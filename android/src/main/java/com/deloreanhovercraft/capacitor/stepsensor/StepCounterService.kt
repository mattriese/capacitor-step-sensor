package com.deloreanhovercraft.capacitor.stepsensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class OriginTickDetail(
    val delta: Long,
    val phoneStepsInRange: Double,
    val watchSurplus: Long,
    val bucketsFilledByOrigin: Int,
    val stepsDistributedByOrigin: Int,
    /** Running cumulative balance for this origin after this tick. Positive = phone ahead, negative = HC ahead. */
    val runningBalanceAfter: Long = 0,
    /** Running balance BEFORE this tick's update. */
    val prevBalance: Long = 0
)

data class TickAuditData(
    val tickNumber: Long,
    val timestamp: String,
    val phoneDelta: Long,
    val latestSensorValue: Long,
    val lastSensorBaseline: Long,
    val hcRecordsCount: Int,
    val hcRecords: List<HcStepRecord>,
    val hcDeltas: Map<String, Long>,
    val existingBucketCount: Int,
    val existingBucketTotalSteps: Int,
    val perOriginDetail: Map<String, OriginTickDetail>,
    val filledBucketCount: Int,
    val filledBucketTotalSteps: Int,
    /** "persisted" if changesToken was restored from SharedPreferences, "fresh" if a new token was fetched */
    val changesTokenSource: String,
    /**
     * Cumulative sum of phone steps that exceeded Samsung's HC delta across all
     * HC-processing ticks (per non-skipped origin). Measures the Jensen's inequality
     * effect: credit lost because max(0, delta - phone) clips negative surplus to 0.
     * If this value ≈ the overcounting gap (app total - Samsung total), it confirms
     * that per-tick clipping is the primary overcounting mechanism.
     */
    val cumulativeLostPhoneCredit: Long,
    /** Raw hcDeltaTracker.lastProcessTime at the time of HC processing (null if baseline tick or no HC data). */
    val lastProcessTime: String? = null,
    /** The 30-second-floored value actually used for the DB query (null if no HC processing). */
    val queryFrom: String? = null,
    /** Duration.between(lastProcessTime, now).toMillis() / 1000.0 — how narrow the phone range is. */
    val rangeSeconds: Double? = null,
    /** Session-level cumulative phone delta total. */
    val sessionPhoneDeltaTotal: Long = 0L,
    /** Session-level cumulative non-"android" HC origin delta total (all external sources: Samsung Health, Fitbit, etc.). */
    val sessionExternalHcDeltaTotal: Long = 0L,
    /** Session-level cumulative surplus steps distributed. */
    val sessionSurplusDistributed: Long = 0L,
    /** Seconds since session start. */
    val sessionUptimeSeconds: Long? = null,
    /** Which origins had non-zero deltas and caused markProcessed to advance (null if no HC processing). */
    val lastProcessTimeAdvancedBy: String? = null,
    /** Restored running balance values, only populated on first tick of session (tickCount == 1). */
    val balanceRestoredValues: Map<String, Long>? = null
)

class StepCounterService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "StepCounterService"
        private const val NOTIFICATION_ID = 9274
        private const val CHANNEL_ID = "step_tracking_channel"
        private const val INTERVAL_MS = 30_000L // 30 seconds
        private const val PREFS_NAME = "step_sensor_service_prefs"
        private const val KEY_NOTIFICATION_TITLE = "notification_title"
        private const val KEY_NOTIFICATION_TEXT = "notification_text"
        private const val KEY_HC_CHANGES_TOKEN = "hc_changes_token"
        private const val KEY_RUNNING_BALANCE_PREFIX = "running_balance_"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastTickAudit: TickAuditData? = null
            private set

        /** Ring buffer of recent tick audits for debugging. Capacity = TICK_AUDIT_BUFFER_SIZE. */
        private const val TICK_AUDIT_BUFFER_SIZE = 100
        val tickAuditHistory: MutableList<TickAuditData> = mutableListOf()
        /** Count of ticks where any single bucket write exceeded MAX_STEPS_PER_BUCKET. */
        @Volatile
        var anomalousTickCount: Int = 0
            private set

        /**
         * Cumulative phone steps that exceeded Samsung's HC delta across all
         * HC-processing ticks. Measures the Jensen's inequality / per-tick clipping
         * effect. Reset on service restart (in-memory only).
         */
        @Volatile
        var cumulativeLostPhoneCredit: Long = 0L
            private set

        /**
         * Running cumulative balance per HC origin.
         * Positive = phone ahead (Samsung lagging, credit saved for later).
         * Negative = HC ahead (watch-only steps to distribute as surplus).
         *
         * Phone credit is accumulated via phoneDelta on EVERY tick (not just
         * when Samsung reports). Samsung's delta is subtracted when Samsung
         * reports. This ensures Samsung batch updates are compared against the
         * full phone credit since the last Samsung update, regardless of how
         * often the android origin advances the shared lastProcessTime.
         *
         * Persisted to SharedPreferences so it survives service restarts.
         */
        val runningBalanceByOrigin: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

        /** "persisted" if balance was restored from SharedPreferences, "fresh" if starting from zero. */
        @Volatile
        var runningBalanceSource: String = "fresh"
            private set

        /** Last restored balance values from SharedPreferences, for inclusion in first tick audit. */
        @Volatile
        var lastRestoredBalances: Map<String, Long> = emptyMap()
            private set

        /** When the current session started (set in onStartCommand). */
        @Volatile
        var sessionStartTime: Instant? = null
            private set

        /**
         * Store notification config before starting the service
         * (since extras may not be available when started from alarm receiver)
         */
        fun setNotificationConfig(context: Context, title: String?, text: String?) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (title != null) putString(KEY_NOTIFICATION_TITLE, title)
                if (text != null) putString(KEY_NOTIFICATION_TEXT, text)
                apply()
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Session-level cumulative counters for diagnostics (instance vars — reset with service)
    private var sessionPhoneDeltaTotal: Long = 0L
    private var sessionExternalHcDeltaTotal: Long = 0L
    private var sessionSurplusDistributed: Long = 0L

    private lateinit var database: StepSensorDatabase
    private lateinit var fitnessNotificationManager: FitnessNotificationManager
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var healthConnectClient: HealthConnectClient? = null

    // Phone sensor state (accessed only on main thread via Handler + SensorEventListener)
    private var latestSensorValue: Long = -1
    private var lastSensorCount: Long = -1

    // Health Connect state (guarded by hcMutex, accessed on IO dispatcher)
    private val hcMutex = Mutex()
    private var changesToken: String? = null
    /** "persisted" if token was restored from SharedPreferences, "fresh" if newly fetched */
    private var changesTokenSource: String = "fresh"

    // HC delta tracking (in-memory, resets on service restart)
    private val hcDeltaTracker = HcDeltaTracker()

    // Time of last tick — used to distribute phone delta across elapsed buckets
    private var lastTickTime: Instant? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            onTimerTick()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = StepSensorDatabase.getInstance(this)
        fitnessNotificationManager = FitnessNotificationManager(this)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground on every onStartCommand invocation within 5 seconds
        // to avoid ForegroundServiceDidNotStartInTimeException
        startForegroundNotification()

        if (isRunning) {
            Log.d(TAG, "Service already running, ignoring duplicate start command")
            return START_STICKY
        }

        isRunning = true
        sessionStartTime = Instant.now()

        startPhoneSensor()
        startHealthConnectPoller()
        startTimer()
        scope.launch {
            try {
                fitnessNotificationManager.reconcileNotifications(Instant.now(), database)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to reconcile fitness notifications on service start", error)
            }
        }

        Log.i(TAG, "Step tracking started | plugin build: ${PluginBuildInfo.BUILD_ID}")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopTimer()
        stopPhoneSensor()
        scope.cancel()
        Log.d(TAG, "Step tracking stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notification ---

    private fun startForegroundNotification() {
        createNotificationChannel()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appName = applicationInfo.loadLabel(packageManager).toString()
        val title = prefs.getString(KEY_NOTIFICATION_TITLE, null) ?: "Tracking steps for $appName"
        val text = prefs.getString(KEY_NOTIFICATION_TEXT, null) ?: "Step counting is active"

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while step tracking is active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // --- Phone Sensor (TYPE_STEP_COUNTER) ---

    private fun startPhoneSensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            val registered = sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Phone step sensor registered=$registered | sensor=${stepSensor?.name} | vendor=${stepSensor?.vendor}")
        } else {
            val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL)?.map { "${it.type}:${it.name}" }
            Log.w(TAG, "TYPE_STEP_COUNTER sensor not available | allSensorTypes=${allSensors?.take(20)}")
        }
    }

    private fun stopPhoneSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        stepSensor = null
    }

    private var sensorEventCount = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val prev = latestSensorValue
            latestSensorValue = event.values[0].toLong()
            sensorEventCount++
            // Log every 10th event to avoid spam
            if (sensorEventCount % 10 == 1L) {
                Log.d(TAG, "SENSOR_EVENT #$sensorEventCount | prev=$prev | new=$latestSensorValue | delta=${latestSensorValue - if (prev >= 0) prev else latestSensorValue}")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun recordPhoneSensorInterval(): Long {
        val (delta, newBaseline) = StepTrackingLogic.computeSensorDelta(latestSensorValue, lastSensorCount)
        if (delta == 0L && latestSensorValue >= 0 && lastSensorCount >= 0 && latestSensorValue < lastSensorCount) {
            Log.d(TAG, "Sensor counter reset detected (reboot?). Resetting baseline.")
        }
        lastSensorCount = newBaseline
        return delta
    }

    // --- Health Connect Poller ---

    private fun startHealthConnectPoller() {
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.w(TAG, "Health Connect not available (status=$sdkStatus), skipping HC poller")
            return
        }

        try {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Health Connect client", e)
            return
        }

        // Try to restore a persisted changes token to avoid a fresh baseline snapshot
        // on every service restart. If no token is stored, fall back to fetching a new
        // token + a 48h baseline snapshot (original behavior).
        //
        // Order matters for the fresh-token path: token FIRST, then snapshot.
        // Any HC changes after the token is obtained will appear in subsequent
        // getChanges() calls. The snapshot gives us baseline counts to diff against,
        // so late-appearing records (e.g. Samsung's daily cumulative record) produce
        // correct small deltas instead of crediting their entire cumulative count.
        scope.launch {
            hcMutex.withLock {
                try {
                    val client = healthConnectClient!!
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val storedToken = prefs.getString(KEY_HC_CHANGES_TOKEN, null)

                    if (storedToken != null) {
                        // Validate the stored token before trusting it
                        try {
                            val testResponse = client.getChanges(storedToken)
                            if (testResponse.changesTokenExpired) {
                                // Token expired — fall through to fresh init
                                Log.w(TAG, "Persisted HC changes token expired, fetching new one")
                                initFreshChangesToken(client, prefs)
                            } else {
                                // Token is valid — restore it and advance to the latest token
                                // (consuming any changes that occurred while service was stopped)
                                changesToken = testResponse.nextChangesToken
                                changesTokenSource = "persisted"
                                saveChangesToken(prefs, changesToken!!)
                                val changeCount = testResponse.changes.size
                                Log.d(TAG, "HC changes token restored from SharedPreferences" +
                                    " | changesWhileStopped=$changeCount" +
                                    " | nextToken=${changesToken?.take(20)}...")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Stored HC token validation failed, fetching fresh token", e)
                            initFreshChangesToken(client, prefs)
                        }
                    } else {
                        initFreshChangesToken(client, prefs)
                    }

                    // Restore running balances from SharedPreferences so phone credit
                    // accumulated before the service restart is not lost.
                    restoreRunningBalances(prefs)

                    // Ensure Samsung origin exists in balance map so phoneDelta
                    // accumulates from the first tick, not just after Samsung first reports.
                    if (!runningBalanceByOrigin.containsKey("com.sec.android.app.shealth")) {
                        runningBalanceByOrigin["com.sec.android.app.shealth"] = 0L
                    }

                    Log.d(TAG, "Health Connect poller initialized | tokenSource=$changesTokenSource" +
                        " | balanceSource=$runningBalanceSource")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Health Connect poller", e)
                    healthConnectClient = null
                }
            }
        }
    }

    /** Fetch a fresh HC changes token and take a 48h baseline snapshot. */
    private suspend fun initFreshChangesToken(
        client: HealthConnectClient,
        prefs: android.content.SharedPreferences
    ) {
        changesToken = client.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
        )
        changesTokenSource = "fresh"
        saveChangesToken(prefs, changesToken!!)

        // Snapshot all existing step records to establish delta baselines.
        val now = Instant.now()
        val snapshot = client.readRecords(
            ReadRecordsRequest(
                StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    now.minus(Duration.ofHours(48)), now
                )
            )
        )

        if (snapshot.records.isNotEmpty()) {
            val baselineRecords = snapshot.records.map { sr ->
                HcStepRecord(
                    recordId = sr.metadata.id,
                    startTime = sr.startTime,
                    endTime = sr.endTime,
                    count = sr.count,
                    dataOrigin = sr.metadata.dataOrigin.packageName
                )
            }
            hcDeltaTracker.computeDeltas(baselineRecords)
            hcDeltaTracker.markProcessed(now)
            Log.d(TAG, "HC fresh init: baseline snapshot=${baselineRecords.size} records" +
                " | origins=${baselineRecords.map { it.dataOrigin }.toSet()}")
        } else {
            Log.d(TAG, "HC fresh init: no records in 48h window, " +
                "first timer tick will establish baselines")
        }
    }

    private fun saveChangesToken(prefs: android.content.SharedPreferences, token: String) {
        prefs.edit().putString(KEY_HC_CHANGES_TOKEN, token).apply()
    }

    private fun saveRunningBalance(prefs: android.content.SharedPreferences, origin: String, balance: Long) {
        prefs.edit().putLong("$KEY_RUNNING_BALANCE_PREFIX$origin", balance).apply()
    }

    private fun restoreRunningBalances(prefs: android.content.SharedPreferences) {
        var restoredCount = 0
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_RUNNING_BALANCE_PREFIX) && value is Long) {
                val origin = key.removePrefix(KEY_RUNNING_BALANCE_PREFIX)
                runningBalanceByOrigin[origin] = value
                restoredCount++
                Log.d(TAG, "Restored running balance | origin=$origin | balance=$value")
            }
        }
        if (restoredCount > 0) {
            runningBalanceSource = "persisted"
            Log.d(TAG, "Running balances restored from SharedPreferences | count=$restoredCount")
        } else {
            runningBalanceSource = "fresh"
            Log.d(TAG, "No persisted running balances found, starting from zero")
        }
        lastRestoredBalances = runningBalanceByOrigin.toMap()
    }

    private suspend fun collectHcRecords(): List<HcStepRecord> {
        if (healthConnectClient == null) {
            Log.d(TAG, "collectHcRecords: healthConnectClient is null, returning empty")
            return emptyList()
        }
        val client = healthConnectClient!!

        return hcMutex.withLock {
            if (changesToken == null) {
                Log.d(TAG, "collectHcRecords: changesToken is null, returning empty")
                return@withLock emptyList()
            }
            val token = changesToken!!
            val records = mutableListOf<HcStepRecord>()

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            try {
                var nextToken = token

                do {
                    val response = client.getChanges(nextToken)

                    if (response.changesTokenExpired) {
                        Log.w(TAG, "Health Connect changes token expired, re-initializing")
                        val newToken = client.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
                        )
                        changesToken = newToken
                        changesTokenSource = "fresh"
                        // Clear the stored token so next service start also refreshes
                        prefs.edit().remove(KEY_HC_CHANGES_TOKEN).apply()
                        return@withLock emptyList()
                    }

                    for (change in response.changes) {
                        if (change is UpsertionChange && change.record is StepsRecord) {
                            val sr = change.record as StepsRecord
                            records.add(
                                HcStepRecord(
                                    recordId = sr.metadata.id,
                                    startTime = sr.startTime,
                                    endTime = sr.endTime,
                                    count = sr.count,
                                    dataOrigin = sr.metadata.dataOrigin.packageName
                                )
                            )
                        }
                    }
                    nextToken = response.nextChangesToken
                } while (response.hasMore)

                changesToken = nextToken
                // Persist the advanced token after each successful collection
                saveChangesToken(prefs, nextToken)
                records
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting Health Connect records", e)
                emptyList()
            }
        }
    }

    // --- Timer & Merge Logic ---

    private fun startTimer() {
        handler.postDelayed(timerRunnable, INTERVAL_MS)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
    }

    private var tickCount = 0L

    private fun recordTickAudit(audit: TickAuditData) {
        lastTickAudit = audit
        synchronized(tickAuditHistory) {
            tickAuditHistory.add(audit)
            while (tickAuditHistory.size > TICK_AUDIT_BUFFER_SIZE) {
                tickAuditHistory.removeAt(0)
            }
        }
    }

    private fun onTimerTick() {
        tickCount++
        val phoneDelta = recordPhoneSensorInterval()
        val capturedLatestSensor = latestSensorValue
        val capturedLastBaseline = lastSensorCount

        // Session-level phone delta tracking (change 4)
        sessionPhoneDeltaTotal += phoneDelta

        scope.launch {
            val now = Instant.now()
            val sessionUptime = sessionStartTime?.let { Duration.between(it, now).seconds }
            val balanceRestored = if (tickCount == 1L) lastRestoredBalances.ifEmpty { null } else null
            try {

                // Distribute phone delta across elapsed buckets instead of dumping
                // into a single bucket. Prevents >90 step buckets when tick timer
                // is delayed by Doze mode or system scheduling.
                if (phoneDelta > 0) {
                    val distributed = StepTrackingLogic.distributePhoneDelta(
                        phoneDelta, lastTickTime, now
                    )
                    for ((bucketStart, steps) in distributed) {
                        val bucketEnd = bucketStart.plusSeconds(30)
                        database.insertOrUpdate(bucketStart, bucketEnd, steps)
                    }
                    if (distributed.values.any { it > StepTrackingLogic.MAX_STEPS_PER_BUCKET }) {
                        anomalousTickCount++
                        Log.w(TAG, "ANOMALY | phoneDelta=$phoneDelta exceeded cap in ${distributed.size} buckets")
                    }
                }
                lastTickTime = now

                // Accumulate phone credit into running balance on every tick.
                // This ensures Samsung's batch deltas are compared against the full
                // phone credit since the last Samsung tick, not just the narrow
                // lastProcessTime-based DB query range (which is shortened by
                // android-origin ticks advancing the shared lastProcessTime).
                if (phoneDelta > 0) {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    for ((origin, balance) in runningBalanceByOrigin) {
                        if (origin == "android") continue
                        val newBalance = balance + phoneDelta
                        runningBalanceByOrigin[origin] = newBalance
                        saveRunningBalance(prefs, origin, newBalance)
                    }
                }

                val hcRecords = collectHcRecords()

            // Log every tick so we can verify the service is alive and see what's happening
            Log.d(TAG, "TICK #$tickCount | now=$now | phoneDelta=$phoneDelta" +
                " | latestSensor=$capturedLatestSensor | lastBaseline=$capturedLastBaseline" +
                " | hcRecords=${hcRecords.size}" +
                " | hcClientNull=${healthConnectClient == null}" +
                " | changesTokenNull=${changesToken == null}")

            if (hcRecords.isNotEmpty()) {
                for (rec in hcRecords) {
                    Log.d(TAG, "  HC_RECORD | start=${rec.startTime} | end=${rec.endTime}" +
                        " | count=${rec.count} | origin=${rec.dataOrigin}" +
                        " | spanSeconds=${Duration.between(rec.startTime, rec.endTime).seconds}")
                }
            }

            if (hcRecords.isEmpty()) {
                recordTickAudit(TickAuditData(
                    tickNumber = tickCount,
                    timestamp = now.toString(),
                    phoneDelta = phoneDelta,
                    latestSensorValue = capturedLatestSensor,
                    lastSensorBaseline = capturedLastBaseline,
                    hcRecordsCount = 0,
                    hcRecords = emptyList(),
                    hcDeltas = emptyMap(),
                    existingBucketCount = 0,
                    existingBucketTotalSteps = 0,
                    perOriginDetail = emptyMap(),
                    filledBucketCount = 0,
                    filledBucketTotalSteps = 0,
                    changesTokenSource = changesTokenSource,
                    cumulativeLostPhoneCredit = cumulativeLostPhoneCredit,
                    sessionPhoneDeltaTotal = sessionPhoneDeltaTotal,
                    sessionExternalHcDeltaTotal = sessionExternalHcDeltaTotal,
                    sessionSurplusDistributed = sessionSurplusDistributed,
                    sessionUptimeSeconds = sessionUptime,
                    balanceRestoredValues = balanceRestored
                ))
                return@launch
            }

            // --- HC Delta Tracking ---
            val deltas = hcDeltaTracker.computeDeltas(hcRecords)

            if (hcDeltaTracker.lastProcessTime == null) {
                // First call: baselines just established, no deltas to process
                Log.d(TAG, "HC_DELTA | Baseline established | records=${hcRecords.size}" +
                    " | origins=${deltas.keys}")
                hcDeltaTracker.markProcessed(now)
                recordTickAudit(TickAuditData(
                    tickNumber = tickCount,
                    timestamp = now.toString(),
                    phoneDelta = phoneDelta,
                    latestSensorValue = capturedLatestSensor,
                    lastSensorBaseline = capturedLastBaseline,
                    hcRecordsCount = hcRecords.size,
                    hcRecords = hcRecords,
                    hcDeltas = deltas,
                    existingBucketCount = 0,
                    existingBucketTotalSteps = 0,
                    perOriginDetail = emptyMap(),
                    filledBucketCount = 0,
                    filledBucketTotalSteps = 0,
                    changesTokenSource = changesTokenSource,
                    cumulativeLostPhoneCredit = cumulativeLostPhoneCredit,
                    sessionPhoneDeltaTotal = sessionPhoneDeltaTotal,
                    sessionExternalHcDeltaTotal = sessionExternalHcDeltaTotal,
                    sessionSurplusDistributed = sessionSurplusDistributed,
                    sessionUptimeSeconds = sessionUptime,
                    balanceRestoredValues = balanceRestored
                ))
                return@launch
            }

            val totalDelta = deltas.values.sum()
            if (totalDelta == 0L) {
                Log.d(TAG, "HC_DELTA | No new steps | deltas=$deltas")
                recordTickAudit(TickAuditData(
                    tickNumber = tickCount,
                    timestamp = now.toString(),
                    phoneDelta = phoneDelta,
                    latestSensorValue = capturedLatestSensor,
                    lastSensorBaseline = capturedLastBaseline,
                    hcRecordsCount = hcRecords.size,
                    hcRecords = hcRecords,
                    hcDeltas = deltas,
                    existingBucketCount = 0,
                    existingBucketTotalSteps = 0,
                    perOriginDetail = emptyMap(),
                    filledBucketCount = 0,
                    filledBucketTotalSteps = 0,
                    changesTokenSource = changesTokenSource,
                    cumulativeLostPhoneCredit = cumulativeLostPhoneCredit,
                    lastProcessTime = hcDeltaTracker.lastProcessTime?.toString(),
                    sessionPhoneDeltaTotal = sessionPhoneDeltaTotal,
                    sessionExternalHcDeltaTotal = sessionExternalHcDeltaTotal,
                    sessionSurplusDistributed = sessionSurplusDistributed,
                    sessionUptimeSeconds = sessionUptime,
                    balanceRestoredValues = balanceRestored
                ))
                return@launch
            }

            val lastProcessTime = hcDeltaTracker.lastProcessTime!!
            val rangeSeconds = Duration.between(lastProcessTime, now).toMillis() / 1000.0
            Log.d(TAG, "HC_DELTA | deltas=$deltas | range=[$lastProcessTime, $now) | rangeSeconds=$rangeSeconds")

            val hcRecordsJson = StepTrackingLogic.serializeHcRecords(hcRecords)

            // Read existing bucket data for [lastProcessTime, now).
            // Floor lastProcessTime to a 30-second boundary so the query captures
            // the current tick's phone bucket. Without this, lastProcessTime at e.g.
            // 23:40:01.567Z would miss a phone bucket at 23:40:00Z because
            // bucket_start (23:40:00) < lastProcessTime (23:40:01.567).
            val queryFrom = StepTrackingLogic.floorTo30Seconds(lastProcessTime)
            val existingRows = database.getStepsSince(queryFrom)
            val existingBuckets = existingRows
                .filter { Instant.parse(it.bucketStart).isBefore(now) }
                .associate { Instant.parse(it.bucketStart) to it.steps }

            val allFilledBuckets = mutableMapOf<Instant, Int>()
            val originDetails = mutableMapOf<String, OriginTickDetail>()

            for ((origin, delta) in deltas) {
                if (delta <= 0) {
                    originDetails[origin] = OriginTickDetail(delta, 0.0, 0, 0, 0)
                    continue
                }

                // Skip the phone's own pedometer HC origin — TYPE_STEP_COUNTER
                // already captures these steps directly. Distributing surplus from
                // the "android" origin double-counts the same physical sensor.
                if (origin == "android") {
                    originDetails[origin] = OriginTickDetail(delta, 0.0, 0, 0, 0)
                    Log.d(TAG, "HC_DELTA | SKIP origin=$origin (phone pedometer) | delta=$delta")
                    continue
                }

                // Use queryFrom (floored to 30s boundary) instead of raw lastProcessTime.
                val phoneTotalInRange = StepTrackingLogic.computeProratedPhoneSteps(
                    existingBuckets, queryFrom, now
                )
                val phoneInRangeLong = phoneTotalInRange.toLong()

                // Running cumulative balance: phone credit from one tick offsets
                // Samsung surplus from another, eliminating Jensen's inequality.
                // balance > 0 = phone ahead (Samsung lagging, credit saved)
                // balance < 0 = HC ahead (watch-only steps to distribute)
                val prevBalance = runningBalanceByOrigin.getOrDefault(origin, 0L)
                // Phone credit is now accumulated via phoneDelta on every tick (step 2 above).
                // Only subtract Samsung's delta here. The old approach used phoneInRange
                // (prorated phone steps from the lastProcessTime DB range), but that window
                // was narrowed by android-origin ticks advancing the shared lastProcessTime,
                // causing phoneInRange to capture only ~29% of actual phone steps.
                val newBalance = prevBalance - delta

                val watchSurplus: Long
                if (newBalance < 0) {
                    // HC is cumulatively ahead — distribute the deficit
                    watchSurplus = -newBalance
                } else {
                    watchSurplus = 0L
                }

                // Track Jensen's inequality effect for observability.
                // This still measures credit that WOULD have been lost under the
                // old per-tick max(0,...) approach, for comparison.
                val lostCredit = max(0L, phoneInRangeLong - delta)
                if (lostCredit > 0) {
                    cumulativeLostPhoneCredit += lostCredit
                }

                Log.d(TAG, "HC_DELTA | origin=$origin | delta=$delta" +
                    " | phoneInRange=$phoneInRangeLong (audit only, not used for balance)" +
                    " | prevBalance=$prevBalance | newBalance=$newBalance" +
                    " | watchSurplus=$watchSurplus" +
                    " | lostCredit=$lostCredit | cumulativeLostCredit=$cumulativeLostPhoneCredit")

                var originBucketsFilled = 0
                var originStepsDistributed = 0

                if (watchSurplus > 0) {
                    val filled = StepTrackingLogic.distributeWatchSurplus(
                        watchSurplus, existingBuckets, lastProcessTime, now, now = now
                    )
                    for ((bucketStart, steps) in filled) {
                        allFilledBuckets[bucketStart] =
                            max(allFilledBuckets[bucketStart] ?: 0, steps)
                    }
                    originBucketsFilled = filled.size
                    originStepsDistributed = filled.values.sum()
                }

                // Session-level external HC delta and surplus tracking
                sessionExternalHcDeltaTotal += delta
                sessionSurplusDistributed += originStepsDistributed

                // Update balance: consume only what was actually distributed.
                // If surplus couldn't be fully distributed (not enough zero buckets),
                // the remaining deficit carries forward.
                val actuallyDistributed = originStepsDistributed.toLong()
                val balanceAfter = if (newBalance < 0) {
                    newBalance + actuallyDistributed
                } else {
                    newBalance
                }
                runningBalanceByOrigin[origin] = balanceAfter
                saveRunningBalance(
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    origin, balanceAfter
                )

                originDetails[origin] = OriginTickDetail(
                    delta = delta,
                    phoneStepsInRange = phoneTotalInRange,
                    watchSurplus = watchSurplus,
                    bucketsFilledByOrigin = originBucketsFilled,
                    stepsDistributedByOrigin = originStepsDistributed,
                    runningBalanceAfter = balanceAfter,
                    prevBalance = prevBalance
                )
            }

            Log.d(TAG, "HC_DELTA_FILL | existingBuckets=${existingBuckets.size}" +
                " | existingTotal=${existingBuckets.values.sum()}" +
                " | filledBuckets=${allFilledBuckets.size}" +
                " | filledTotal=${allFilledBuckets.values.sum()}")

            // Write filled values to DB
            for ((bucketStart, steps) in allFilledBuckets) {
                if (steps > 0) {
                    val bucketEnd = bucketStart.plusSeconds(30)
                    database.insertOrUpdate(bucketStart, bucketEnd, steps, hcRecordsJson)
                }
            }

            // Check for anomalous HC fills
            if (allFilledBuckets.values.any { it > StepTrackingLogic.MAX_STEPS_PER_BUCKET }) {
                anomalousTickCount++
                Log.w(TAG, "ANOMALY | HC fill produced >90 step bucket | " +
                    "filled=${allFilledBuckets.entries.filter { it.value > StepTrackingLogic.MAX_STEPS_PER_BUCKET }}")
            }

            // Determine which origins caused markProcessed to advance (change 5)
            val advancedBy = deltas.entries
                .filter { it.value > 0 }
                .map { it.key }
                .joinToString(",")

            recordTickAudit(TickAuditData(
                tickNumber = tickCount,
                timestamp = now.toString(),
                phoneDelta = phoneDelta,
                latestSensorValue = capturedLatestSensor,
                lastSensorBaseline = capturedLastBaseline,
                hcRecordsCount = hcRecords.size,
                hcRecords = hcRecords,
                hcDeltas = deltas,
                existingBucketCount = existingBuckets.size,
                existingBucketTotalSteps = existingBuckets.values.sum(),
                perOriginDetail = originDetails,
                filledBucketCount = allFilledBuckets.size,
                filledBucketTotalSteps = allFilledBuckets.values.sum(),
                changesTokenSource = changesTokenSource,
                cumulativeLostPhoneCredit = cumulativeLostPhoneCredit,
                lastProcessTime = lastProcessTime.toString(),
                queryFrom = queryFrom.toString(),
                rangeSeconds = rangeSeconds,
                sessionPhoneDeltaTotal = sessionPhoneDeltaTotal,
                sessionExternalHcDeltaTotal = sessionExternalHcDeltaTotal,
                sessionSurplusDistributed = sessionSurplusDistributed,
                sessionUptimeSeconds = sessionUptime,
                lastProcessTimeAdvancedBy = advancedBy,
                balanceRestoredValues = balanceRestored
            ))

                hcDeltaTracker.markProcessed(now)
            } finally {
                try {
                    fitnessNotificationManager.reconcileNotifications(Instant.now(), database)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to reconcile fitness notifications on tick", error)
                }
            }
        }
    }
}

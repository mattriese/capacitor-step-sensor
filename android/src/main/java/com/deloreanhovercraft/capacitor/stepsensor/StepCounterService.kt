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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class StepCounterService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "StepCounterService"
        private const val NOTIFICATION_ID = 9274
        private const val CHANNEL_ID = "step_tracking_channel"
        private const val INTERVAL_MS = 30_000L // 30 seconds
        private const val PREFS_NAME = "step_sensor_service_prefs"
        private const val KEY_NOTIFICATION_TITLE = "notification_title"
        private const val KEY_NOTIFICATION_TEXT = "notification_text"

        @Volatile
        var isRunning = false
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

    private lateinit var database: StepSensorDatabase
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var healthConnectClient: HealthConnectClient? = null

    // Phone sensor state (accessed only on main thread via Handler + SensorEventListener)
    private var latestSensorValue: Long = -1
    private var lastSensorCount: Long = -1

    // Health Connect state (guarded by hcMutex, accessed on IO dispatcher)
    private val hcMutex = Mutex()
    private var changesToken: String? = null

    // HC delta tracking (in-memory, resets on service restart)
    private val hcDeltaTracker = HcDeltaTracker()

    private val timerRunnable = object : Runnable {
        override fun run() {
            onTimerTick()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = StepSensorDatabase.getInstance(this)
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

        startPhoneSensor()
        startHealthConnectPoller()
        startTimer()

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

        // Get initial changes token
        scope.launch {
            hcMutex.withLock {
                try {
                    changesToken = healthConnectClient!!.getChangesToken(
                        ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
                    )
                    Log.d(TAG, "Health Connect poller initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Health Connect poller", e)
                    healthConnectClient = null
                }
            }
        }
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

            try {
                var nextToken = token

                do {
                    val response = client.getChanges(nextToken)

                    if (response.changesTokenExpired) {
                        Log.w(TAG, "Health Connect changes token expired, re-initializing")
                        changesToken = client.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
                        )
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

    private fun onTimerTick() {
        tickCount++
        val phoneDelta = recordPhoneSensorInterval()

        scope.launch {
            val now = Instant.now()
            val (currentBucketStart, currentBucketEnd) = StepTrackingLogic.computeBucketBoundaries(now)

            // Always write phone data first — it's real-time and per-bucket accurate
            if (phoneDelta > 0) {
                database.insertOrUpdate(currentBucketStart, currentBucketEnd, phoneDelta.toInt())
            }

            val hcRecords = collectHcRecords()

            // Log every tick so we can verify the service is alive and see what's happening
            Log.d(TAG, "TICK #$tickCount | now=$now | phoneDelta=$phoneDelta" +
                " | latestSensor=$latestSensorValue | lastBaseline=$lastSensorCount" +
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

            if (hcRecords.isEmpty()) return@launch

            // --- HC Delta Tracking ---
            val deltas = hcDeltaTracker.computeDeltas(hcRecords)

            if (hcDeltaTracker.lastProcessTime == null) {
                // First call: baselines just established, no deltas to process
                Log.d(TAG, "HC_DELTA | Baseline established | records=${hcRecords.size}" +
                    " | origins=${deltas.keys}")
                hcDeltaTracker.markProcessed(now)
                return@launch
            }

            val totalDelta = deltas.values.sum()
            if (totalDelta == 0L) {
                Log.d(TAG, "HC_DELTA | No new steps | deltas=$deltas")
                return@launch
            }

            val lastProcessTime = hcDeltaTracker.lastProcessTime!!
            Log.d(TAG, "HC_DELTA | deltas=$deltas | range=[$lastProcessTime, $now)")

            val hcRecordsJson = StepTrackingLogic.serializeHcRecords(hcRecords)

            // Read existing bucket data for [lastProcessTime, now)
            val existingRows = database.getStepsSince(lastProcessTime)
            val existingBuckets = existingRows
                .filter { Instant.parse(it.bucketStart).isBefore(now) }
                .associate { Instant.parse(it.bucketStart) to it.steps }

            val allFilledBuckets = mutableMapOf<Instant, Int>()

            for ((origin, delta) in deltas) {
                if (delta <= 0) continue

                val phoneTotalInRange = StepTrackingLogic.computeProratedPhoneSteps(
                    existingBuckets, lastProcessTime, now
                )
                val watchSurplus = max(0L, delta - phoneTotalInRange.toLong())

                Log.d(TAG, "HC_DELTA | origin=$origin | delta=$delta" +
                    " | phoneInRange=${phoneTotalInRange.toLong()} | watchSurplus=$watchSurplus")

                if (watchSurplus > 0) {
                    val filled = StepTrackingLogic.distributeWatchSurplus(
                        watchSurplus, existingBuckets, lastProcessTime, now, now = now
                    )
                    for ((bucketStart, steps) in filled) {
                        allFilledBuckets[bucketStart] =
                            max(allFilledBuckets[bucketStart] ?: 0, steps)
                    }
                }
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

            hcDeltaTracker.markProcessed(now)
        }
    }
}

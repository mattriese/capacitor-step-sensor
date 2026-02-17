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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var lastHcTotal: Long = -1
    private var trackingStartTime: Instant? = null

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
        trackingStartTime = Instant.now()

        startPhoneSensor()
        startHealthConnectPoller()
        startTimer()

        Log.d(TAG, "Step tracking started")
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
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Phone step sensor registered")
        } else {
            Log.w(TAG, "TYPE_STEP_COUNTER sensor not available on this device")
        }
    }

    private fun stopPhoneSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        stepSensor = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            latestSensorValue = event.values[0].toLong()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    private fun recordPhoneSensorInterval(): Long {
        val currentCount = latestSensorValue
        if (currentCount < 0) return 0

        val delta = if (lastSensorCount >= 0) {
            if (currentCount >= lastSensorCount) {
                currentCount - lastSensorCount
            } else {
                // Reboot detected — counter reset to 0
                Log.d(TAG, "Sensor counter reset detected (reboot?). Resetting baseline.")
                0
            }
        } else {
            // First reading — establish baseline, no delta yet
            0
        }

        lastSensorCount = currentCount
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
                    // Get initial HC total as baseline
                    lastHcTotal = getHcTotalSteps()
                    Log.d(TAG, "Health Connect poller initialized, baseline total=$lastHcTotal")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Health Connect poller", e)
                    healthConnectClient = null
                }
            }
        }
    }

    private suspend fun recordHcInterval(): Long {
        val client = healthConnectClient ?: return 0

        return hcMutex.withLock {
            val token = changesToken ?: return@withLock 0L

            try {
                // Check if there are any changes
                var hasChanges = false
                var nextToken = token

                do {
                    val response = client.getChanges(nextToken)

                    // Handle expired token — re-initialize and skip this interval
                    if (response.changesTokenExpired) {
                        Log.w(TAG, "Health Connect changes token expired, re-initializing")
                        changesToken = client.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(StepsRecord::class))
                        )
                        lastHcTotal = getHcTotalSteps()
                        return@withLock 0L
                    }

                    if (response.changes.any { it is UpsertionChange }) {
                        hasChanges = true
                    }
                    nextToken = response.nextChangesToken
                } while (response.hasMore)

                changesToken = nextToken

                if (!hasChanges) return@withLock 0L

                // Changes detected — get current total and compute delta
                val currentTotal = getHcTotalSteps()
                val delta = if (lastHcTotal >= 0) {
                    max(0, currentTotal - lastHcTotal)
                } else {
                    0
                }
                lastHcTotal = currentTotal
                delta
            } catch (e: Exception) {
                Log.e(TAG, "Error polling Health Connect", e)
                0L
            }
        }
    }

    private suspend fun getHcTotalSteps(): Long {
        val client = healthConnectClient ?: return 0
        val startTime = trackingStartTime ?: return 0

        return try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, Instant.now())
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error aggregating Health Connect steps", e)
            0
        }
    }

    // --- Timer & Merge Logic ---

    private fun startTimer() {
        handler.postDelayed(timerRunnable, INTERVAL_MS)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
    }

    private fun onTimerTick() {
        val phoneDelta = recordPhoneSensorInterval()

        scope.launch {
            val hcDelta = recordHcInterval()
            val steps = max(phoneDelta, hcDelta).toInt()

            val now = Instant.now()
            val bucketEnd = floorTo30Seconds(now)
            val bucketStart = bucketEnd.minusSeconds(30)

            if (steps > 0) {
                database.insertOrUpdate(bucketStart, bucketEnd, steps)
                Log.d(TAG, "Recorded $steps steps (phone=$phoneDelta, hc=$hcDelta) for bucket $bucketStart")
            }
        }
    }

    private fun floorTo30Seconds(instant: Instant): Instant {
        val epochSecond = instant.epochSecond
        val floored = epochSecond - (epochSecond % 30)
        return Instant.ofEpochSecond(floored)
    }
}

package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@CapacitorPlugin(
    name = "StepSensor",
    permissions = [
        Permission(
            strings = [Manifest.permission.ACTIVITY_RECOGNITION],
            alias = "activityRecognition"
        ),
        Permission(
            strings = [Manifest.permission.POST_NOTIFICATIONS],
            alias = "notifications"
        )
    ]
)
class StepSensorPlugin : Plugin() {

    companion object {
        private const val TAG = "StepSensorPlugin"
    }

    private lateinit var scheduler: StepTrackingScheduler
    private lateinit var database: StepSensorDatabase

    override fun load() {
        scheduler = StepTrackingScheduler(context)
        database = StepSensorDatabase.getInstance(context)
    }

    @PluginMethod
    fun scheduleStepTracking(call: PluginCall) {
        val windowsArray = call.getArray("windows")
        if (windowsArray == null) {
            call.reject("Missing required parameter: windows")
            return
        }

        try {
            val windows = mutableListOf<TrackingWindow>()
            for (i in 0 until windowsArray.length()) {
                val obj = windowsArray.getJSONObject(i)
                val startAt = Instant.parse(obj.getString("startAt"))
                val endAt = Instant.parse(obj.getString("endAt"))
                windows.add(TrackingWindow(startAt, endAt))
            }

            scheduler.scheduleWindows(windows)
            call.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule step tracking", e)
            call.reject("Failed to schedule step tracking: ${e.message}")
        }
    }

    @PluginMethod
    fun startStepTracking(call: PluginCall) {
        if (StepCounterService.isRunning) {
            call.resolve()
            return
        }

        // Store notification config if provided
        val notificationTitle = call.getString("notificationTitle")
        val notificationText = call.getString("notificationText")
        StepCounterService.setNotificationConfig(context, notificationTitle, notificationText)

        // Check permissions before starting
        if (!hasAllRequiredPermissions()) {
            // Save the call so we can resolve it after permission result
            bridge.saveCall(call)
            requestAllPermissions(call, "permissionCallback")
            return
        }

        doStartTracking(call)
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        if (!hasActivityRecognitionPermission()) {
            call.reject("ACTIVITY_RECOGNITION permission is required for step tracking")
            return
        }

        // POST_NOTIFICATIONS is not strictly required — the service can still run,
        // but the notification may not show on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — notification may not display")
        }

        doStartTracking(call)
    }

    private fun doStartTracking(call: PluginCall) {
        try {
            scheduler.startServiceNow()
            call.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start step tracking", e)
            call.reject("Failed to start step tracking: ${e.message}")
        }
    }

    @PluginMethod
    fun stopStepTracking(call: PluginCall) {
        try {
            scheduler.stopServiceNow()
            call.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop step tracking", e)
            call.reject("Failed to stop step tracking: ${e.message}")
        }
    }

    @PluginMethod
    fun getTrackedSteps(call: PluginCall) {
        try {
            val sinceStr = call.getString("since")
            val modifiedSinceStr = call.getString("modifiedSince")

            // Capture syncToken BEFORE querying so no writes are missed
            val syncToken = Instant.now().toString()

            val since = sinceStr?.let { Instant.parse(it) }
            val modifiedSince = modifiedSinceStr?.let { Instant.parse(it) }
            val buckets = database.getStepsSince(since, modifiedSince)

            val stepsArray = JSArray()
            buckets.forEach { bucket ->
                val obj = JSObject().apply {
                    put("bucketStart", bucket.bucketStart)
                    put("bucketEnd", bucket.bucketEnd)
                    put("steps", bucket.steps)
                    if (bucket.hcMetadata != null) {
                        put("hcMetadata", bucket.hcMetadata)
                    }
                }
                stepsArray.put(obj)
            }

            val result = JSObject().apply {
                put("steps", stepsArray)
                put("syncToken", syncToken)
            }
            call.resolve(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tracked steps", e)
            call.reject("Failed to get tracked steps: ${e.message}")
        }
    }

    @PluginMethod
    fun backfillFromHealthConnect(call: PluginCall) {
        val windowsArray = call.getArray("windows")
        if (windowsArray == null) {
            call.reject("Missing required parameter: windows")
            return
        }

        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.w(TAG, "Health Connect not available (status=$sdkStatus)")
            val result = JSObject().apply { put("backedUp", false) }
            call.resolve(result)
            return
        }

        val client: HealthConnectClient
        try {
            client = HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Health Connect client", e)
            val result = JSObject().apply { put("backedUp", false) }
            call.resolve(result)
            return
        }

        val windows = mutableListOf<TrackingWindow>()
        try {
            for (i in 0 until windowsArray.length()) {
                val obj = windowsArray.getJSONObject(i)
                val startAt = Instant.parse(obj.getString("startAt"))
                val endAt = Instant.parse(obj.getString("endAt"))
                windows.add(TrackingWindow(startAt, endAt))
            }
        } catch (e: Exception) {
            call.reject("Failed to parse windows: ${e.message}")
            return
        }

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val auditWindows = JSArray()

                for (window in windows) {
                    val request = ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(window.startAt, window.endAt)
                    )
                    val response = client.readRecords(request)

                    val hcRecords = response.records.map { sr ->
                        HcStepRecord(
                            recordId = sr.metadata.id,
                            startTime = sr.startTime,
                            endTime = sr.endTime,
                            count = sr.count,
                            dataOrigin = sr.metadata.dataOrigin.packageName
                        )
                    }

                    Log.d(TAG, "BACKFILL_READ | window=${window.startAt}–${window.endAt}" +
                        " | hcRecordsCount=${hcRecords.size}")
                    for (rec in hcRecords) {
                        val spanSeconds = Duration.between(rec.startTime, rec.endTime).seconds
                        Log.d(TAG, "  BACKFILL_HC_RECORD | start=${rec.startTime}" +
                            " | end=${rec.endTime} | count=${rec.count}" +
                            " | origin=${rec.dataOrigin} | spanSeconds=$spanSeconds")
                    }

                    if (hcRecords.isEmpty()) {
                        auditWindows.put(JSObject().apply {
                            put("startAt", window.startAt.toString())
                            put("endAt", window.endAt.toString())
                            put("hcRecords", JSArray())
                            put("existingBucketCount", 0)
                            put("existingBucketTotalSteps", 0)
                            put("filledBucketCount", 0)
                            put("filledBucketTotalSteps", 0)
                            put("bySource", JSObject())
                        })
                        continue
                    }

                    val hcRecordsJson = StepTrackingLogic.serializeHcRecords(hcRecords)

                    // Read existing DB buckets for this window's time range
                    val existingRows = database.getStepsSince(window.startAt)
                        .filter { Instant.parse(it.bucketStart).isBefore(window.endAt) }
                    val existingBuckets = existingRows
                        .associate { Instant.parse(it.bucketStart) to it.steps }

                    val processResult = StepTrackingLogic.processHcRecords(
                        hcRecords, existingBuckets, window.startAt, window.endAt,
                        now = Instant.now()
                    )

                    Log.d(TAG, "BACKFILL_FILL | existingBuckets=${existingBuckets.size}" +
                        " | existingTotal=${existingBuckets.values.sum()}" +
                        " | filledBuckets=${processResult.filledBuckets.size}" +
                        " | filledTotal=${processResult.filledBuckets.values.sum()}")

                    for ((bucketStart, steps) in processResult.filledBuckets) {
                        if (steps > 0) {
                            val bucketEnd = bucketStart.plusSeconds(30)
                            database.insertOrUpdate(bucketStart, bucketEnd, steps, hcRecordsJson)
                        }
                    }

                    // Build audit for this window
                    val hcRecordsAudit = JSArray()
                    for (rec in hcRecords) {
                        hcRecordsAudit.put(JSObject().apply {
                            put("recordId", rec.recordId)
                            put("startTime", rec.startTime.toString())
                            put("endTime", rec.endTime.toString())
                            put("count", rec.count)
                            put("dataOrigin", rec.dataOrigin)
                            put("spanSeconds", Duration.between(rec.startTime, rec.endTime).seconds)
                        })
                    }

                    val bySourceAudit = JSObject()
                    for ((origin, detail) in processResult.perSourceDetail) {
                        bySourceAudit.put(origin, JSObject().apply {
                            put("recordCount", detail.recordCount)
                            put("totalCount", detail.totalHcCount)
                            put("phoneStepsInWindow", detail.phoneStepsInWindow)
                            put("surplusComputed", detail.surplusComputed)
                            put("zeroBucketsAvailable", detail.zeroBucketsAvailable)
                            put("bucketsFilledBySource", detail.bucketsActuallyFilled)
                            put("stepsDistributed", detail.stepsDistributed)
                        })
                    }

                    auditWindows.put(JSObject().apply {
                        put("startAt", window.startAt.toString())
                        put("endAt", window.endAt.toString())
                        put("hcRecords", hcRecordsAudit)
                        put("existingBucketCount", existingBuckets.size)
                        put("existingBucketTotalSteps", existingBuckets.values.sum())
                        put("filledBucketCount", processResult.filledBuckets.size)
                        put("filledBucketTotalSteps", processResult.filledBuckets.values.sum())
                        put("bySource", bySourceAudit)
                    })
                }

                val result = JSObject().apply {
                    put("backedUp", true)
                    put("audit", auditWindows)
                }
                call.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Backfill failed", e)
                val result = JSObject().apply { put("backedUp", false) }
                call.resolve(result)
            }
        }
    }

    @PluginMethod
    fun clearData(call: PluginCall) {
        try {
            val beforeStr = call.getString("before")
            if (beforeStr != null) {
                database.deleteBefore(Instant.parse(beforeStr))
            } else {
                database.deleteStepsSince(null)
            }
            call.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear data", e)
            call.reject("Failed to clear data: ${e.message}")
        }
    }

    @PluginMethod
    fun checkExactAlarmPermission(call: PluginCall) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true // Pre-Android 12: exact alarms always allowed
        }

        val result = JSObject().apply { put("granted", granted) }
        call.resolve(result)
    }

    @PluginMethod
    fun requestExactAlarmPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (alarmManager.canScheduleExactAlarms()) {
                val result = JSObject().apply { put("granted", true) }
                call.resolve(result)
                return
            }

            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                // Resolve immediately — the app can re-check after the user returns
                val result = JSObject().apply { put("granted", false) }
                call.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open exact alarm settings", e)
                call.reject("Failed to open exact alarm settings: ${e.message}")
            }
        } else {
            // Pre-Android 12: exact alarms always allowed
            val result = JSObject().apply { put("granted", true) }
            call.resolve(result)
        }
    }

    @PluginMethod
    fun getLastTickAudit(call: PluginCall) {
        val audit = StepCounterService.lastTickAudit
        if (audit == null) {
            call.resolve(JSObject().apply { put("available", false) })
            return
        }

        val hcRecordsArray = JSArray()
        for (rec in audit.hcRecords) {
            hcRecordsArray.put(JSObject().apply {
                put("recordId", rec.recordId)
                put("startTime", rec.startTime.toString())
                put("endTime", rec.endTime.toString())
                put("count", rec.count)
                put("dataOrigin", rec.dataOrigin)
                put("spanSeconds", Duration.between(rec.startTime, rec.endTime).seconds)
            })
        }

        val hcDeltasObj = JSObject()
        for ((origin, delta) in audit.hcDeltas) {
            hcDeltasObj.put(origin, delta)
        }

        val perOriginObj = JSObject()
        for ((origin, detail) in audit.perOriginDetail) {
            perOriginObj.put(origin, JSObject().apply {
                put("delta", detail.delta)
                put("phoneStepsInRange", detail.phoneStepsInRange)
                put("watchSurplus", detail.watchSurplus)
                put("bucketsFilledByOrigin", detail.bucketsFilledByOrigin)
                put("stepsDistributedByOrigin", detail.stepsDistributedByOrigin)
            })
        }

        val result = JSObject().apply {
            put("available", true)
            put("tickNumber", audit.tickNumber)
            put("timestamp", audit.timestamp)
            put("phoneDelta", audit.phoneDelta)
            put("latestSensorValue", audit.latestSensorValue)
            put("lastSensorBaseline", audit.lastSensorBaseline)
            put("hcRecordsCount", audit.hcRecordsCount)
            put("hcRecords", hcRecordsArray)
            put("hcDeltas", hcDeltasObj)
            put("existingBucketCount", audit.existingBucketCount)
            put("existingBucketTotalSteps", audit.existingBucketTotalSteps)
            put("perOriginDetail", perOriginObj)
            put("filledBucketCount", audit.filledBucketCount)
            put("filledBucketTotalSteps", audit.filledBucketTotalSteps)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun getPluginInfo(call: PluginCall) {
        val result = JSObject().apply {
            put("buildId", PluginBuildInfo.BUILD_ID)
        }
        call.resolve(result)
    }

    // --- Permission helpers ---

    private fun hasAllRequiredPermissions(): Boolean {
        if (!hasActivityRecognitionPermission()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) return false
        return true
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed pre-Android 13
        }
    }
}

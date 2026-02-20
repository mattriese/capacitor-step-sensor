package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
                for (window in windows) {
                    val request = ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(window.startAt, window.endAt)
                    )
                    val response = client.readRecords(request)

                    val hcRecords = response.records.map { sr ->
                        HcStepRecord(
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

                    if (hcRecords.isEmpty()) continue

                    val hcRecordsJson = StepTrackingLogic.serializeHcRecords(hcRecords)

                    // Read existing DB buckets for this window's time range
                    val existingRows = database.getStepsSince(window.startAt)
                        .filter { Instant.parse(it.bucketStart).isBefore(window.endAt) }
                    val existingBuckets = existingRows
                        .associate { Instant.parse(it.bucketStart) to it.steps }

                    val filledBuckets = StepTrackingLogic.processHcRecords(
                        hcRecords, existingBuckets, window.startAt, window.endAt,
                        now = Instant.now()
                    )

                    Log.d(TAG, "BACKFILL_FILL | existingBuckets=${existingBuckets.size}" +
                        " | existingTotal=${existingBuckets.values.sum()}" +
                        " | filledBuckets=${filledBuckets.size}" +
                        " | filledTotal=${filledBuckets.values.sum()}")

                    for ((bucketStart, steps) in filledBuckets) {
                        if (steps > 0) {
                            val bucketEnd = bucketStart.plusSeconds(30)
                            database.insertOrUpdate(bucketStart, bucketEnd, steps, hcRecordsJson)
                        }
                    }
                }

                val result = JSObject().apply { put("backedUp", true) }
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

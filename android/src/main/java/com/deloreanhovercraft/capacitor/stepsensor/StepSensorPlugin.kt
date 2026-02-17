package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
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
            val deleteAfterRead = call.getBoolean("deleteAfterRead", false) ?: false

            val since = sinceStr?.let { Instant.parse(it) }
            val buckets = database.getStepsSince(since)

            val stepsArray = JSArray()
            buckets.forEach { bucket ->
                val obj = JSObject().apply {
                    put("bucketStart", bucket.bucketStart)
                    put("bucketEnd", bucket.bucketEnd)
                    put("steps", bucket.steps)
                }
                stepsArray.put(obj)
            }

            if (deleteAfterRead && buckets.isNotEmpty()) {
                database.deleteStepsSince(since)
            }

            val result = JSObject().apply {
                put("steps", stepsArray)
            }
            call.resolve(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tracked steps", e)
            call.reject("Failed to get tracked steps: ${e.message}")
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

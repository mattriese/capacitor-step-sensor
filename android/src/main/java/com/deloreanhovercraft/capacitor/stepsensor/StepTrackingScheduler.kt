package com.deloreanhovercraft.capacitor.stepsensor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class TrackingWindow(
    val startAt: Instant,
    val endAt: Instant
)

class StepTrackingScheduler(private val context: Context) {

    companion object {
        private const val TAG = "StepTrackingScheduler"
        private const val PREFS_NAME = "step_sensor_prefs"
        private const val KEY_WINDOWS = "scheduled_windows"
        const val ACTION_START_TRACKING = "com.deloreanhovercraft.capacitor.stepsensor.ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.deloreanhovercraft.capacitor.stepsensor.ACTION_STOP_TRACKING"
        private const val REQUEST_CODE_BASE_START = 10000
        private const val REQUEST_CODE_BASE_STOP = 20000
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule tracking windows. Replaces any previously scheduled windows.
     * Merges overlapping windows, skips past windows, and starts immediately
     * if a window's startAt is in the past but endAt is in the future.
     */
    fun scheduleWindows(windows: List<TrackingWindow>) {
        // Cancel all existing alarms
        cancelAllAlarms()

        val now = Instant.now()

        // Filter and process windows
        val validWindows = windows
            .filter { it.endAt.isAfter(now) } // Skip windows that have already ended
            .sortedBy { it.startAt }

        // Merge overlapping windows
        val merged = mergeOverlapping(validWindows)

        // Persist for reboot recovery
        persistWindows(merged)

        // Register alarms for each merged window
        merged.forEachIndexed { index, window ->
            if (window.startAt.isAfter(now)) {
                // Start is in the future — schedule alarm
                scheduleAlarm(ACTION_START_TRACKING, window.startAt, REQUEST_CODE_BASE_START + index)
            } else {
                // Start is in the past but end is in the future — start immediately
                startServiceNow()
            }
            // Always schedule the stop alarm
            scheduleAlarm(ACTION_STOP_TRACKING, window.endAt, REQUEST_CODE_BASE_STOP + index)
        }

        Log.d(TAG, "Scheduled ${merged.size} tracking windows")
    }

    /**
     * Re-register alarms from persisted windows (called after boot).
     */
    fun reregisterFromPersisted() {
        val windows = loadPersistedWindows()
        if (windows.isNotEmpty()) {
            Log.d(TAG, "Re-registering ${windows.size} persisted windows after boot")
            scheduleWindows(windows)
        }
    }

    /**
     * Start the step counter service immediately.
     */
    fun startServiceNow() {
        val intent = Intent(context, StepCounterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Stop the step counter service immediately.
     */
    fun stopServiceNow() {
        val intent = Intent(context, StepCounterService::class.java)
        context.stopService(intent)
    }

    private fun scheduleAlarm(action: String, triggerAt: Instant, requestCode: Int) {
        val intent = Intent(context, StepTrackingAlarmReceiver::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        // Check if exact alarms are available (API 31+ requires SCHEDULE_EXACT_ALARM grant)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission not granted, falling back to inexact alarm for $action")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                pendingIntent
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                pendingIntent
            )
        }
        Log.d(TAG, "Scheduled alarm: action=$action at=$triggerAt requestCode=$requestCode")
    }

    private fun cancelAllAlarms() {
        // Cancel a reasonable range of previously registered alarms
        val persisted = loadPersistedWindows()
        persisted.forEachIndexed { index, _ ->
            cancelAlarm(REQUEST_CODE_BASE_START + index)
            cancelAlarm(REQUEST_CODE_BASE_STOP + index)
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val intent = Intent(context, StepTrackingAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
    }

    private fun mergeOverlapping(windows: List<TrackingWindow>): List<TrackingWindow> {
        if (windows.isEmpty()) return emptyList()

        val result = mutableListOf<TrackingWindow>()
        var current = windows[0]

        for (i in 1 until windows.size) {
            val next = windows[i]
            if (!next.startAt.isAfter(current.endAt)) {
                // Overlapping or adjacent — merge
                current = TrackingWindow(
                    startAt = current.startAt,
                    endAt = if (next.endAt.isAfter(current.endAt)) next.endAt else current.endAt
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    private fun persistWindows(windows: List<TrackingWindow>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        windows.forEach { window ->
            val obj = JSONObject().apply {
                put("startAt", window.startAt.toString())
                put("endAt", window.endAt.toString())
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_WINDOWS, jsonArray.toString()).apply()
    }

    fun loadPersistedWindows(): List<TrackingWindow> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_WINDOWS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                TrackingWindow(
                    startAt = Instant.parse(obj.getString("startAt")),
                    endAt = Instant.parse(obj.getString("endAt"))
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse persisted windows", e)
            emptyList()
        }
    }
}

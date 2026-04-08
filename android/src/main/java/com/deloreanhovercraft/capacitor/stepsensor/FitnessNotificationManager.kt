package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.absoluteValue

class FitnessNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "FitnessNotifManager"
        private const val PREFS_NAME = "step_sensor_fitness_notifications"
        private const val KEY_GENERATED_AT = "generated_at"
        private const val KEY_CONFIGS = "configs"
        private const val KEY_SCHEDULED_REMINDERS = "scheduled_reminders"
        private const val REQUEST_CODE_BASE_REMINDER = 30000
        private const val NOTIFICATION_CHANNEL_ID = "fitness_interval_reminders"
        private const val NOTIFICATION_CHANNEL_NAME = "Fitness Interval Reminders"
        const val ACTION_SHOW_FITNESS_INTERVAL_REMINDER =
            "com.deloreanhovercraft.capacitor.stepsensor.ACTION_SHOW_FITNESS_INTERVAL_REMINDER"
        const val EXTRA_COMMITMENT_ID = "commitmentId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_DUE_AT = "dueAt"
    }

    data class PersistedScheduledReminder(
        val commitmentId: String,
        val title: String,
        val body: String,
        val scheduleAt: Instant,
        val dueAt: Instant
    )

    fun configureFitnessNotifications(
        generatedAt: String,
        commitments: List<StepIntervalNotificationConfig>,
        database: StepSensorDatabase,
        now: Instant = Instant.now()
    ) {
        persistConfigs(generatedAt, commitments)
        reconcileNotifications(now, database)
    }

    fun clearFitnessNotifications() {
        persistConfigs(null, emptyList())
        val existing = loadScheduledReminders()
        existing.values.forEach { cancelReminder(it.commitmentId) }
        persistScheduledReminders(emptyList())
    }

    fun reconcileNotifications(
        now: Instant,
        database: StepSensorDatabase
    ) {
        val configs = loadConfigs()
        val existing = loadScheduledReminders().toMutableMap()
        val desiredReminderStates = mutableListOf<PersistedScheduledReminder>()
        val desiredIds = mutableSetOf<String>()

        for (config in configs) {
            val buckets = database.getStepsSince(config.timePeriodStartAt)
            val reminder = StepIntervalNotificationLogic.computeReminder(config, buckets, now)
            if (reminder == null) {
                existing[config.commitmentId]?.let { cancelReminder(config.commitmentId) }
                continue
            }

            desiredIds.add(config.commitmentId)
            val desired = PersistedScheduledReminder(
                commitmentId = reminder.commitmentId,
                title = reminder.title,
                body = reminder.body,
                scheduleAt = reminder.scheduleAt,
                dueAt = reminder.dueAt
            )
            desiredReminderStates.add(desired)

            val current = existing[config.commitmentId]
            if (current == null || current != desired) {
                scheduleReminder(desired)
            }
        }

        for ((commitmentId, _) in existing) {
            if (!desiredIds.contains(commitmentId)) {
                cancelReminder(commitmentId)
            }
        }

        persistScheduledReminders(desiredReminderStates)
    }

    fun reregisterScheduledReminderAlarms() {
        loadScheduledReminders().values.forEach { scheduleReminder(it) }
    }

    fun showReminderNotification(
        commitmentId: String,
        title: String,
        body: String,
        dueAt: String?
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing, skipping reminder delivery")
            removeScheduledReminder(commitmentId)
            return
        }

        createReminderChannel()

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                requestCodeForCommitment(commitmentId),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .apply {
                    if (dueAt != null) {
                        setSubText("Due by $dueAt")
                    }
                }
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationIdForCommitment(commitmentId), notification)
        removeScheduledReminder(commitmentId)
    }

    fun getDebugState(): JSObject {
        val generatedAt = getPrefs().getString(KEY_GENERATED_AT, null)
        val configsArray = JSArray()
        loadConfigs().forEach { config ->
            configsArray.put(JSObject().apply {
                put("commitmentId", config.commitmentId)
                put("taskName", config.taskName)
                put("maxOrMin", config.maxOrMin)
                put("intervalInMinutes", config.intervalInMinutes)
                put("completionMetric", config.completionMetric)
                put("completionMetricType", config.completionMetricType)
                put("timePeriodStartAt", config.timePeriodStartAt.toString())
                put("timePeriodEndAt", config.timePeriodEndAt.toString())
                put("reminderLeadMinutes", config.reminderLeadMinutes)
                put("staleAfterMinutes", config.staleAfterMinutes)
            })
        }

        val scheduledArray = JSArray()
        loadScheduledReminders().values.forEach { reminder ->
            scheduledArray.put(JSObject().apply {
                put("commitmentId", reminder.commitmentId)
                put("title", reminder.title)
                put("body", reminder.body)
                put("scheduleAt", reminder.scheduleAt.toString())
                put("dueAt", reminder.dueAt.toString())
            })
        }

        return JSObject().apply {
            put("generatedAt", generatedAt)
            put("commitments", configsArray)
            put("scheduledReminders", scheduledArray)
        }
    }

    private fun persistConfigs(
        generatedAt: String?,
        commitments: List<StepIntervalNotificationConfig>
    ) {
        val configsJson = JSONArray()
        commitments.forEach { config ->
            configsJson.put(JSONObject().apply {
                put("commitmentId", config.commitmentId)
                put("taskName", config.taskName)
                put("maxOrMin", config.maxOrMin)
                put("intervalInMinutes", config.intervalInMinutes)
                put("completionMetric", config.completionMetric)
                put("completionMetricType", config.completionMetricType)
                put("timePeriodStartAt", config.timePeriodStartAt.toString())
                put("timePeriodEndAt", config.timePeriodEndAt.toString())
                put("reminderLeadMinutes", config.reminderLeadMinutes)
                put("staleAfterMinutes", config.staleAfterMinutes)
            })
        }

        getPrefs().edit().apply {
            putString(KEY_GENERATED_AT, generatedAt)
            putString(KEY_CONFIGS, configsJson.toString())
            apply()
        }
    }

    private fun loadConfigs(): List<StepIntervalNotificationConfig> {
        val raw = getPrefs().getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                StepIntervalNotificationConfig(
                    commitmentId = obj.getString("commitmentId"),
                    taskName = obj.getString("taskName"),
                    maxOrMin = obj.getString("maxOrMin"),
                    intervalInMinutes = obj.getInt("intervalInMinutes"),
                    completionMetric = obj.getInt("completionMetric"),
                    completionMetricType = obj.getString("completionMetricType"),
                    timePeriodStartAt = Instant.parse(obj.getString("timePeriodStartAt")),
                    timePeriodEndAt = Instant.parse(obj.getString("timePeriodEndAt")),
                    reminderLeadMinutes = obj.getInt("reminderLeadMinutes"),
                    staleAfterMinutes = obj.getInt("staleAfterMinutes")
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse persisted fitness notification configs", error)
            emptyList()
        }
    }

    private fun persistScheduledReminders(reminders: List<PersistedScheduledReminder>) {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(JSONObject().apply {
                put("commitmentId", reminder.commitmentId)
                put("title", reminder.title)
                put("body", reminder.body)
                put("scheduleAt", reminder.scheduleAt.toString())
                put("dueAt", reminder.dueAt.toString())
            })
        }
        getPrefs().edit().putString(KEY_SCHEDULED_REMINDERS, array.toString()).apply()
    }

    private fun loadScheduledReminders(): Map<String, PersistedScheduledReminder> {
        val raw = getPrefs().getString(KEY_SCHEDULED_REMINDERS, null) ?: return emptyMap()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).associate { index ->
                val obj = array.getJSONObject(index)
                val reminder = PersistedScheduledReminder(
                    commitmentId = obj.getString("commitmentId"),
                    title = obj.getString("title"),
                    body = obj.getString("body"),
                    scheduleAt = Instant.parse(obj.getString("scheduleAt")),
                    dueAt = Instant.parse(obj.getString("dueAt"))
                )
                reminder.commitmentId to reminder
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to parse persisted scheduled reminders", error)
            emptyMap()
        }
    }

    private fun scheduleReminder(reminder: PersistedScheduledReminder) {
        val intent = Intent(context, StepTrackingAlarmReceiver::class.java).apply {
            action = ACTION_SHOW_FITNESS_INTERVAL_REMINDER
            putExtra(EXTRA_COMMITMENT_ID, reminder.commitmentId)
            putExtra(EXTRA_TITLE, reminder.title)
            putExtra(EXTRA_BODY, reminder.body)
            putExtra(EXTRA_DUE_AT, reminder.dueAt.toString())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeForCommitment(reminder.commitmentId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.scheduleAt.toEpochMilli(),
                pendingIntent
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.scheduleAt.toEpochMilli(),
                pendingIntent
            )
        }
    }

    private fun cancelReminder(commitmentId: String) {
        val intent = Intent(context, StepTrackingAlarmReceiver::class.java).apply {
            action = ACTION_SHOW_FITNESS_INTERVAL_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeForCommitment(commitmentId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationIdForCommitment(commitmentId))
        removeScheduledReminder(commitmentId)
    }

    private fun removeScheduledReminder(commitmentId: String) {
        val updated = loadScheduledReminders()
            .filterKeys { it != commitmentId }
            .values
            .toList()
        persistScheduledReminders(updated)
    }

    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Interval reminders for fitness commitments"
                setShowBadge(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getPrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun requestCodeForCommitment(commitmentId: String): Int {
        val normalizedHash = commitmentId.hashCode().absoluteValue % 1_000_000
        return REQUEST_CODE_BASE_REMINDER + normalizedHash
    }

    private fun notificationIdForCommitment(commitmentId: String): Int {
        return requestCodeForCommitment(commitmentId) + 1_000_000
    }
}

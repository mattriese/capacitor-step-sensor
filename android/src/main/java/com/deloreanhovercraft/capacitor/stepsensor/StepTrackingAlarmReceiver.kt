package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant

class StepTrackingAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StepTrackingAlarmRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val fitnessNotificationManager = FitnessNotificationManager(context)
        when (intent.action) {
            StepTrackingScheduler.ACTION_START_TRACKING -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Alarm fired but ACTIVITY_RECOGNITION not granted — skipping service start")
                    return
                }
                Log.d(TAG, "Alarm fired: starting step tracking service")
                val serviceIntent = Intent(context, StepCounterService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                fitnessNotificationManager.reconcileNotifications(
                    Instant.now(),
                    StepSensorDatabase.getInstance(context)
                )
            }
            StepTrackingScheduler.ACTION_STOP_TRACKING -> {
                Log.d(TAG, "Alarm fired: stopping step tracking service")
                fitnessNotificationManager.reconcileNotifications(
                    Instant.now(),
                    StepSensorDatabase.getInstance(context)
                )
                val serviceIntent = Intent(context, StepCounterService::class.java)
                context.stopService(serviceIntent)
            }
            FitnessNotificationManager.ACTION_SHOW_FITNESS_INTERVAL_REMINDER -> {
                val commitmentId = intent.getStringExtra(FitnessNotificationManager.EXTRA_COMMITMENT_ID)
                val title = intent.getStringExtra(FitnessNotificationManager.EXTRA_TITLE)
                val body = intent.getStringExtra(FitnessNotificationManager.EXTRA_BODY)
                val dueAt = intent.getStringExtra(FitnessNotificationManager.EXTRA_DUE_AT)

                if (commitmentId == null || title == null || body == null) {
                    Log.w(TAG, "Reminder alarm fired with incomplete extras")
                    return
                }

                Log.d(TAG, "Alarm fired: showing fitness reminder notification")
                fitnessNotificationManager.showReminderNotification(
                    commitmentId = commitmentId,
                    title = title,
                    body = body,
                    dueAt = dueAt
                )
            }
            else -> {
                Log.w(TAG, "Received unknown action: ${intent.action}")
            }
        }
    }
}

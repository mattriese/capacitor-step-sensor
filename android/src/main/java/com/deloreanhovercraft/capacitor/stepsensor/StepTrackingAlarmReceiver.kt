package com.deloreanhovercraft.capacitor.stepsensor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class StepTrackingAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StepTrackingAlarmRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
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
            }
            StepTrackingScheduler.ACTION_STOP_TRACKING -> {
                Log.d(TAG, "Alarm fired: stopping step tracking service")
                val serviceIntent = Intent(context, StepCounterService::class.java)
                context.stopService(serviceIntent)
            }
            else -> {
                Log.w(TAG, "Received unknown action: ${intent.action}")
            }
        }
    }
}

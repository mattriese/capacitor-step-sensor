package com.deloreanhovercraft.capacitor.stepsensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class StepTrackingAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StepTrackingAlarmRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            StepTrackingScheduler.ACTION_START_TRACKING -> {
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

package com.deloreanhovercraft.capacitor.stepsensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed — re-registering step tracking alarms")
            val scheduler = StepTrackingScheduler(context)
            scheduler.reregisterFromPersisted()
        }
    }
}

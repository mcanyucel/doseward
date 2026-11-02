package com.doseward.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lateSeconds = (System.currentTimeMillis() - SpikeStore.armedAt(context)) / 1000
        val mode = SpikeStore.armedMode(context)?.label ?: "unknown"
        SpikeLog.append(context, "FIRED ($mode), ${lateSeconds}s late")
    }
}

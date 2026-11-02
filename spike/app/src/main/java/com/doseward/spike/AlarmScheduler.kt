package com.doseward.spike

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun arm(context: Context, triggerAt: Long, chosen: AlarmMode) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        // Same rule as the design: no exact-alarm permission means Approximate.
        val mode = if (canExact(context)) chosen else AlarmMode.APPROXIMATE
        val operation = alarmPendingIntent(context)

        when (mode) {
            AlarmMode.ALARM_CLOCK -> {
                // What the system opens when the user taps the alarm icon in the status bar.
                val showIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
            }
            AlarmMode.EXACT ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            AlarmMode.APPROXIMATE ->
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }

        SpikeStore.saveArmed(context, triggerAt, mode)
        SpikeLog.append(context, "armed ${mode.label} for ${SpikeLog.time(triggerAt)}")
    }

    // Always the same PendingIntent: arming again replaces the old alarm, whatever its type.
    private fun alarmPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

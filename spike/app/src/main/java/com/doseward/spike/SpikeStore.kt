package com.doseward.spike

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SpikeStore {
    private const val KEY_AT = "armed_at"
    private const val KEY_MODE = "armed_mode"

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("spike", Context.MODE_PRIVATE)

    fun saveArmed(context: Context, triggerAt: Long, mode: AlarmMode) {
        prefs(context).edit {
            putLong(KEY_AT, triggerAt)
            putString(KEY_MODE, mode.name)
        }
    }

    fun armedAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    fun armedMode(context: Context): AlarmMode? =
        prefs(context).getString(KEY_MODE, null)?.let(AlarmMode::valueOf)
}

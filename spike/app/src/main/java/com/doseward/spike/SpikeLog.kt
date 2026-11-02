package com.doseward.spike

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SpikeLog {
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    // Device-protected storage: still readable after a reboot, before the first unlock (stage 4).
    private fun file(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, "spike-log.txt")

    fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(format)

    @Synchronized
    fun append(context: Context, message: String) {
        file(context).appendText("${time(System.currentTimeMillis())}  $message\n")
    }

    fun read(context: Context): String {
        val f = file(context)
        return if (f.exists()) f.readText() else "(empty)"
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}

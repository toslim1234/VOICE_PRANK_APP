package com.voiceprank.app.inject

import android.util.Log
import java.io.File

/**
 * Root detection.
 *
 * The original app has a `RootTools` helper that checks for `su` before it
 * offers the "Root" injection mode. We do the same.
 */
object RootTools {

    private const val TAG = "VoicePrankRoot"

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/magisk/.core/bin/su",
        "/debug_ramdisk/su"
    )

    @Volatile
    private var cached: Boolean? = null

    fun hasRoot(): Boolean {
        cached?.let { return it }
        val found = detect()
        cached = found
        return found
    }

    private fun detect(): Boolean {
        // 1. a su binary on disk
        for (p in SU_PATHS) {
            if (File(p).exists()) return true
        }
        // 2. PATH lookup
        val pathDirs = (System.getenv("PATH") ?: "").split(":")
        for (d in pathDirs) {
            if (d.isBlank()) continue
            val f = File(d, "su")
            if (f.exists() && f.canExecute()) return true
        }
        // 3. actually run it
        return try {
            val p = Runtime.getRuntime().exec("su")
            p.outputStream.close()
            val exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (exited && p.exitValue() == 0) {
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.d(TAG, "su not usable: ${t.message}")
            false
        }
    }

    fun reset() {
        cached = null
    }
}

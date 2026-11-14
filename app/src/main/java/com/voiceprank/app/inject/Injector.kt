package com.voiceprank.app.inject

import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Audio injector, started as its own process through `app_process` (shell or root uid):
 *
 *   CLASSPATH=<apk> app_process /system/bin com.voiceprank.app.inject.Injector <pcm> <rate> <log> [loop]
 *
 * Plays <pcm> (16-bit mono) into the microphone path of other apps once, or repeatedly
 * with `loop`. Android 8-12: capture-preset loop-back mix; Android 13+: mix role INJECTOR. Writes progress markers to <log>;
 * the controller waits for the line `ready`.
 */
object Injector {

    private const val TAG = "VoicePrankInjector"

    @Volatile
    private var logPath: String = ""

    @Volatile
    private var running = true

    private fun log(line: String) {
        Log.i(TAG, line)
        val p = logPath
        if (p.isBlank()) return
        try {
            File(p).appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: Injector <pcm-file> <sample-rate> <log-file> [loop]")
            return
        }
        logPath = args[2]
        val loop = args.size > 3 && args[3] == "loop"
        val rate = args[1].toIntOrNull() ?: 44100
        var code = 0
        try {
            if (Build.VERSION.SDK_INT < 26) {
                log("ERROR: injection needs Android 8 or newer")
                System.exit(2)
            }
            val pcm = File(args[0]).readBytes()
            if (pcm.isEmpty()) {
                log("ERROR: empty audio")
                System.exit(2)
            }
            code = inject(pcm, rate, loop)
        } catch (t: Throwable) {
            log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            code = 1
        }
        System.exit(code)
    }

    private fun inject(pcm: ByteArray, rate: Int, loop: Boolean): Int {
        try {
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
        }
        val context = systemContext()
        if (context == null) {
            log("ERROR: no system context")
            return 1
        }
        log("context ok")

        val handle = AudioPolicyFactory.create(context, rate)
        if (handle == null) {
            log("ERROR: audio policy failed: ${AudioPolicyFactory.lastError}")
            return 1
        }
        log("mix ok")

        try {
            val track = handle.track
            track.play()
            log("ready")

            // Stop when killed (pkill) so the policy is unregistered cleanly.
            Runtime.getRuntime().addShutdownHook(Thread {
                running = false
                AudioPolicyFactory.release(handle)
            })

            do {
                var off = 0
                while (running && off < pcm.size) {
                    val n = track.write(pcm, off, minOf(4096, pcm.size - off))
                    if (n < 0) {
                        log("ERROR: write failed ($n)")
                        return 1
                    }
                    off += n
                }
            } while (loop && running)

            // let the buffer drain before tearing the mix down
            Thread.sleep(500)
            log("done")
            return 0
        } finally {
            AudioPolicyFactory.release(handle)
        }
    }

    private fun systemContext(): android.content.Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredMethod("systemMain").apply { isAccessible = true }.invoke(null)
            at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(thread) as? android.content.Context
        } catch (t: Throwable) {
            Log.w(TAG, "systemContext failed", t)
            null
        }
    }
}

package com.voiceprank.app.inject

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell execution with elevated privileges.
 *
 * Two privilege sources, exactly like the original:
 *
 *  - **Root**   -> `su -c <cmd>`
 *  - **Shizuku**-> a shell-uid process, obtained through the Shizuku service
 *
 * The original exposes both through a `ShellRunner` with a `Mode` enum and a
 * `RootTools` helper for root detection. We keep that shape.
 */
object ShellRunner {

    private const val TAG = "VoicePrankShell"

    enum class Mode { ROOT, SHIZUKU, NONE }

    data class Result(val exitCode: Int, val out: String, val err: String) {
        val ok: Boolean get() = exitCode == 0
    }

    /** Run a command with the given mode and return its output. */
    fun run(mode: Mode, command: String, timeoutSec: Int = 60): Result {
        return when (mode) {
            Mode.ROOT -> runRoot(command, timeoutSec)
            Mode.SHIZUKU -> runShizuku(command, timeoutSec)
            Mode.NONE -> Result(-1, "", "no privilege")
        }
    }

    // ---------------------------------------------------------------- root

    fun hasRoot(): Boolean = RootTools.hasRoot()

    private fun runRoot(command: String, timeoutSec: Int): Result {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su"))
            val writer = process.outputStream.bufferedWriter()
            writer.write(command)
            writer.write("\n")
            writer.write("exit\n")
            writer.flush()
            val out = readAll(process.inputStream)
            val err = readAll(process.errorStream)
            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                Result(-2, out, err.ifBlank { "timeout" })
            } else {
                Result(process.exitValue(), out, err)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "root exec failed", t)
            Result(-1, "", t.message ?: "root_exec_failed")
        }
    }

    // ------------------------------------------------------------- shizuku

    private fun runShizuku(command: String, timeoutSec: Int): Result {
        return try {
            val b = ShizukuBridge.binder
            if (b == null) {
                return Result(-1, "", "shizuku_not_ready")
            }
            val out = b.run(command, timeoutSec * 1000)
            if (out.startsWith("[error]")) Result(-1, "", out) else Result(0, out, "")
        } catch (t: Throwable) {
            Log.e(TAG, "shizuku exec failed", t)
            Result(-1, "", t.message ?: "shizuku_exec_failed")
        }
    }

    // ---------------------------------------------------------------- utils

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Command that starts the injector as a detached process:
     *
     *   CLASSPATH=<apk> nohup app_process /system/bin <Injector> <pcm> <rate> <log> [loop] &
     */
    fun injectorCommand(
        apkPath: String, pcmFile: String, sampleRate: Int, logFile: String, loop: Boolean = false
    ): String {
        fun q(v: String) = "'" + v.replace("'", "'\\''") + "'"
        return "chmod 666 ${q(logFile)} 2>/dev/null; " +
            "CLASSPATH=${q(apkPath)} nohup app_process /system/bin " +
            "$InjectorClassName ${q(pcmFile)} $sampleRate ${q(logFile)}" +
            (if (loop) " loop" else "") + " >/dev/null 2>&1 &"
    }

    /** Stop a running injector. */
    fun killInjectorCommand(): String = "pkill -f $InjectorClassName"

    const val InjectorClassName = "com.voiceprank.app.inject.Injector"

    /** Path of our own APK, used as CLASSPATH for `app_process`. */
    fun apkPath(context: Context): String {
        return try {
            val path = context.applicationInfo.sourceDir
            // app_process needs to read the dex; make sure it is world readable
            try {
                val f = java.io.File(path)
                f.setReadable(true, false)
            } catch (_: Throwable) {
            }
            path
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Shared directory both the app and the shell/root injector can reach.
     * (/data/data/<pkg> is private to the app, so the shell uid cannot read it.)
     */
    fun sharedDir(context: Context): java.io.File =
        (context.getExternalFilesDir("inject") ?: java.io.File(context.cacheDir, "inject")).apply {
            mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }

    fun logPath(context: Context): String =
        java.io.File(sharedDir(context), "inject.log").absolutePath
}

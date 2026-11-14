package com.voiceprank.app.inject

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Controls the injector process - the original app's `InjectCtl`.
 *
 * The original writes `inject.log`, starts the injector in the background,
 * greps the log for `ready`, and stops it with
 * `pkill -f com.voiceprank.app.inject.Injector`.
 */
class InjectCtl(private val context: Context) {

    companion object {
        private const val TAG = "VoicePrankInjectCtl"
    }

    enum class State { STOPPED, RUNNING, FAILED }

    data class Status(
        val state: State,
        val mode: ShellRunner.Mode,
        val detail: String = "",
        val log: String = ""
    )

    private val logFile: File
        get() = File(ShellRunner.logPath(context))

    /** Available privilege modes, in the order the original presents them. */
    fun availableModes(): List<ShellRunner.Mode> {
        val list = mutableListOf<ShellRunner.Mode>()
        if (RootTools.hasRoot()) list.add(ShellRunner.Mode.ROOT)
        if (ShizukuBridge.isReady()) list.add(ShellRunner.Mode.SHIZUKU)
        return list
    }

    /**
     * Start injection of a PCM file.
     * Returns the resulting status.
     */
    fun start(pcmFile: File, sampleRate: Int, mode: ShellRunner.Mode): Status {
        if (!pcmFile.exists()) {
            return Status(State.FAILED, mode, "audio file missing")
        }
        val apk = ShellRunner.apkPath(context)
        if (apk.isBlank()) {
            return Status(State.FAILED, mode, "cannot resolve apk path")
        }
        if (mode == ShellRunner.Mode.SHIZUKU && !ShizukuBridge.bindBlocking(context)) {
            return Status(State.FAILED, mode, "Shizuku service did not connect")
        }

        // The shell/root process cannot read the app's private dir: hand the audio over
        // through the shared external-files dir.
        val shared = File(ShellRunner.sharedDir(context), "inject.pcm")
        try {
            pcmFile.copyTo(shared, overwrite = true)
            shared.setReadable(true, false)
            logFile.writeText("")
            logFile.setReadable(true, false)
            logFile.setWritable(true, false)
        } catch (t: Throwable) {
            return Status(State.FAILED, mode, "cannot prepare files: ${t.message}")
        }

        val command = ShellRunner.injectorCommand(
            apk, shared.absolutePath, sampleRate, logFile.absolutePath
        )
        Log.i(TAG, "start: $command")

        val result = ShellRunner.run(mode, command, timeoutSec = 20)
        if (!result.ok) {
            return Status(State.FAILED, mode, result.err.ifBlank { "failed to start" })
        }

        // Give the injector a moment to register the mix and log "ready".
        val deadline = System.currentTimeMillis() + 6_000
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            val l = readLog()
            if (l.contains("ready")) {
                ready = true
                break
            }
            if (l.contains("ERROR")) break
        }
        return if (ready) {
            Status(State.RUNNING, mode, "ready")
        } else {
            Status(State.FAILED, mode, readLog().takeLast(300).ifBlank { "no log" })
        }
    }

    /** Stop a running injector. */
    fun stop(mode: ShellRunner.Mode): Status {
        val result = ShellRunner.run(mode, ShellRunner.killInjectorCommand(), timeoutSec = 15)
        return if (result.ok) {
            Status(State.STOPPED, mode, "stopped")
        } else {
            Status(State.STOPPED, mode, result.err)
        }
    }

    /** Current status, derived from the log file. */
    fun status(mode: ShellRunner.Mode): Status {
        val log = readLog()
        return when {
            log.contains("ERROR") -> Status(State.FAILED, mode, log.takeLast(300), log)
            log.contains("done") -> Status(State.STOPPED, mode, "finished", log)
            log.contains("ready") -> Status(State.RUNNING, mode, "ready", log)
            log.isNotBlank() -> Status(State.FAILED, mode, log.takeLast(300), log)
            else -> Status(State.STOPPED, mode, "", log)
        }
    }

    private fun readLog(): String {
        return try {
            if (!logFile.exists()) return ""
            logFile.readText()
        } catch (t: Throwable) {
            ""
        }
    }
}

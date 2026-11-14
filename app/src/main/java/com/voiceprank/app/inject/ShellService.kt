package com.voiceprank.app.inject

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService (runs as the shell uid, in a process Shizuku starts).
 *
 * It is NOT an Android Service and is NOT declared in the manifest, so no other
 * app can bind to it. Only the Shizuku server instantiates it, and only apps the
 * user approved in Shizuku receive its Binder.
 */
class ShellService() : IShell.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = android.os.Process.myUid()

    override fun run(command: String, timeoutMs: Int): String {
        return try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                "[error] timeout"
            } else {
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.exitValue() == 0) out else "[error] exit=${p.exitValue()} $out"
            }
        } catch (t: Throwable) {
            Log.e("VoicePrankShellSvc", "run failed", t)
            "[error] ${t.message ?: t.javaClass.simpleName}"
        }
    }
}

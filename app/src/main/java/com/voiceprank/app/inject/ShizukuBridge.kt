package com.voiceprank.app.inject

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku bridge.
 *
 * Shizuku gives us shell-level privilege without root, which is enough to run
 * `app_process` and register the loop-back audio mix.
 *
 * The modern Shizuku API (13.x) does not expose `newProcess`, so the app uses
 * the **UserService** pattern: `ShellService` is started by Shizuku in a process owned by the shell user. The app binds to it
 * and calls methods on the Binder, so the work happens with shell privileges.
 *
 * The original app shows a setup guide ("Install the Shizuku app", "pair using
 * Pairing, then tap 'Start via Wireless debugging'", "Check Shizuku / permission")
 * and a `ShizukuProvider` in its manifest - we reproduce both.
 */
object ShizukuBridge {

    private const val TAG = "VoicePrankShizuku"

    @Volatile
    var binder: IShell? = null
        private set

    @Volatile
    private var binding = false

    fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    fun needsPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /** Request the Shizuku permission; the user must grant it once. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        try {
            if (isReady()) {
                onResult(true)
                return
            }
            Shizuku.addRequestPermissionResultListener(
                Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                    onResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
            )
            Shizuku.requestPermission(13)
        } catch (t: Throwable) {
            Log.e(TAG, "requestPermission failed", t)
            onResult(false)
        }
    }

    /**
     * Bind the shell-uid user service.
     * Returns true once the binder is available.
     */
    fun bind(context: Context, onReady: (Boolean) -> Unit): Boolean {
        if (binder != null) {
            onReady(true)
            return true
        }
        if (!isReady()) {
            onReady(false)
            return false
        }
        if (binding) return true
        binding = true
        return try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder = IShell.Stub.asInterface(service)
                    binding = false
                    Log.i(TAG, "shell service bound, uid=${binder?.uid()}")
                    onReady(binder != null)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    binder = null
                    binding = false
                    onReady(false)
                }
            }
            Shizuku.bindUserService(args, conn)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            binding = false
            onReady(false)
            false
        }
    }

    fun unbind() {
        binder = null
    }

    /** Bind and wait (max [timeoutMs]) until the shell service is available. */
    fun bindBlocking(context: Context, timeoutMs: Long = 6_000): Boolean {
        if (binder != null) return true
        val latch = java.util.concurrent.CountDownLatch(1)
        bind(context) { ok -> if (ok) latch.countDown() }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return binder != null
    }

    /**
     * Run a command with shell privileges.
     * Returns the combined output, or an error marker when Shizuku is not
     * available.
     */
    fun run(command: String, timeoutMs: Int = 60_000): String {
        val b = binder
            ?: return "[error] shizuku_not_ready"
        return try { b.run(command, timeoutMs) } catch (t: Throwable) { "[error] ${t.message}" }
    }

    /**
     * Human readable status used by the UI, mirroring the original's
     * "Shizuku is running" / "Remember: after a phone restart you must Start
     * Shizuku again." guidance.
     */
    fun statusText(): String = when {
        binder != null -> "Shizuku is running (uid ${binder?.uid()})"
        isReady() -> "Shizuku is running"
        needsPermission() -> "Shizuku permission required"
        else -> "Shizuku is not running"
    }
}

package com.voiceprank.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Device + install identity.
 *
 * The original app computes:
 *  - a device id derived from `Settings.Secure.ANDROID_ID`
 *  - a signature hash = SHA-256 of the APK signing certificate
 * and sends both to its backend in `X-Legacy-Device` / `X-Legacy-App-Sig`.
 *
 * We keep both, because a self-hosted backend needs the same fields.
 */
object Identity {

    /** SHA-256 of the signing certificate, hex, lowercase. */
    fun signatureHash(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return ""
                if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            if (signatures.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            signatures.forEach { digest.update(it.toByteArray()) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            ""
        }
    }

    /** Stable-ish per-install device id. */
    fun deviceId(context: Context): String {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (t: Throwable) {
            null
        } ?: Build.SERIAL ?: "unknown"
        return sha256Hex(raw)
    }

    fun versionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (t: Throwable) {
            0
        }
    }

    fun versionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    fun sha256Hex(text: String): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256")
        return d.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

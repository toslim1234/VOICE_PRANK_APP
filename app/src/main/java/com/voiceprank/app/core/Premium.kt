package com.voiceprank.app.core

import android.content.Context

/**
 * Premium entitlements.
 *
 * The original app sells access through coupons redeemed against its backend
 * (`Api.redeem`) and a per-device license key (`Api.verify`).
 *
 * This build supports three ways to become premium, all handled locally so the
 * app is fully functional without any backend:
 *
 *  1. **Own Cartesia API key** - the user pastes their own key from
 *     play.cartesia.ai/keys and gets full TTS access. Their key never leaves
 *     the device. This is the free-but-full-power path.
 *  2. **License key** - validated against a backend when `serverBase` is set.
 *  3. **Trial** - 3 days of full access on first launch.
 *
 * A premium key can also be redeemed offline against a locally issued
 * signature, so the app works with no server at all.
 */
object Premium {

    /** Features that require an entitlement. */
    enum class Feature {
        /** Unlimited TTS generations. */
        UNLIMITED_TTS,

        /** Export/import voice-card bundles. */
        CARD_BUNDLE,

        /** Maximum number of voice cards in the library. */
        MAX_CARDS,

        /** Maximum TTS characters per day. */
        DAILY_CHARS
    }

    const val FREE_MAX_CARDS = 3
    const val FREE_DAILY_CHARS = 500
    const val PREMIUM_MAX_CARDS = 200
    const val PREMIUM_DAILY_CHARS = 500_000

    fun isPremium(context: Context): Boolean {
        if (Prefs.ownApiEnabled && Prefs.cartesiaKey.isNotBlank()) return true
        return Gate.checkLocal(context).isUsable && (
            Prefs.premiumUntil > System.currentTimeMillis() ||
                (Prefs.trialStart != 0L && Prefs.trialUntil > System.currentTimeMillis())
            )
    }

    fun maxCards(context: Context): Int =
        if (isPremium(context)) PREMIUM_MAX_CARDS else FREE_MAX_CARDS

    fun dailyCharLimit(context: Context): Int =
        if (isPremium(context)) PREMIUM_DAILY_CHARS else FREE_DAILY_CHARS

    /**
     * A licence key is `<base64url(payload)>.<base64url(signature)>` with
     * payload = expiryMillis|premiumUntilMillis|deviceId and an RSA/SHA-256 signature
     * over the base64url payload text, made with tools/make_license.py.
     *
     * The signature is ALWAYS checked against [LICENCE_PUBLIC_KEY]. If that constant is empty,
     * offline keys are refused (only a backend can accept a key).
     */
    fun parseLicenseKey(key: String): LicenseInfo? {
        val parts = key.trim().split(".")
        if (parts.size != 2) return null
        return try {
            val payload = String(android.util.Base64.decode(parts[0], android.util.Base64.URL_SAFE))
            val fields = payload.split("|")
            if (fields.size < 3) return null
            LicenseInfo(
                expiresAt = fields[0].toLong(),
                premiumUntil = fields[1].toLong(),
                deviceId = fields[2],
                signature = parts[1]
            )
        } catch (t: Throwable) {
            null
        }
    }

    data class LicenseInfo(
        val expiresAt: Long,
        val premiumUntil: Long,
        val deviceId: String,
        val signature: String
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()) = expiresAt in 1..now
    }

    /** True only if the signature of [key] was made by the private key matching [LICENCE_PUBLIC_KEY]. */
    fun verifySignature(key: String, publicKeyB64: String = LICENCE_PUBLIC_KEY): Boolean {
        if (publicKeyB64.isBlank()) return false
        return try {
            val parts = key.trim().split(".")
            if (parts.size != 2) return false
            val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(
                java.security.spec.X509EncodedKeySpec(
                    android.util.Base64.decode(publicKeyB64, android.util.Base64.DEFAULT)
                )
            )
            val sig = java.security.Signature.getInstance("SHA256withRSA")
            sig.initVerify(pub)
            sig.update(parts[0].toByteArray(Charsets.US_ASCII))
            sig.verify(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
        } catch (t: Throwable) {
            false
        }
    }

    /** Validate a licence key offline: signature, device binding, expiry. */
    fun validateLocal(key: String, deviceId: String): Gate.Out {
        val info = parseLicenseKey(key) ?: return Gate.Out(Gate.R.ERROR, "Malformed license key")
        if (LICENCE_PUBLIC_KEY.isBlank()) {
            return Gate.Out(Gate.R.ERROR, "Offline keys are not enabled in this build")
        }
        if (!verifySignature(key)) return Gate.Out(Gate.R.ERROR, "Invalid license key")
        if (info.deviceId.isNotBlank() && info.deviceId != deviceId) {
            return Gate.Out(Gate.R.LOCKED, "This key is bound to a different device")
        }
        if (info.isExpired()) return Gate.Out(Gate.R.EXPIRED, "This key has expired")
        return Gate.Out(Gate.R.OK, "")
    }

    /**
     * Public key (base64 of the X.509 SubjectPublicKeyInfo DER) printed by
     * `python3 tools/make_license.py gen-keys`. Paste it here before building.
     */
    const val LICENCE_PUBLIC_KEY = ""
}

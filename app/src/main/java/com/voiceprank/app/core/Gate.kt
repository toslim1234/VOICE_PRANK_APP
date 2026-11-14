package com.voiceprank.app.core

/**
 * License / entitlement gate.
 *
 * This mirrors the original app's `Gate` class and its `Gate$R` enum:
 *
 *   OK          - entitled, everything works
 *   ERROR       - generic failure, message shown to the user
 *   EXPIRED     - entitlement expired
 *   LOCKED      - device locked (too many devices / revoked)
 *   MAINT       - backend in maintenance, message shown
 *   NEED_LOGIN  - no key stored, show the login screen
 *   UPDATE      - forced update, url provided
 *   OFFLINE_OK  - previously confirmed entitlement, server unreachable
 *
 * The original also has a "own API" path where the user supplies their own
 * Cartesia key and the app works fully offline from the licensing server.
 * We support the same two modes:
 *
 *   ServerMode  - the app talks to a licensing/TTS backend (configurable base URL)
 *   OwnApiMode  - the user supplies their own Cartesia API key; no server needed
 */
object Gate {

    enum class R {
        OK,
        ERROR,
        EXPIRED,
        LOCKED,
        MAINT,
        NEED_LOGIN,
        UPDATE,
        OFFLINE_OK
    }

    data class Out(
        val r: R,
        val msg: String = "",
        val url: String = "",
        /** Extra payload, e.g. remote config for voices / model. */
        val config: Map<String, String> = emptyMap()
    ) {
        val isUsable: Boolean
            get() = r == R.OK || r == R.OFFLINE_OK
    }

    /** Free trial length granted on first launch. */
    const val TRIAL_DAYS = 3L

    /**
     * Local entitlement check.
     *
     * Order of evaluation (same as the original's `classify`):
     *   1. own-API mode  -> OK immediately, no server needed
     *   2. premium until -> OK
     *   3. trial window  -> OK
     *   4. otherwise      -> NEED_LOGIN
     */
    fun checkLocal(context: android.content.Context): Out {
        if (Prefs.ownApiEnabled && Prefs.cartesiaKey.isNotBlank()) {
            return Out(R.OK, "")
        }
        val now = System.currentTimeMillis()
        val premium = Prefs.premiumUntil
        if (premium > now) {
            return Out(R.OK, "")
        }
        val trial = Prefs.trialStart
        if (trial == 0L) {
            Prefs.trialStart = now
            Prefs.trialUntil = now + TRIAL_DAYS * 24L * 60L * 60L * 1000L
            return Out(R.OK, "")
        }
        val until = Prefs.trialUntil
        if (until > now) {
            return Out(R.OK, "")
        }
        return Out(R.NEED_LOGIN, "Your trial has ended. Add your own Cartesia API key or activate premium to continue.")
    }

    /**
     * Convert an org.json object into a plain map.
     * `JSONObject` has no `toMap()` on Android, so we walk it ourselves.
     */
    fun toMap(obj: org.json.JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = obj.opt(k)
        }
        return out
    }

    /**
     * Classify a backend response into a [Out].
     *
     * The original reads these JSON keys from the server response:
     *   ok, key, url, update, update_url, latest_code,
     *   maintenance, maintenance_message, expired, device_locked, message
     */
    fun classify(json: Map<String, Any?>): Out {
        val r = when {
            json["maintenance"] == true -> R.MAINT
            json["expired"] == true -> R.EXPIRED
            json["device_locked"] == true -> R.LOCKED
            json["update_required"] == true || json["update"] == true -> R.UPDATE
            json["ok"] == true -> R.OK
            json["ok"] == false -> R.ERROR
            else -> R.ERROR
        }
        val msg = (json["maintenance_message"] as? String)
            ?: (json["message"] as? String)
            ?: ""
        val url = (json["update_url"] as? String)
            ?: (json["url"] as? String)
            ?: ""
        return Out(r, msg, url)
    }
}

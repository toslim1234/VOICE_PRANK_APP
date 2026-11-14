package com.voiceprank.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted application state.
 *
 * The original app keeps a small obfuscated `L` helper with the same job:
 * an `en`/`loaded` pair plus a `SharedPreferences` entry holding the
 * confirmed-license flag. We keep the same idea but with readable keys.
 */
object Prefs {

    private const val FILE = "voice_prank_state"

    private const val KEY_CONFIRMED = "gate_confirmed"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_OWN_API = "own_api_enabled"
    private const val KEY_CARTESIA_KEY = "cartesia_api_key"          // legacy plaintext, migrated
    private const val KEY_CARTESIA_KEY_ENC = "cartesia_api_key_enc"
    private const val KEY_CARTESIA_MODEL = "cartesia_model"
    private const val KEY_CARTESIA_API_VERSION = "cartesia_api_version"
    private const val KEY_SERVER_BASE = "server_base"
    private const val KEY_LAST_VERIFY = "last_verify_at"
    private const val KEY_TRIAL_START = "trial_start"
    private const val KEY_TRIAL_UNTIL = "trial_until"
    private const val KEY_PREMIUM = "premium_until"
    private const val KEY_ONBOARDED = "onboarded"

    /** Mirrors the original's `en` flag: is the feature currently enabled? */
    @Volatile
    var en: Boolean = false
        private set

    /** Mirrors the original's `loaded` flag: has state been read from disk? */
    @Volatile
    var loaded: Boolean = false
        private set

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (loaded) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        loaded = true
        en = sp.getBoolean(KEY_CONFIRMED, false)
    }

    private fun require(): SharedPreferences {
        if (!loaded) {
            throw IllegalStateException("Prefs.init() must be called from Application.onCreate()")
        }
        return sp
    }

    fun setEnabled(context: Context, value: Boolean) {
        if (!loaded) init(context)
        en = value
        sp.edit().putBoolean(KEY_CONFIRMED, value).apply()
    }

    var isEnabled: Boolean
        get() = en
        set(value) {
            en = value
            if (loaded) sp.edit().putBoolean(KEY_CONFIRMED, value).apply()
        }

    var licenseKey: String
        get() = if (loaded) sp.getString(KEY_LICENSE_KEY, "") ?: "" else ""
        set(value) { if (loaded) sp.edit().putString(KEY_LICENSE_KEY, value).apply() }

    var ownApiEnabled: Boolean
        get() = loaded && sp.getBoolean(KEY_OWN_API, false)
        set(value) { if (loaded) sp.edit().putBoolean(KEY_OWN_API, value).apply() }

    /** Stored encrypted (Android Keystore). A legacy plaintext value is migrated on first read. */
    var cartesiaKey: String
        get() {
            if (!loaded) return ""
            val enc = sp.getString(KEY_CARTESIA_KEY_ENC, "").orEmpty()
            if (enc.isNotEmpty()) return SecureStore.decrypt(enc).orEmpty()
            val legacy = sp.getString(KEY_CARTESIA_KEY, "").orEmpty()
            if (legacy.isNotEmpty()) {
                cartesiaKey = legacy      // re-save encrypted, drops the plaintext
                return legacy
            }
            return ""
        }
        set(value) {
            if (!loaded) return
            val e = sp.edit().remove(KEY_CARTESIA_KEY)
            if (value.isBlank()) e.remove(KEY_CARTESIA_KEY_ENC)
            else e.putString(KEY_CARTESIA_KEY_ENC, SecureStore.encrypt(value.trim()))
            e.apply()
        }

    var cartesiaModel: String
        get() = if (loaded) sp.getString(KEY_CARTESIA_MODEL, "") ?: "" else ""
        set(value) { if (loaded) sp.edit().putString(KEY_CARTESIA_MODEL, value).apply() }

    var cartesiaApiVersion: String
        get() = if (loaded) sp.getString(KEY_CARTESIA_API_VERSION, "") ?: "" else ""
        set(value) { if (loaded) sp.edit().putString(KEY_CARTESIA_API_VERSION, value).apply() }

    var serverBase: String
        get() = if (loaded) sp.getString(KEY_SERVER_BASE, "") ?: "" else ""
        set(value) { if (loaded) sp.edit().putString(KEY_SERVER_BASE, value).apply() }

    var lastVerifyAt: Long
        get() = if (loaded) sp.getLong(KEY_LAST_VERIFY, 0L) else 0L
        set(value) { if (loaded) sp.edit().putLong(KEY_LAST_VERIFY, value).apply() }

    var trialStart: Long
        get() = if (loaded) sp.getLong(KEY_TRIAL_START, 0L) else 0L
        set(value) { if (loaded) sp.edit().putLong(KEY_TRIAL_START, value).apply() }

    var trialUntil: Long
        get() = if (loaded) sp.getLong(KEY_TRIAL_UNTIL, 0L) else 0L
        set(value) { if (loaded) sp.edit().putLong(KEY_TRIAL_UNTIL, value).apply() }

    var premiumUntil: Long
        get() = if (loaded) sp.getLong(KEY_PREMIUM, 0L) else 0L
        set(value) { if (loaded) sp.edit().putLong(KEY_PREMIUM, value).apply() }

    var onboarded: Boolean
        get() = loaded && sp.getBoolean(KEY_ONBOARDED, false)
        set(value) { if (loaded) sp.edit().putBoolean(KEY_ONBOARDED, value).apply() }

    fun clearAll() {
        if (!loaded) return
        sp.edit().clear().apply()
        en = false
    }
}

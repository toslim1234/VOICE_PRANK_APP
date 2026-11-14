package com.voiceprank.app

import android.app.Application
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.data.VoiceStore

/**
 * Application entry point.
 *
 * Mirrors the original IS FF VOICE structure:
 *  - Prefs      -> persisted app state (license, own-API mode, settings)
 *  - VoiceStore -> local voice-card library (audio + image + voice id)
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        VoiceStore.init(this)
    }

    companion object {
        @Volatile
        var instance: App? = null
            private set
    }
}

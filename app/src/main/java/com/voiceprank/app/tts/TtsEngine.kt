package com.voiceprank.app.tts

import android.content.Context
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.net.ApiClient
import com.voiceprank.app.net.CartesiaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Text-to-speech orchestration.
 *
 * The original app's `Tts` class:
 *  - talks to its backend (`/api/tts`) or, in own-API mode, straight to
 *    Cartesia
 *  - receives audio in parts (`$main` / `$done` markers)
 *  - caches the result as `tts_<id>.wav` and converts it to `pcm_<id>.pcm`
 *  - reads `x-sample-rate` and `x-credits-left` from the response headers
 *
 * We reproduce the same flow with a two-mode backend selection.
 */
class TtsEngine(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val audio: ByteArray? = null,
        val sampleRate: Int = 44100,
        val creditsLeft: Int? = null,
        val errorCode: String? = null,
        val message: String = ""
    )

    val cacheDir: File
        get() = File(context.filesDir, "tts").apply { mkdirs() }

    /** Cached wav for a given transcript+voice combination. */
    fun cacheFile(text: String, voiceId: String, ext: String): File {
        val key = com.voiceprank.app.core.Identity.sha256Hex("$text|$voiceId").take(24)
        return File(cacheDir, "tts_$key.$ext")
    }

    fun pcmFile(text: String, voiceId: String): File =
        File(cacheDir, "pcm_" + com.voiceprank.app.core.Identity.sha256Hex("$text|$voiceId").take(24) + ".pcm")

    /**
     * Generate speech.
     *
     * Own-API mode short-circuits straight to Cartesia; otherwise the
     * configured backend is used.
     */
    suspend fun generate(text: String, voiceId: String): Result {
        if (text.isBlank()) return Result(false, errorCode = "empty_text", message = "Nothing to say")
        if (voiceId.isBlank()) return Result(false, errorCode = "missing_voice_id", message = "Pick a voice")

        if (Prefs.ownApiEnabled && Prefs.cartesiaKey.isNotBlank()) {
            val r = CartesiaClient(context).tts(text, voiceId)
            if (!r.ok) {
                return Result(
                    false,
                    errorCode = r.error,
                    message = CartesiaClient.label(r.error)
                )
            }
            return Result(true, r.audio, r.sampleRate, null, null, "")
        }

        val base = Prefs.serverBase
        if (base.isBlank()) {
            return Result(
                false,
                errorCode = "no_backend",
                message = "No backend configured. Add your own Cartesia API key in Settings."
            )
        }
        val resp = ApiClient(context).tts(text, voiceId, Prefs.licenseKey)
        if (resp.code !in 200..299) {
            return Result(
                false,
                errorCode = resp.errorCode,
                message = CartesiaClient.label(resp.errorCode).ifBlank { "HTTP ${resp.code}" }
            )
        }
        val rate = resp.sampleRate ?: 44100
        return Result(true, resp.body, rate, resp.creditsLeft, null, "")
    }

    /** Convert raw PCM to a playable WAV container. */
    suspend fun toWav(pcm: ByteArray, sampleRate: Int): ByteArray = withContext(Dispatchers.IO) {
        PcmConverter.pcmToWav(pcm, sampleRate)
    }

    /** Convert a WAV container back to raw PCM. */
    suspend fun wavToPcm(wav: ByteArray): Pair<ByteArray, Int> = withContext(Dispatchers.IO) {
        PcmConverter.wavToPcm(wav)
    }
}

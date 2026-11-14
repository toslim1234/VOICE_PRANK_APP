package com.voiceprank.app.net

import android.content.Context
import com.voiceprank.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct Cartesia TTS client - the "own API" mode.
 *
 * In the original app this mode is described as:
 *   "In this mode you can enter your own Cartesia API Key and Voice ID and
 *    generate voices directly from the phone. The key never goes to the server."
 *
 * We implement exactly that: the request goes straight from the phone to
 * Cartesia, the key is never sent to the app's own backend.
 *
 * Endpoint: POST https://api.cartesia.ai/tts/bytes
 * Headers:
 *   X-API-Key:    <user key>
 *   X-API-Version: <api version>
 *   Content-Type: application/json
 * Body:
 *   { model_id, voice: { id }, transcript }
 */
class CartesiaClient(private val context: Context) {

    data class Resp(
        val code: Int,
        val audio: ByteArray,
        val sampleRate: Int,
        val error: String? = null
    ) {
        val ok: Boolean get() = code in 200..299 && audio.isNotEmpty()
    }

    private val endpoint = "https://api.cartesia.ai/tts/bytes"

    suspend fun tts(text: String, voiceId: String): Resp = withContext(Dispatchers.IO) {
        val apiKey = Prefs.cartesiaKey
        val model = Prefs.cartesiaModel.ifBlank { "sonic-2" }
        val apiVersion = Prefs.cartesiaApiVersion.ifBlank { "2024-11-13" }

        if (apiKey.isBlank()) {
            return@withContext Resp(401, ByteArray(0), 0, "missing_api_key")
        }
        if (voiceId.isBlank()) {
            return@withContext Resp(400, ByteArray(0), 0, "missing_voice_id")
        }

        // output_format is required by /tts/bytes; WAV/PCM s16le is what PcmConverter expects.
        val payload = JSONObject()
            .put("model_id", model)
            .put("transcript", text)
            .put("voice", JSONObject().put("mode", "id").put("id", voiceId.trim()))
            .put(
                "output_format", JSONObject()
                    .put("container", "wav")
                    .put("encoding", "pcm_s16le")
                    .put("sample_rate", OUTPUT_SAMPLE_RATE)
            )

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                doInput = true
                setRequestProperty("X-API-Key", apiKey)
                setRequestProperty("Cartesia-Version", apiVersion)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "audio/*")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                return@withContext Resp(code, ByteArray(0), 0, extractError(err, code))
            }

            val bytes = BufferedInputStream(conn.inputStream).use { it.readBytes() }
            Resp(code, bytes, OUTPUT_SAMPLE_RATE, null)
        } catch (t: Throwable) {
            Resp(0, ByteArray(0), 0, t.message ?: "network_error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractError(body: String, code: Int): String {
        return try {
            val j = JSONObject(body)
            j.optString("error").ifBlank { j.optString("message").ifBlank { "HTTP $code" } }
        } catch (t: Throwable) {
            if (body.isBlank()) "HTTP $code" else body.take(200)
        }
    }

    companion object {
        const val OUTPUT_SAMPLE_RATE = 44100

        /** Cartesia error codes the original surfaces to the user. */
        val ERROR_LABELS = mapOf(
            "no_credit" to "No credits left",
            "credits" to "Credit info",
            "disabled" to "TTS disabled by the server",
            "device_mismatch" to "This key belongs to another device",
            "invalid_key" to "Invalid API key",
            "expired" to "Your subscription has expired",
            "missing_api_key" to "Add your Cartesia API key in Settings",
            "missing_voice_id" to "Pick a voice first",
            "network_error" to "Network error"
        )

        fun label(code: String?): String = when (code) {
            null -> ""
            else -> ERROR_LABELS[code] ?: code
        }
    }
}

package com.voiceprank.app.net

import android.content.Context
import com.voiceprank.app.core.Identity
import com.voiceprank.app.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client.
 *
 * The original app's `Api.call` sends:
 *
 *     User-Agent: DJB2AL/1.0
 *     Content-Type: application/x-www-form-urlencoded
 *     Accept: application/json
 *     X-API-Client: NativeApp
 *     X-Legacy-Key / X-Legacy-App-Version / X-Legacy-Device / X-Legacy-App-Sig
 *
 * and reads `x-sample-rate` / `x-credits-left` / `code` from the response.
 * We reproduce that header set so the same backend contract works.
 */
class ApiClient(private val context: Context) {

    data class Resp(
        val code: Int,
        val body: ByteArray,
        val headers: Map<String, String>
    ) {
        fun text(): String = String(body, Charsets.UTF_8)

        fun json(): JSONObject? = try {
            JSONObject(text())
        } catch (t: Throwable) {
            null
        }

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        val errorCode: String? get() = header("code")
        val sampleRate: Int? get() = header("x-sample-rate")?.toIntOrNull()
        val creditsLeft: Int? get() = header("x-credits-left")?.toIntOrNull()
    }

    private val baseUrl: String
        get() = Prefs.serverBase.trimEnd('/')

    suspend fun call(
        path: String,
        method: String = "POST",
        body: JSONObject? = null,
        key: String = Prefs.licenseKey,
        timeoutMs: Int = 30_000
    ): Resp = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$path")
        if (url.protocol != "https") {
            return@withContext Resp(0, """{"ok":false,"message":"Backend URL must use https"}""".toByteArray(), emptyMap())
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doInput = true
            setRequestProperty("User-Agent", "DJB2AL/1.0")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Client", "NativeApp")
            if (key.isNotBlank()) setRequestProperty("X-Legacy-Key", key)
            setRequestProperty("X-Legacy-App-Version", Identity.versionCode(context).toString())
            setRequestProperty("X-Legacy-Device", Identity.deviceId(context))
            setRequestProperty("X-Legacy-App-Sig", Identity.signatureHash(context))
        }
        try {
            if (method != "GET" && body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, v) -> v.firstOrNull().orEmpty() }
            Resp(code, bytes, headers)
        } finally {
            conn.disconnect()
        }
    }

    /** Build the JSON body the original sends along with every request. */
    fun withDevice(extra: JSONObject? = null): JSONObject {
        val obj = extra ?: JSONObject()
        obj.put("device", Identity.deviceId(context))
        obj.put("version", Identity.versionCode(context))
        obj.put("version_name", Identity.versionName(context))
        obj.put("app_sig", Identity.signatureHash(context))
        obj.put("client", "NativeApp")
        return obj
    }

    /**
     * Ask the backend for the remote configuration.
     * Keys the original reads: voices, key, url, update, latest_code,
     * maintenance, maintenance_message.
     */
    suspend fun config(): Resp = call("/api/config", "GET")

    /** Verify a license key for this device. */
    suspend fun verify(key: String): Resp =
        call("/api/verify", "POST", withDevice(), key)

    /** Redeem a coupon code. */
    suspend fun redeem(coupon: String, key: String): Resp =
        call("/api/redeem", "POST", withDevice().put("coupon", coupon), key)

    /**
     * Text-to-speech through the backend.
     * The backend proxies Cartesia and returns audio bytes.
     */
    suspend fun tts(text: String, voiceId: String, key: String): Resp =
        call(
            "/api/tts",
            "POST",
            withDevice()
                .put("text", text)
                .put("voice_id", voiceId)
                .put("model", Prefs.cartesiaModel.ifBlank { "sonic-2" }),
            key
        )
}

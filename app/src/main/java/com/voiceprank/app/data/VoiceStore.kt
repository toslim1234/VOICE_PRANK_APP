package com.voiceprank.app.data

import android.content.Context
import android.util.Log
import com.voiceprank.app.core.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local voice-card library.
 *
 * The original app keeps its cards on disk and lists them in `MainActivity`
 * with a search field. We store the index as JSON in `filesDir` and the media
 * in sibling folders, so the structure is easy to inspect and to back up.
 */
object VoiceStore {

    private const val TAG = "VoicePrankStore"
    private const val INDEX = "cards.json"

    @Volatile
    private var appContext: Context? = null

    private val cache = mutableListOf<VoiceCard>()

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    private fun require(): Context =
        appContext ?: throw IllegalStateException("VoiceStore.init() not called")

    private fun root(): File = File(require().filesDir, "cards").apply { mkdirs() }

    private fun audioDir(): File = File(root(), "audio").apply { mkdirs() }

    private fun imageDir(): File = File(root(), "images").apply { mkdirs() }

    private fun indexFile(): File = File(require().filesDir, INDEX)

    // ------------------------------------------------------------------ read

    fun all(): List<VoiceCard> = cache.toList()

    fun get(id: String): VoiceCard? = cache.firstOrNull { it.id == id }

    fun search(query: String): List<VoiceCard> {
        if (query.isBlank()) return all()
        val q = query.trim()
        return cache.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.voiceId.contains(q, ignoreCase = true) ||
                it.voiceIds.any { v -> v.contains(q, ignoreCase = true) }
        }
    }

    // ----------------------------------------------------------------- write

    fun add(card: VoiceCard): VoiceCard {
        cache.add(0, card)
        save()
        return card
    }

    fun update(card: VoiceCard) {
        val idx = cache.indexOfFirst { it.id == card.id }
        if (idx >= 0) {
            cache[idx] = card
            save()
        }
    }

    fun remove(id: String): Boolean {
        val card = get(id) ?: return false
        cache.removeAll { it.id == id }
        File(card.audioPath).takeIf { it.exists() }?.delete()
        File(card.imagePath).takeIf { it.exists() }?.delete()
        save()
        return true
    }

    /** Store raw bytes as the card's audio and return its absolute path. */
    fun saveAudio(id: String, bytes: ByteArray, ext: String = "wav"): String {
        val f = File(audioDir(), "$id.$ext")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun saveImage(id: String, bytes: ByteArray, ext: String = "jpg"): String {
        val f = File(imageDir(), "$id.$ext")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun hashOf(path: String): String = try {
        Identity.sha256Hex(File(path).readBytes())
    } catch (t: Throwable) {
        ""
    }

    // ------------------------------------------------------------- serialize

    private fun save() {
        try {
            val arr = JSONArray()
            cache.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("voice_id", c.voiceId)
                        .put("audio", c.audioPath)
                        .put("image", c.imagePath)
                        .put("audio_hash", c.audioHash)
                        .put("voices", JSONArray(c.voiceIds))
                        .put("created", c.createdAt)
                )
            }
            indexFile().writeText(arr.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "save failed", t)
        }
    }

    private fun load() {
        cache.clear()
        try {
            val f = indexFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val voices = mutableListOf<String>()
                val vArr = o.optJSONArray("voices")
                if (vArr != null) {
                    for (k in 0 until vArr.length()) voices.add(vArr.optString(k))
                }
                cache.add(
                    VoiceCard(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        voiceId = o.optString("voice_id"),
                        audioPath = o.optString("audio"),
                        imagePath = o.optString("image"),
                        audioHash = o.optString("audio_hash"),
                        voiceIds = voices,
                        createdAt = o.optLong("created", System.currentTimeMillis())
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
        }
    }

    fun reload() = load()
}

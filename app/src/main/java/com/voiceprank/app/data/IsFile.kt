package com.voiceprank.app.data

import android.util.Log
import com.voiceprank.app.core.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * "IS File" bundles - the original app's export/import format.
 *
 * Layout (identical to the original):
 *
 *     manifest.json
 *     audio/<name>
 *     images/<name>.jpg
 *
 * `manifest.json`:
 *
 *     {
 *       "format": "isfile",
 *       "version": 1,
 *       "app": "IS FF VOICE",
 *       "appVersion": "2.4",
 *       "created": "2026-01-01T00:00:00",
 *       "count": 2,
 *       "cards": [ { "name": ..., "audio": "audio/x.wav", "image": "images/x.jpg",
 *                    "voices": [...], "sha256": "..." } ]
 *     }
 *
 * The original is careful about safety on import, and so are we:
 *   - max 200 cards
 *   - max 100 MB total size
 *   - path traversal rejected
 *   - too many zip entries rejected
 *   - the audio hash is verified on import
 */
object IsFile {

    private const val TAG = "VoicePrankIsFile"

    const val FORMAT = "isfile"
    const val VERSION = 1
    const val APP_NAME = "IS FF VOICE"

    const val MAX_CARDS = 200
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    const val MAX_ENTRIES = 2000

    // ---------------------------------------------------------------- export

    fun export(cards: List<VoiceCard>, outFile: File): Result<File> {
        if (cards.isEmpty()) return Result.failure(IOException("No cards selected"))
        if (cards.size > MAX_CARDS) {
            return Result.failure(IOException("You can export at most $MAX_CARDS cards at once"))
        }
        return try {
            val manifest = buildManifest(cards)
            var total = manifest.toString().toByteArray().size.toLong()
            cards.forEach { c ->
                File(c.audioPath).takeIf { it.exists() }?.let { total += it.length() }
                File(c.imagePath).takeIf { it.exists() }?.let { total += it.length() }
            }
            if (total > MAX_TOTAL_BYTES) {
                return Result.failure(IOException("Total size exceeds 100 MB - select fewer cards"))
            }

            outFile.parentFile?.mkdirs()
            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()

                cards.forEachIndexed { index, card ->
                    val audio = File(card.audioPath)
                    if (audio.exists()) {
                        val name = "audio/%03d_%s".format(index, safeName(card.name) + ".wav")
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(audio.readBytes())
                        zip.closeEntry()
                    }
                    val image = File(card.imagePath)
                    if (image.exists()) {
                        val name = "images/%03d_%s.jpg".format(index, safeName(card.name))
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(image.readBytes())
                        zip.closeEntry()
                    }
                }
            }
            Result.success(outFile)
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            Result.failure(t as? IOException ?: IOException(t.message ?: "export failed", t))
        }
    }

    private fun buildManifest(cards: List<VoiceCard>): JSONObject {
        val arr = JSONArray()
        cards.forEachIndexed { index, card ->
            val entry = JSONObject()
                .put("name", card.name)
                .put("voices", JSONArray(card.voiceIds))
                .put("audio", "audio/%03d_%s.wav".format(index, safeName(card.name)))
                .put("image", "images/%03d_%s.jpg".format(index, safeName(card.name)))
            if (card.audioHash.isNotBlank()) entry.put("sha256", card.audioHash)
            arr.put(entry)
        }
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", APP_NAME)
            .put("appVersion", "2.4")
            .put("created", now)
            .put("count", cards.size)
            .put("cards", arr)
    }

    // ---------------------------------------------------------------- import

    data class ImportResult(
        val cards: List<VoiceCard>,
        val error: String? = null
    )

    fun import(zipFile: File): ImportResult {
        if (!zipFile.exists()) return ImportResult(emptyList(), "Could not open the file")
        if (zipFile.length() > MAX_TOTAL_BYTES) {
            return ImportResult(emptyList(), "File is larger than 100 MB - cannot import")
        }
        return try {
            var manifest: JSONObject? = null
            var entries = 0
            var totalBytes = 0L
            val audio = HashMap<String, ByteArray>()
            val images = HashMap<String, ByteArray>()

            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    entries++
                    if (entries > MAX_ENTRIES) {
                        return ImportResult(emptyList(), "Too many entries inside the file - looks unsafe, cancelled")
                    }
                    val name = entry.name
                    // path traversal check
                    if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                        return ImportResult(emptyList(), "Unsafe path found inside the file - cancelled")
                    }
                    when {
                        name == "manifest.json" -> {
                            val data = readBounded(zip, 1L * 1024 * 1024)       // manifest <= 1 MB
                            totalBytes += data.size
                            manifest = JSONObject(String(data, Charsets.UTF_8))
                        }
                        name.startsWith("audio/") -> {
                            val data = readBounded(zip, MAX_TOTAL_BYTES - totalBytes)
                            totalBytes += data.size
                            audio[name] = data
                        }
                        name.startsWith("images/") -> {
                            val data = readBounded(zip, MAX_TOTAL_BYTES - totalBytes)
                            totalBytes += data.size
                            images[name] = data
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (manifest == null) {
                return ImportResult(emptyList(), "manifest.json missing - this is not an IS File")
            }
            val m = manifest!!
            if (m.optString("format") != FORMAT) {
                return ImportResult(emptyList(), "This is not a valid IS File (corrupted or wrong file)")
            }
            if (m.optInt("version", 0) > VERSION) {
                return ImportResult(emptyList(), "This IS File is from a newer version - update the app first")
            }
            val arr = m.optJSONArray("cards")
                ?: return ImportResult(emptyList(), "The file has no card list")

            if (arr.length() > MAX_CARDS) {
                return ImportResult(emptyList(), "Too many cards in the file (max $MAX_CARDS)")
            }
            var skipped = 0
            val imported = mutableListOf<VoiceCard>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                val voiceIds = mutableListOf<String>()
                val vArr = o.optJSONArray("voices")
                if (vArr != null) for (k in 0 until vArr.length()) voiceIds.add(vArr.optString(k))

                val audioName = o.optString("audio")
                val imageName = o.optString("image")
                val bytes = audio[audioName]

                // Refuse a card whose audio does not match the hash the exporter recorded.
                val expected = o.optString("sha256")
                if (expected.isNotBlank() && bytes != null &&
                    !Identity.sha256Hex(bytes).equals(expected, ignoreCase = true)
                ) {
                    Log.w(TAG, "hash mismatch, skipping card $name")
                    skipped++
                    continue
                }

                val id = java.util.UUID.randomUUID().toString().replace("-", "")
                val audioPath = if (bytes != null) {
                    val f = File(File(requireStoreRoot(), "audio"), "$id.wav")
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    f.absolutePath
                } else ""
                val imagePath = images[imageName]?.let { b ->
                    val f = File(File(requireStoreRoot(), "images"), "$id.jpg")
                    f.parentFile?.mkdirs()
                    f.writeBytes(b)
                    f.absolutePath
                } ?: ""

                imported.add(
                    VoiceCard(
                        id = id,
                        name = name,
                        voiceId = voiceIds.firstOrNull() ?: "",
                        audioPath = audioPath,
                        imagePath = imagePath,
                        audioHash = if (bytes != null) Identity.sha256Hex(bytes) else "",
                        voiceIds = voiceIds
                    )
                )
            }
            if (imported.isEmpty()) {
                return ImportResult(
                    emptyList(),
                    if (skipped > 0) "$skipped card(s) failed the integrity check - file is corrupted or modified"
                    else "No usable voices in the file"
                )
            }
            ImportResult(imported, null)
        } catch (t: Throwable) {
            Log.e(TAG, "import failed", t)
            ImportResult(emptyList(), t.message ?: "The file could not be read")
        }
    }

    // ----------------------------------------------------------------- utils

    private fun requireStoreRoot(): String {
        val ctx = com.voiceprank.app.App.instance
            ?: throw IllegalStateException("App not initialised")
        return File(ctx.filesDir, "cards").apply { mkdirs() }.absolutePath
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return if (cleaned.isBlank()) "voice" else cleaned.take(48)
    }

    /** Reads at most [limit] bytes; throws when the entry is larger (zip-bomb guard). */
    private fun readBounded(stream: InputStream, limit: Long): ByteArray {
        if (limit <= 0) throw IOException("Total size exceeds 100 MB - cannot import")
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Total size inside the file exceeds 100 MB - cannot import")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

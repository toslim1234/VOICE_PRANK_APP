package com.voiceprank.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.voiceprank.app.R
import com.voiceprank.app.core.Prefs
import com.voiceprank.app.core.Premium
import com.voiceprank.app.data.IsFile
import com.voiceprank.app.data.VoiceCard
import com.voiceprank.app.data.VoiceStore
import com.voiceprank.app.databinding.ActivityVoiceEditBinding
import com.voiceprank.app.inject.InjectCtl
import com.voiceprank.app.inject.ShellRunner
import com.voiceprank.app.tts.PcmConverter
import com.voiceprank.app.tts.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Voice editor + TTS panel.
 *
 * Combines what the original splits across `MainActivity` (add voice),
 * `Importer` (pull audio + thumbnail out of picked media) and the TTS page of
 * the overlay panel (text -> audio -> inject).
 */
class VoiceEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "card_id"
    }

    private lateinit var b: ActivityVoiceEditBinding
    private var card: VoiceCard? = null
    private var audioBytes: ByteArray? = null
    private var audioSampleRate: Int = 44100
    private var pendingImage: ByteArray? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importAudio(it) }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importImage(it) }
    }

    private val pickBundle = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importBundle(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoiceEditBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getStringExtra(EXTRA_CARD_ID)
        if (id != null) {
            card = VoiceStore.get(id)
        }

        b.btnPickAudio.setOnClickListener { pickAudio.launch("audio/*") }
        b.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        b.btnSave.setOnClickListener { save() }
        b.btnGenerate.setOnClickListener { generate() }
        b.btnInject.setOnClickListener { inject() }
        b.btnExport.setOnClickListener { exportBundle() }
        b.btnImport.setOnClickListener { pickBundle.launch("*/*") }

        card?.let { c ->
            b.nameInput.setText(c.name)
            b.voiceIdInput.setText(c.voiceId)
            b.sampleStatus.text =
                File(c.audioPath).takeIf { it.exists() }?.let { "Sample audio ready (${it.length()} bytes)" }
                    ?: "No sample audio yet"
        }
    }

    // --------------------------------------------------------------- import

    private fun importAudio(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (t: Throwable) {
                    null
                }
            }
            if (bytes == null || bytes.isEmpty()) {
                toast("Could not read that file")
                return@launch
            }
            // Normalise everything to WAV so playback and injection are uniform.
            val (pcm, rate) = if (PcmConverter.isWav(bytes)) {
                PcmConverter.wavToPcm(bytes)
            } else {
                bytes to 44100
            }
            audioBytes = PcmConverter.pcmToWav(pcm, rate)
            audioSampleRate = rate
            b.sampleStatus.text = "Sample audio ready (${audioBytes!!.size} bytes @ $rate Hz)"
        }
    }

    private fun importImage(uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (t: Throwable) {
                    null
                }
            }
            if (bytes == null) {
                toast("Could not read that image")
                return@launch
            }
            pendingImage = bytes
            b.imageStatus.text = "Cover image selected"
        }
    }

    private fun importBundle(uri: Uri) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val tmp = File(cacheDir, "import_${System.currentTimeMillis()}.zip")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    IsFile.import(tmp)
                } catch (t: Throwable) {
                    IsFile.ImportResult(emptyList(), t.message)
                } finally {
                    tmp.delete()
                }
            }
            if (res.error != null) {
                toast(res.error)
                return@launch
            }
            if (!Premium.isPremium(this@VoiceEditActivity)) {
                toast("Importing voice bundles needs premium")
                return@launch
            }
            res.cards.forEach { VoiceStore.add(it) }
            toast("Imported ${res.cards.size} voice(s)")
            finish()
        }
    }

    // ----------------------------------------------------------------- save

    private fun save() {
        val name = b.nameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            toast("Name must not be empty")
            return
        }
        val voiceId = b.voiceIdInput.text?.toString()?.trim().orEmpty()
        val existing = card

        val id = existing?.id ?: java.util.UUID.randomUUID().toString().replace("-", "")
        val audioPath = audioBytes?.let { VoiceStore.saveAudio(id, it) } ?: existing?.audioPath.orEmpty()
        val imagePath = pendingImage?.let { VoiceStore.saveImage(id, it) } ?: existing?.imagePath.orEmpty()
        val hash = if (audioPath.isNotBlank()) VoiceStore.hashOf(audioPath) else ""

        val updated = VoiceCard(
            id = id,
            name = name,
            voiceId = voiceId,
            audioPath = audioPath,
            imagePath = imagePath,
            audioHash = hash,
            voiceIds = existing?.voiceIds ?: emptyList(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        if (existing == null) {
            if (VoiceStore.all().size >= Premium.maxCards(this) && !Premium.isPremium(this)) {
                toast("Free accounts can store ${Premium.maxCards(this)} voices. Go premium for more.")
                return
            }
            VoiceStore.add(updated)
        } else {
            VoiceStore.update(updated)
        }
        card = updated
        toast("Saved")
    }

    // ------------------------------------------------------------- generate

    private fun generate() {
        val current = card ?: run {
            toast("Save the voice first")
            return
        }
        val text = b.sampleText.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            toast("Nothing to generate")
            return
        }
        val voiceId = current.voiceId.ifBlank { b.voiceIdInput.text?.toString()?.trim().orEmpty() }
        b.btnGenerate.isEnabled = false
        b.generateStatus.text = "Generating..."

        lifecycleScope.launch {
            val engine = TtsEngine(this@VoiceEditActivity)
            val result = engine.generate(text, voiceId)
            b.btnGenerate.isEnabled = true
            if (!result.ok) {
                b.generateStatus.text = result.message.ifBlank { "Could not create audio" }
                toast(result.message.ifBlank { "Could not create audio" })
                return@launch
            }
            val bytes = result.audio ?: return@launch
            val (pcm, rate) = if (PcmConverter.isWav(bytes)) {
                PcmConverter.wavToPcm(bytes)
            } else {
                bytes to result.sampleRate
            }
            val wav = PcmConverter.pcmToWav(pcm, rate)
            audioBytes = wav
            audioSampleRate = rate
            b.generateStatus.text =
                "Audio ready (${wav.size} bytes, ${PcmConverter.durationMs(pcm.size, rate)} ms)"
            result.creditsLeft?.let { b.generateStatus.append(" | credits left: $it") }
        }
    }

    // -------------------------------------------------------------- inject

    private fun inject() {
        val bytes = audioBytes ?: run {
            toast("Generate or import audio first")
            return
        }
        val ctl = InjectCtl(this)
        val modes = ctl.availableModes()
        if (modes.isEmpty()) {
            toast("Root or Shizuku is required for injection")
            return
        }
        if (modes.size == 1) {
            doInject(ctl, modes[0], bytes)
            return
        }
        val labels = arrayOf("Root", "Shizuku")
        MaterialAlertDialogBuilder(this)
            .setTitle("Injection mode")
            .setItems(labels) { _, which -> doInject(ctl, modes[which], bytes) }
            .show()
    }

    private fun doInject(ctl: InjectCtl, mode: ShellRunner.Mode, bytes: ByteArray) {
        b.injectStatus.text = "Starting injection..."
        lifecycleScope.launch {
            val pcm = if (PcmConverter.isWav(bytes)) PcmConverter.wavToPcm(bytes).first else bytes
            val tmp = withContext(Dispatchers.IO) {
                val f = File(cacheDir, "inject_${System.currentTimeMillis()}.pcm")
                f.writeBytes(pcm)
                f
            }
            val status = withContext(Dispatchers.IO) { ctl.start(tmp, audioSampleRate, mode) }
            b.injectStatus.text = when (status.state) {
                InjectCtl.State.RUNNING -> "Injection on ✅ (${modeLabel(mode)})"
                InjectCtl.State.FAILED -> "Did not start ❌ (${status.detail})"
                else -> status.detail
            }
            toast(b.injectStatus.text.toString())
        }
    }

    private fun modeLabel(mode: ShellRunner.Mode) = when (mode) {
        ShellRunner.Mode.ROOT -> "Root"
        ShellRunner.Mode.SHIZUKU -> "Shizuku"
        ShellRunner.Mode.NONE -> "none"
    }

    // --------------------------------------------------------------- export

    private fun exportBundle() {
        if (!Premium.isPremium(this)) {
            toast("Exporting voice bundles needs premium")
            return
        }
        val cards = card?.let { listOf(it) } ?: VoiceStore.all()
        if (cards.isEmpty()) {
            toast("No cards to export")
            return
        }
        val out = File(getExternalFilesDir(null) ?: filesDir, "export_${System.currentTimeMillis()}.zip")
        val res = IsFile.export(cards, out)
        if (res.isFailure) {
            toast(res.exceptionOrNull()?.message ?: "Export failed")
            return
        }
        toast("Exported to ${out.name}")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

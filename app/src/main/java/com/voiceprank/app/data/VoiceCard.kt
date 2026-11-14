package com.voiceprank.app.data

import java.io.File

/**
 * A voice card: one clonable voice profile.
 *
 * The original app stores a card as audio + image + a Cartesia Voice ID, and
 * calls the container an "IS File" (`.isfile`). We keep the same model and
 * the same export format so bundles remain interchangeable.
 */
data class VoiceCard(
    val id: String,
    val name: String,
    /** Cartesia Voice ID, or empty for locally recorded samples. */
    val voiceId: String = "",
    /** Absolute path of the sample audio file. */
    val audioPath: String = "",
    /** Absolute path of the cover image, optional. */
    val imagePath: String = "",
    /** SHA-256 of the audio, used to detect tampering on import. */
    val audioHash: String = "",
    /** Voice IDs bundled with this card (clone voice list). */
    val voiceIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val hasAudio: Boolean get() = audioPath.isNotBlank() && File(audioPath).exists()

    val displaySubtitle: String
        get() = when {
            voiceId.isNotBlank() -> voiceId
            hasAudio -> "local sample"
            else -> "no voice"
        }
}

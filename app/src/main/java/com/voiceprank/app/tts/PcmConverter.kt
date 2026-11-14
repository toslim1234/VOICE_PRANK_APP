package com.voiceprank.app.tts

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV <-> raw PCM conversion.
 *
 * The original app has a `PcmConverter` that uses MediaCodec to decode
 * whatever the backend returns, then stores the result as `.wav` / `.pcm`.
 *
 * For our purposes we accept the two shapes the server can return:
 *  - a real RIFF/WAVE container  -> strip the header, keep the PCM
 *  - raw PCM bytes              -> wrap them into a WAV header
 *
 * The container we produce is a canonical 44-byte RIFF/WAVE, PCM 16-bit,
 * which every Android audio decoder and every game voice-chat stack accepts.
 */
object PcmConverter {

    const val DEFAULT_SAMPLE_RATE = 44100
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** True when the buffer starts with a RIFF....WAVE header. */
    fun isWav(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
    }

    /** Extract the `fmt ` chunk to learn the real sample rate. */
    fun wavSampleRate(bytes: ByteArray): Int {
        if (!isWav(bytes)) return DEFAULT_SAMPLE_RATE
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readLeInt(bytes, pos + 4)
            if (id == "fmt " && pos + 8 + 16 <= bytes.size) {
                val rate = readLeInt(bytes, pos + 12)
                if (rate > 0) return rate
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        return DEFAULT_SAMPLE_RATE
    }

    /** Strip the RIFF container and return raw PCM plus its sample rate. */
    fun wavToPcm(bytes: ByteArray): Pair<ByteArray, Int> {
        if (!isWav(bytes)) return bytes to DEFAULT_SAMPLE_RATE
        val rate = wavSampleRate(bytes)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readLeInt(bytes, pos + 4)
            if (id == "data") {
                val start = pos + 8
                // streamed WAVs carry 0 / 0xFFFFFFFF as size: take everything to the end
                val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
                return bytes.copyOfRange(start, end) to rate
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        return bytes to rate
    }

    /** Wrap raw PCM into a canonical RIFF/WAVE container. */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, 36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, 16)                 // PCM chunk size
        writeLeShort(out, 1)               // format = PCM
        writeLeShort(out, CHANNELS)
        writeLeInt(out, sampleRate)
        writeLeInt(out, byteRate)
        writeLeShort(out, blockAlign)
        writeLeShort(out, BITS_PER_SAMPLE)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** Duration of a PCM buffer in milliseconds. */
    fun durationMs(pcmBytes: Int, sampleRate: Int = DEFAULT_SAMPLE_RATE): Long {
        val bytesPerSecond = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        if (bytesPerSecond <= 0) return 0
        return (pcmBytes * 1000L) / bytesPerSecond
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write(b)
    }

    private fun writeLeShort(out: ByteArrayOutputStream, v: Int) {
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        out.write(b)
    }

    private fun readLeInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
    }
}

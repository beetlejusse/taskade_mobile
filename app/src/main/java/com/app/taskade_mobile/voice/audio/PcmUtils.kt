package com.app.taskade_mobile.voice.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM-16 conversions used on both sides of the socket. Endianness is forced to
 * little-endian explicitly (the wire format the backend / Deepgram expect — PRD
 * §4.1), independent of the device's native byte order.
 */
object PcmUtils {

    /** Packs PCM-16 samples into little-endian bytes ready for a binary WS frame. */
    fun shortsToLeBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 2)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) buf.putShort(samples[i])
        return out
    }

    /** Unpacks little-endian PCM-16 bytes (a TTS chunk) into samples for playback. */
    fun leBytesToShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) out[i] = buf.short
        return out
    }
}

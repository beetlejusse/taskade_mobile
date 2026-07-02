package com.app.taskade_mobile.voice.vad

import android.content.Context
import android.util.Log
import kotlin.math.sqrt

/**
 * Frame-level voice-activity model: maps a fixed-size PCM-16 frame to a speech
 * probability in `[0, 1]`. Used **only for barge-in** (end-of-turn is decided
 * server-side by Deepgram — PRD §5.2).
 *
 * Two implementations exist: the Silero ONNX model (matching the web client) and
 * a lightweight RMS-energy fallback. [Factory] picks Silero when the bundled
 * `silero_vad_v5.onnx` asset is present, otherwise the energy model.
 */
interface VadModel {
    /** Speech probability for one frame (PCM-16 samples). */
    fun probability(frame: ShortArray): Float

    /** Clears any internal recurrent state between turns. */
    fun reset()

    fun close()

    object Factory {
        private const val TAG = "VadModel"
        const val SILERO_ASSET = "silero_vad_v5.onnx"

        /**
         * Returns the best available model. Ships robustly: if the Silero asset is
         * missing or ONNX Runtime fails to initialize, the energy model is used so
         * barge-in still works (with coarser tuning).
         */
        fun create(context: Context): VadModel {
            if (hasSileroAsset(context)) {
                runCatching { SileroVadModel(context) }
                    .onSuccess { return it }
                    .onFailure { Log.w(TAG, "Silero VAD unavailable, using energy fallback", it) }
            } else {
                Log.i(TAG, "$SILERO_ASSET not bundled; using energy VAD fallback")
            }
            return EnergyVadModel()
        }

        private fun hasSileroAsset(context: Context): Boolean =
            runCatching { context.assets.list("")?.contains(SILERO_ASSET) == true }
                .getOrDefault(false)
    }
}

/**
 * RMS-energy VAD fallback. Crude but dependency-free: normalizes the frame's RMS
 * and maps it through a soft threshold. Combined with the platform AEC on the
 * capture path, it is adequate for barge-in when Silero isn't available.
 */
class EnergyVadModel : VadModel {

    override fun probability(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / frame.size) / Short.MAX_VALUE // 0..1
        // Map [SILENCE_FLOOR, SPEECH_CEIL] → [0, 1].
        val norm = ((rms - SILENCE_FLOOR) / (SPEECH_CEIL - SILENCE_FLOOR))
        return norm.coerceIn(0.0, 1.0).toFloat()
    }

    override fun reset() = Unit
    override fun close() = Unit

    private companion object {
        const val SILENCE_FLOOR = 0.02
        const val SPEECH_CEIL = 0.12
    }
}

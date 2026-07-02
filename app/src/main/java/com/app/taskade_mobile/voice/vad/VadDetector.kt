package com.app.taskade_mobile.voice.vad

import com.app.taskade_mobile.core.ApiConfig

/**
 * Turns per-frame [VadModel] probabilities into a stable speaking/not-speaking
 * signal for **barge-in**, using the web client's tuned thresholds (PRD §5.2):
 *  - speech is confirmed after the probability stays ≥ [POSITIVE_THRESHOLD] for
 *    at least [MIN_SPEECH_MS] (debounces transient noise), and
 *  - it is released after [REDEMPTION_MS] of sub-threshold frames (so brief gaps
 *    mid-utterance don't end it).
 *
 * Not thread-safe: drive it from the single mic-reader loop.
 */
class VadDetector(
    private val model: VadModel,
    private val frameMs: Float =
        ApiConfig.MIC_FRAME_SAMPLES * 1000f / ApiConfig.MIC_SAMPLE_RATE
) {
    private var speechMs = 0f
    private var silenceMs = 0f
    private var speaking = false

    /** Feeds one frame; returns the current debounced speaking state. */
    fun process(frame: ShortArray): Boolean {
        val p = model.probability(frame)
        if (p >= POSITIVE_THRESHOLD) {
            speechMs += frameMs
            silenceMs = 0f
            if (speechMs >= MIN_SPEECH_MS) speaking = true
        } else {
            silenceMs += frameMs
            if (silenceMs >= REDEMPTION_MS) {
                speaking = false
                speechMs = 0f
            }
        }
        return speaking
    }

    /** Clears state and the model's recurrent buffers (call between turns). */
    fun reset() {
        speechMs = 0f
        silenceMs = 0f
        speaking = false
        model.reset()
    }

    fun close() = model.close()

    private companion object {
        const val POSITIVE_THRESHOLD = 0.8f
        const val MIN_SPEECH_MS = 150f
        const val REDEMPTION_MS = 600f
    }
}

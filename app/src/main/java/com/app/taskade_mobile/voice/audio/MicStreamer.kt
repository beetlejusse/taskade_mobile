package com.app.taskade_mobile.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.app.taskade_mobile.core.ApiConfig
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Continuous microphone capture → PCM-16 mono @16 kHz frames (PRD §5.1).
 *
 * - Captures via [MediaRecorder.AudioSource.VOICE_COMMUNICATION] so the platform
 *   AEC/NS/AGC path is engaged, and additionally attaches the [AcousticEchoCanceler]
 *   / [NoiseSuppressor] / [AutomaticGainControl] effects when available — critical
 *   so the assistant's own voice doesn't self-trigger barge-in (PRD §5.4).
 * - Reads fixed [ApiConfig.MIC_FRAME_SAMPLES] frames and hands each to [onFrame]
 *   on a dedicated capture thread.
 * - Keeps a rolling [ApiConfig.RING_BUFFER_FRAMES] ring buffer of recent frames so
 *   the ~500 ms that triggered a barge-in can be flushed to the server (PRD §4.4).
 *
 * The caller must hold `RECORD_AUDIO` before [start]; otherwise it no-ops.
 */
class MicStreamer(
    private val onFrame: (ShortArray) -> Unit
) {
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private val ring = ArrayDeque<ShortArray>(ApiConfig.RING_BUFFER_FRAMES)
    private val ringLock = Any()

    val isRunning: Boolean get() = recording

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO
    fun start() {
        if (recording) return

        val minBuffer = AudioRecord.getMinBufferSize(
            ApiConfig.MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            return
        }
        val bufferSize = maxOf(minBuffer, ApiConfig.MIC_FRAME_SAMPLES * 2 * 8)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                ApiConfig.MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            record.release()
            return
        }

        audioRecord = record
        attachEffects(record.audioSessionId)

        recording = true
        record.startRecording()
        captureThread = thread(name = "mic-capture", priority = Thread.MAX_PRIORITY) {
            captureLoop(record)
        }
    }

    fun stop() {
        recording = false
        captureThread?.let { runCatching { it.join(500) } }
        captureThread = null

        audioRecord?.let { record ->
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
        }
        audioRecord = null
        releaseEffects()
        synchronized(ringLock) { ring.clear() }
    }

    /** A copy of the recent frames (oldest → newest) for the barge-in flush. */
    fun ringSnapshot(): List<ShortArray> = synchronized(ringLock) { ring.map { it.copyOf() } }

    private fun captureLoop(record: AudioRecord) {
        val frame = ShortArray(ApiConfig.MIC_FRAME_SAMPLES)
        while (recording) {
            val read = record.read(frame, 0, frame.size)
            if (read <= 0) continue
            val copy = frame.copyOf(read)
            pushRing(copy)
            try {
                onFrame(copy)
            } catch (e: Exception) {
                Log.w(TAG, "onFrame handler threw", e)
            }
        }
    }

    private fun pushRing(frame: ShortArray) {
        synchronized(ringLock) {
            if (ring.size >= ApiConfig.RING_BUFFER_FRAMES) ring.pollFirst()
            ring.addLast(frame)
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        }
        Log.d(TAG, "Effects — AEC=${aec != null} NS=${ns != null} AGC=${agc != null}")
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        runCatching { agc?.release() }; agc = null
    }

    private companion object {
        const val TAG = "MicStreamer"
    }
}

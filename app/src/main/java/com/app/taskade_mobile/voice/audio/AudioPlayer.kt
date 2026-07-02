package com.app.taskade_mobile.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.app.taskade_mobile.core.ApiConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Gapless PCM-16 playback over [AudioTrack] in streaming mode — the native
 * equivalent of the web client's `audioPlayer.js` (PRD §5.3).
 *
 * Per turn:
 *  1. [beginTurn] on `tts.start` — (re)creates the track at the announced sample
 *     rate (never hardcoded) and resets state,
 *  2. [enqueue] each binary chunk — written gaplessly by a single writer thread
 *     after a small pre-buffer absorbs network jitter,
 *  3. [endTurn] on `tts.done` — once the queue drains and the track finishes
 *     playing, [onTurnDrained] fires so the session can resume listening.
 *
 * [stopNow] (barge-in / `interrupted`) cuts playback immediately. Routing through
 * `USAGE_VOICE_COMMUNICATION` couples render with the VOICE_COMMUNICATION capture
 * path so the platform AEC can subtract it (PRD §5.4).
 */
class AudioPlayer(
    private val onTurnDrained: () -> Unit
) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var active = false
    @Volatile private var endOfTurn = false
    private var writer: Thread? = null

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var framesWritten = 0L

    val isActive: Boolean get() = active

    fun beginTurn(sampleRate: Int) {
        stopNow() // tear down any previous turn cleanly

        val sr = if (sampleRate > 0) sampleRate else ApiConfig.DEFAULT_TTS_SAMPLE_RATE
        val minBuffer = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size for sr=$sr")
            return
        }

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sr)) // ~0.5s headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = newTrack
        framesWritten = 0L
        endOfTurn = false
        active = true
        queue.clear()
        writer = thread(name = "tts-writer", priority = Thread.MAX_PRIORITY) {
            writerLoop(newTrack)
        }
    }

    /** Adds a TTS audio chunk to the playback queue. */
    fun enqueue(pcm: ByteArray) {
        if (active && pcm.isNotEmpty()) queue.offer(pcm)
    }

    /** Signals that all audio for the turn has been received. */
    fun endTurn() {
        endOfTurn = true
        queue.offer(ByteArray(0)) // nudge the writer out of poll()
    }

    /** Stops and releases playback immediately (barge-in / interrupt). */
    fun stopNow() {
        active = false
        writer?.let {
            it.interrupt()
            runCatching { it.join(300) }
        }
        writer = null
        queue.clear()
        track?.let { t ->
            runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        track = null
    }

    private fun writerLoop(t: AudioTrack) {
        var started = false
        val preBuffer = ArrayDeque<ByteArray>()
        try {
            while (active) {
                val item = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (item != null && item.isNotEmpty()) {
                    if (!started) {
                        preBuffer.addLast(item)
                        if (preBuffer.size >= PREBUFFER_CHUNKS) {
                            t.play()
                            started = true
                            while (preBuffer.isNotEmpty()) writeChunk(t, preBuffer.removeFirst())
                        }
                    } else {
                        writeChunk(t, item)
                    }
                }

                if (endOfTurn && queue.isEmpty()) {
                    if (!started && preBuffer.isNotEmpty()) {
                        // Short turn that never reached the pre-buffer threshold.
                        t.play()
                        started = true
                        while (preBuffer.isNotEmpty()) writeChunk(t, preBuffer.removeFirst())
                    }
                    if (started) awaitDrain(t)
                    active = false
                    onTurnDrained()
                }
            }
        } catch (_: InterruptedException) {
            // stopNow() — exit quietly
        } catch (e: Exception) {
            Log.w(TAG, "writer loop error", e)
        }
    }

    private fun writeChunk(t: AudioTrack, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size && active) {
            val written = t.write(bytes, offset, bytes.size - offset)
            if (written <= 0) break
            offset += written
            framesWritten += written / BYTES_PER_FRAME
        }
    }

    /** Polls the playback head until every written frame has been rendered. */
    private fun awaitDrain(t: AudioTrack) {
        while (active) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= framesWritten) break
            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        runCatching { t.stop() }
    }

    private companion object {
        const val TAG = "AudioPlayer"
        const val PREBUFFER_CHUNKS = 2 // ~2 chunks before play() (PRD §5.3)
        const val BYTES_PER_FRAME = 2 // mono PCM-16
        const val POLL_MS = 20L
        const val DRAIN_POLL_MS = 10L
    }
}

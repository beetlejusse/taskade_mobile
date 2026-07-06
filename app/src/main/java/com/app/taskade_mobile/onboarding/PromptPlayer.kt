package com.app.taskade_mobile.onboarding

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.app.taskade_mobile.data.remote.ApiService
import com.app.taskade_mobile.data.remote.dto.TtsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Speaks scripted onboarding prompts in DIVYA's voice.
 *
 * Fetches a one-shot WAV clip from the backend `POST /tts` (same Sarvam voice as
 * the live assistant), plays it via [MediaPlayer], and suspends until playback
 * finishes — so the onboarding state machine can `speak(...)` then move on.
 *
 * Best-effort: any failure (network/synthesis/playback) resolves immediately
 * rather than blocking the flow — the prompt text is always on screen too.
 */
class PromptPlayer(
    private val api: ApiService,
    context: Context,
) {
    private val cacheDir: File = context.cacheDir
    @Volatile private var player: MediaPlayer? = null

    /** Synthesize + play [text], suspending until it finishes (or fails). */
    suspend fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        val file = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = api.synthesize(TtsRequest(clean)).bytes()
                File.createTempFile("prompt_", ".wav", cacheDir).apply { writeBytes(bytes) }
            }.getOrElse {
                Log.w(TAG, "prompt synth failed: ${it.message}")
                null
            }
        } ?: return

        playFile(file)
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        stop()
        val mp = MediaPlayer()
        player = mp

        fun finish() {
            runCatching { file.delete() }
            if (cont.isActive) cont.resume(Unit)
        }

        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setOnCompletionListener { finish() }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            finish()
            true
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer setup failed: ${e.message}")
            finish()
        }

        cont.invokeOnCancellation {
            stop()
            runCatching { file.delete() }
        }
    }

    /** Stop and release any current playback (call on cancel / activity destroy). */
    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
    }

    private companion object {
        const val TAG = "OnboardingVoice"
    }
}

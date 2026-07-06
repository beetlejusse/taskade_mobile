package com.app.taskade_mobile.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin coroutine wrapper over Android's on-device [SpeechRecognizer] for
 * capturing a single short answer during onboarding.
 *
 * [listen] suspends until the recognizer returns a result, errors, or is
 * cancelled, resolving to the best transcript (or null). The caller shows the
 * result in an editable field for confirmation — voice is an assist, never the
 * sole source of truth (STT mangles names), so a null just means "type it".
 *
 * Must be created and used on the main thread. Call [destroy] when done.
 */
class VoiceInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listen(languageTag: String? = null): String? =
        suspendCancellableCoroutine { cont ->
            if (!isAvailable()) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr

            var settled = false
            fun settle(value: String?) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(value)
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    settle(list?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() })
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "speech error code=$error")
                    settle(null)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                if (!languageTag.isNullOrBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                }
            }

            cont.invokeOnCancellation {
                runCatching { sr.cancel() }
                runCatching { sr.destroy() }
            }

            runCatching { sr.startListening(intent) }
                .onFailure {
                    Log.w(TAG, "startListening failed: ${it.message}")
                    settle(null)
                }
        }

    /** Cancels any in-flight recognition and releases the recognizer. */
    fun destroy() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private companion object {
        const val TAG = "OnboardingVoice"
    }
}

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
            // Best hypothesis heard so far. Short answers like "8 am" frequently come
            // back as ERROR_NO_MATCH / a premature timeout even though the recognizer
            // DID transcribe them into a partial result — so we keep the latest partial
            // and fall back to it on error instead of losing the answer entirely.
            var lastPartial: String? = null
            fun settle(value: String?) {
                if (settled) return
                settled = true
                if (cont.isActive) cont.resume(value?.trim()?.takeIf { it.isNotEmpty() })
            }

            fun firstOf(bundle: Bundle?): String? =
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    settle(firstOf(results) ?: lastPartial)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    firstOf(partialResults)?.let { lastPartial = it }
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "speech error code=$error (lastPartial=$lastPartial)")
                    // Salvage a heard-but-not-finalized short utterance.
                    settle(lastPartial)
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                // Keep partials so a short answer isn't lost to a NO_MATCH final.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Some OEM recognizers refuse to bind without the caller's package.
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Give a one-word answer room to breathe: don't end the session in the
                // first moment, and allow a longer trailing silence before finalizing.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
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

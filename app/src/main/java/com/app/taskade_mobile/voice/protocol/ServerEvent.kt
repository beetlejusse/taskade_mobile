package com.app.taskade_mobile.voice.protocol

import com.app.taskade_mobile.core.ApiConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed model of every server → client frame on `/ws/voice` (PRD §4.2).
 *
 * JSON **text** frames are decoded by [parse]; raw **binary** frames (PCM-16 TTS
 * audio) are surfaced by the socket client as [AudioChunk] and never pass through
 * this parser. Unknown `type`s become [Unknown] rather than throwing, so future
 * server events can't crash an old client.
 */
sealed interface ServerEvent {

    /** `processing` — turn accepted; the brain is thinking. */
    data class Processing(val stage: String?) : ServerEvent

    /** `stt.interim` — live partial transcript (ghost text). */
    data class SttInterim(val text: String) : ServerEvent

    /** `stt.final` — a finalized transcript segment (the turn may continue). */
    data class SttFinal(val text: String) : ServerEvent

    /** `stt.result` — end-of-turn transcript → commit the user bubble. */
    data class SttResult(val text: String) : ServerEvent

    /** `stt.reconnecting` — Deepgram dropped and is reconnecting. */
    data object SttReconnecting : ServerEvent

    /** `llm.token` — a streamed chunk of the assistant's reply text. */
    data class LlmToken(val text: String) : ServerEvent

    /** `tool.start` — a tool call started. */
    data class ToolStart(val name: String) : ServerEvent

    /** `tool.result` — a tool finished; refetch `GET /tasks`. */
    data class ToolResult(val name: String, val ok: Boolean, val summary: String?) : ServerEvent

    /** `metadata` — structured task details for a dedicated card (recent_changes §12). */
    data class Metadata(val tasks: List<MetadataTask>) : ServerEvent

    /** `llm.done` — the full reply text (commit on [TtsDone]). */
    data class LlmDone(val text: String) : ServerEvent

    /** `tts.start` — audio for the turn is about to stream; reset the player. */
    data class TtsStart(val sampleRate: Int) : ServerEvent

    /** A raw PCM-16 audio chunk (binary frame). */
    data class AudioChunk(val pcm: ByteArray) : ServerEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is AudioChunk && pcm.contentEquals(other.pcm))
        override fun hashCode(): Int = pcm.contentHashCode()
    }

    /** `tts.done` — all audio sent; resume listening once playback drains. */
    data object TtsDone : ServerEvent

    /** `error` — pipeline error; resume listening. */
    data class Error(val message: String) : ServerEvent

    /** `interrupted` — server acknowledged barge-in; stop playback, don't resume. */
    data object Interrupted : ServerEvent

    /** `history_cleared` — conversation history reset. */
    data object HistoryCleared : ServerEvent

    /** `pong` — heartbeat reply. */
    data object Pong : ServerEvent

    /** Any unrecognized text frame. */
    data class Unknown(val type: String?, val raw: String) : ServerEvent

    companion object {
        /** Decodes a JSON text frame into a [ServerEvent]; never throws. */
        fun parse(json: Json, text: String): ServerEvent {
            return try {
                val obj = json.parseToJsonElement(text) as? JsonObject
                    ?: return Unknown(null, text)
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "processing" -> Processing(obj.str("stage"))
                    "stt.interim" -> SttInterim(obj.str("text").orEmpty())
                    "stt.final" -> SttFinal(obj.str("text").orEmpty())
                    "stt.result" -> SttResult(obj.str("text").orEmpty())
                    "stt.reconnecting" -> SttReconnecting
                    "llm.token" -> LlmToken(obj.str("text").orEmpty())
                    "tool.start" -> ToolStart(obj.str("name").orEmpty())
                    "tool.result" -> ToolResult(
                        name = obj.str("name").orEmpty(),
                        ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                        summary = obj.str("summary")
                    )
                    "metadata" -> Metadata(parseMetadata(obj))
                    "llm.done" -> LlmDone(obj.str("text").orEmpty())
                    "tts.start" -> TtsStart(
                        obj["sampleRate"]?.jsonPrimitive?.intOrNull
                            ?: ApiConfig.DEFAULT_TTS_SAMPLE_RATE
                    )
                    "tts.done" -> TtsDone
                    "error" -> Error(obj.str("message") ?: "Unknown error")
                    "interrupted" -> Interrupted
                    "history_cleared" -> HistoryCleared
                    "pong" -> Pong
                    else -> Unknown(type, text)
                }
            } catch (_: Exception) {
                Unknown(null, text)
            }
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        /** Parses the `metadata` event's `data.tasks[]` into typed [MetadataTask]s. */
        private fun parseMetadata(obj: JsonObject): List<MetadataTask> {
            val data = obj["data"] as? JsonObject ?: return emptyList()
            val tasks = data["tasks"] as? JsonArray ?: return emptyList()
            return tasks.mapNotNull { element ->
                val t = element as? JsonObject ?: return@mapNotNull null
                val title = t.str("title").orEmpty()
                val dueAt = t.str("due_at")
                val summary = t.str("summary")
                val links = (t["links"] as? JsonArray).orEmpty().mapNotNull(::parseLink)
                if (title.isBlank() && dueAt == null && summary == null && links.isEmpty()) {
                    null
                } else {
                    MetadataTask(title, dueAt, summary, links)
                }
            }
        }

        /** A link is either a bare URL string or a `{url, label}` object. */
        private fun parseLink(element: Any?): MetadataLink? = when (element) {
            is JsonObject -> {
                val url = element.str("url")
                if (url.isNullOrBlank()) null
                else MetadataLink(url, element.str("label")?.takeIf { it.isNotBlank() } ?: url)
            }
            is JsonPrimitive -> {
                val s = element.contentOrNull
                if (s.isNullOrBlank()) null else MetadataLink(s, s)
            }
            else -> null
        }

        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    }
}

package com.app.taskade_mobile.voice.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Client → server JSON control frames (PRD §4.1). Audio travels as raw binary
 * frames, sent directly by the socket client and not modeled here.
 *
 * Each variant serializes to `{"type": "..."}` (plus any payload). Use
 * [encode] to turn one into the text frame to send.
 */
@Serializable
sealed class ClientMessage {

    /** Local VAD detected speech onset (informational). */
    @Serializable
    @SerialName("speech.start")
    data object SpeechStart : ClientMessage()

    /** Barge-in: the user is talking over the assistant. */
    @Serializable
    @SerialName("interrupt")
    data object Interrupt : ClientMessage()

    /** Clear the conversation history. */
    @Serializable
    @SerialName("clear_history")
    data object ClearHistory : ClientMessage()

    /** Push a freshly-resolved location into the live session. */
    @Serializable
    @SerialName("location")
    data class Location(val location: String) : ClientMessage()

    /** Keep-alive heartbeat. */
    @Serializable
    @SerialName("ping")
    data object Ping : ClientMessage()

    companion object {
        /**
         * A dedicated [Json] with `type` as the polymorphic discriminator so the
         * sealed hierarchy serializes to the exact `{"type": ...}` wire shape the
         * backend expects.
         */
        val codec: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
        }

        fun encode(message: ClientMessage): String = codec.encodeToString(message)
    }
}

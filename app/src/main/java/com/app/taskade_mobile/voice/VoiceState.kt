package com.app.taskade_mobile.voice

import com.app.taskade_mobile.voice.protocol.MetadataTask

/**
 * The client state machine (PRD §9), mirroring the web client's `status`
 * transitions:
 *
 * `Idle → Connecting → Listening → Recording → Processing → Speaking → Listening`
 * with barge-in jumping `Speaking → Recording`.
 */
enum class VoiceState {
    /** No session / socket closed. */
    Idle,

    /** Socket opening (or reconnecting). */
    Connecting,

    /** Connected, streaming mic, waiting for speech. */
    Listening,

    /** User is speaking; transcript is coming in. */
    Recording,

    /** End-of-turn reached; the brain is thinking / tools running. */
    Processing,

    /** Assistant audio is playing back. */
    Speaking
}

/**
 * One-shot UI events the [VoiceSessionManager] emits for the conversation screen
 * to render. The screen owns how these map onto chat bubbles / status — the
 * manager never touches views.
 */
sealed interface VoiceUiEvent {
    /** Live partial transcript (ghost user bubble). */
    data class Interim(val text: String) : VoiceUiEvent

    /** End-of-turn transcript → commit the user bubble. */
    data class UserFinal(val text: String) : VoiceUiEvent

    /** A streamed chunk of the assistant's reply (append to the live agent bubble). */
    data class AssistantDelta(val text: String) : VoiceUiEvent

    /** The assistant's full reply text (finalize the agent bubble). */
    data class AssistantFinal(val text: String) : VoiceUiEvent

    /** A tool call started ([running] = true) or finished ([running] = false). */
    data class Tool(val name: String, val running: Boolean, val ok: Boolean = true) : VoiceUiEvent

    /** A `tool.result` arrived → the task list may have changed; refetch. */
    data object TasksChanged : VoiceUiEvent

    /** Structured task details for a dedicated card (recent_changes §12). */
    data class Metadata(val tasks: List<MetadataTask>) : VoiceUiEvent

    /** Conversation history was cleared server-side. */
    data object HistoryCleared : VoiceUiEvent

    /** A recoverable pipeline/connection error to surface. */
    data class Error(val message: String) : VoiceUiEvent
}

package com.app.taskade_mobile.voice.protocol

/**
 * Structured task metadata delivered on the dedicated `metadata` WebSocket channel
 * (backend recent_changes §12). The backend emits this only when the user asks
 * about ONE specific, already-tracked task, so the durable details (links, due
 * date, findings) get their own card instead of being recited in speech.
 */
data class MetadataTask(
    val title: String,
    val dueAt: String?,
    val summary: String?,
    val links: List<MetadataLink>
)

/** A link may arrive as a bare URL string or a `{url, label}` object. */
data class MetadataLink(
    val url: String,
    val label: String
)

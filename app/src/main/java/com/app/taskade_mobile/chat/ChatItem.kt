package com.app.taskade_mobile.chat

import com.app.taskade_mobile.voice.protocol.MetadataTask
import java.util.concurrent.atomic.AtomicLong

/**
 * One row in the conversation list — either a chat [ChatMessage] bubble or a
 * [MetadataItem] details card. A shared, stable [id] lets the adapter use stable
 * ids + minimal diffs.
 */
sealed interface ChatItem {
    val id: Long
}

/** Process-wide monotonic id source shared by every [ChatItem]. */
internal object ChatIds {
    private val counter = AtomicLong(0L)
    fun next(): Long = counter.incrementAndGet()
}

/** A structured task-details card (backend `metadata` channel, recent_changes §12). */
data class MetadataItem(
    val tasks: List<MetadataTask>,
    override val id: Long = ChatIds.next()
) : ChatItem

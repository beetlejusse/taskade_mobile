package com.app.taskade_mobile.chat

/**
 * A single chat message. [fromUser] decides alignment and styling: user messages
 * sit on the right with the user avatar, DIVYA's replies on the left with the
 * agent avatar.
 *
 * [text] is mutable so a streaming reply (or a live interim transcript) can be
 * updated in place via `notifyItemChanged`. [id] is a stable identity (shared with
 * every [ChatItem]) used to find and update the "live" bubble while a turn streams.
 */
data class ChatMessage(
    var text: String,
    val fromUser: Boolean,
    override val id: Long = ChatIds.next()
) : ChatItem

package com.app.taskade_mobile.chat

/**
 * A single chat message. [fromUser] decides alignment and styling: user messages
 * sit on the right with the user avatar, DIVYA's replies on the left with the
 * agent avatar. Static placeholder data for now; this will be driven by a real
 * conversation source in a later iteration.
 */
data class ChatMessage(
    val text: String,
    val fromUser: Boolean
)

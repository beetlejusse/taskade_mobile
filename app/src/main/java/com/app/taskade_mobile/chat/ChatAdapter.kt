package com.app.taskade_mobile.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.R

/**
 * Renders the conversation, choosing a left-aligned (agent) or right-aligned
 * (user) row per message. Avatars are baked into the row layouts as placeholders
 * for now; real user/agent images get bound here once chat goes dynamic.
 */
class ChatAdapter(
    private val messages: List<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    override fun getItemViewType(position: Int): Int =
        if (messages[position].fromUser) TYPE_USER else TYPE_AGENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_agent
        val bubbleId = if (viewType == TYPE_USER) R.id.userBubble else R.id.agentBubble
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view, bubbleId)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(
        itemView: View,
        bubbleId: Int
    ) : RecyclerView.ViewHolder(itemView) {
        private val bubble: TextView = itemView.findViewById(bubbleId)

        fun bind(message: ChatMessage) {
            bubble.text = message.text
        }
    }

    private companion object {
        const val TYPE_AGENT = 0
        const val TYPE_USER = 1
    }
}

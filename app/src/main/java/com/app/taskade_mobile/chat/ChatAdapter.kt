package com.app.taskade_mobile.chat

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.R
import com.app.taskade_mobile.ui.Motion
import com.app.taskade_mobile.voice.protocol.MetadataLink
import com.app.taskade_mobile.voice.protocol.MetadataTask
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Renders the conversation: agent / user chat bubbles plus structured task-detail
 * [MetadataItem] cards. The agent avatar is a static mark; the user avatar is the
 * signed-in user's photo when available ([setUserAvatar]), otherwise a placeholder.
 */
class ChatAdapter(
    private val items: List<ChatItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var userAvatar: Bitmap? = null

    // Entrance guard: only positions beyond this pop in. Streamed text updates
    // (notifyItemChanged) rebind the SAME position, so a bubble animates exactly
    // once — when it first appears — and never re-pops mid-stream.
    private var lastAnimatedPosition = -1

    init {
        setHasStableIds(true)
    }

    fun setUserAvatar(bitmap: Bitmap) {
        userAvatar = bitmap
        notifyItemRangeChanged(0, itemCount)
    }

    /** Call when the conversation is cleared so fresh messages animate again. */
    fun resetEntranceState() {
        lastAnimatedPosition = -1
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemViewType(position: Int): Int = when (val item = items[position]) {
        is MetadataItem -> TYPE_METADATA
        is ChatMessage -> if (item.fromUser) TYPE_USER else TYPE_AGENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_METADATA) {
            MetadataViewHolder(inflater.inflate(R.layout.item_chat_metadata, parent, false))
        } else {
            val isUser = viewType == TYPE_USER
            val layout = if (isUser) R.layout.item_chat_user else R.layout.item_chat_agent
            val bubbleId = if (isUser) R.id.userBubble else R.id.agentBubble
            val avatarId = if (isUser) R.id.userAvatar else R.id.agentAvatar
            val view = inflater.inflate(layout, parent, false)
            MessageViewHolder(view, bubbleId, view.findViewById(avatarId), isUser)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatMessage -> (holder as MessageViewHolder).bind(item, userAvatar)
            is MetadataItem -> (holder as MetadataViewHolder).bind(item)
        }
        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position
            popIn(holder.itemView)
        } else {
            // Recycled views can carry transient values from an interrupted entrance.
            Motion.resetTransforms(holder.itemView)
        }
    }

    /** WhatsApp/iMessage-style arrival: the new bubble lifts in with a soft pop. */
    private fun popIn(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 16f * view.resources.displayMetrics.density
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(
        itemView: View,
        bubbleId: Int,
        private val avatar: ImageView,
        private val isUser: Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val bubble: TextView = itemView.findViewById(bubbleId)
        private val density = itemView.resources.displayMetrics.density

        init {
            if (isUser) {
                avatar.clipToOutline = true
                avatar.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setOval(0, 0, v.width, v.height)
                    }
                }
            }
        }

        fun bind(message: ChatMessage, userAvatar: Bitmap?) {
            bubble.text = message.text
            if (isUser) bindUserAvatar(userAvatar)
        }

        private fun bindUserAvatar(bitmap: Bitmap?) {
            if (bitmap != null) {
                avatar.setPadding(0, 0, 0, 0)
                avatar.imageTintList = null
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.setImageBitmap(bitmap)
            } else {
                val pad = (7 * density).toInt()
                avatar.setPadding(pad, pad, pad, pad)
                avatar.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.muted))
                avatar.scaleType = ImageView.ScaleType.FIT_CENTER
                avatar.setImageResource(R.drawable.ic_person)
            }
        }
    }

    /** Populates the details card; the number of tasks/links varies, so build it live. */
    class MetadataViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.metaContainer)
        private val font = ResourcesCompat.getFont(itemView.context, R.font.jakarta)
        private val density = itemView.resources.displayMetrics.density

        fun bind(item: MetadataItem) {
            container.removeAllViews()
            item.tasks.forEachIndexed { index, task ->
                if (index > 0) container.addView(spacer(12))
                task.title.takeIf { it.isNotBlank() }
                    ?.let { container.addView(line("📋 $it", 15f, R.color.ink, bold = true)) }
                task.dueAt?.takeIf { it.isNotBlank() }
                    ?.let { container.addView(line("📅 Due: ${formatDue(it)}", 13f, R.color.muted)) }
                task.summary?.takeIf { it.isNotBlank() }
                    ?.let { container.addView(line("📝 $it", 13f, R.color.ink)) }
                task.links.forEach { container.addView(linkLine(it)) }
            }
        }

        private fun line(text: String, sizeSp: Float, colorRes: Int, bold: Boolean = false): TextView =
            TextView(itemView.context).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(ContextCompat.getColor(itemView.context, colorRes))
                typeface = font
                if (bold) setTypeface(font, android.graphics.Typeface.BOLD)
                setLineSpacing(0f, 1.15f)
            }

        private fun linkLine(link: MetadataLink): TextView =
            line("🔗 ${link.label}", 13f, R.color.info).apply {
                paint.isUnderlineText = true
                isClickable = true
                setOnClickListener {
                    runCatching {
                        itemView.context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                        )
                    }
                }
            }

        private fun spacer(heightDp: Int): View = View(itemView.context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
        }

        private fun formatDue(iso: String): String = try {
            val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso.take(19))
            if (parsed != null) SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(parsed) else iso
        } catch (_: Exception) {
            iso
        }

        private fun dp(value: Int): Int = (value * density).toInt()

        private companion object {
            const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
            const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        }
    }

    private companion object {
        const val TYPE_AGENT = 0
        const val TYPE_USER = 1
        const val TYPE_METADATA = 2
    }
}

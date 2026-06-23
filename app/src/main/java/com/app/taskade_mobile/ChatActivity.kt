package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.chat.ChatAdapter
import com.app.taskade_mobile.chat.ChatMessage
import com.app.taskade_mobile.ui.DockView
import eightbitlab.com.blurview.BlurTarget

/**
 * DIVYA — the primary chat screen and the first destination after login.
 *
 * A fixed header and the persistent glassmorphism [DockView] bracket a scrolling
 * conversation that passes beneath the dock. The dock's center button toggles a
 * text composer (the voice/keyboard input); chat content is static placeholder
 * data for this prototype phase.
 */
class ChatActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }

    private lateinit var chatList: RecyclerView
    private lateinit var composerBar: View
    private lateinit var composerInput: EditText
    private lateinit var dock: DockView

    private val messages = samplePlaceholderConversation().toMutableList()
    private val adapter = ChatAdapter(messages)

    private var headerBaseTopPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat)

        // Gate: no valid session -> back to login.
        if (!authManager.isAuthenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        chatList = findViewById(R.id.chatList)
        composerBar = findViewById(R.id.composerBar)
        composerInput = findViewById(R.id.composerInput)
        dock = findViewById(R.id.dock)

        setupChatList()
        setupComposer()
        applyWindowInsets()

        // Live frosted-glass: blur the conversation behind the dock.
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.CHAT)
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.TASKS -> startActivity(Intent(this, TasksActivity::class.java))
                DockView.Item.PROFILE -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(R.anim.slide_up, R.anim.stay)
                }
                else -> Unit // Chat is current; Calendar is a placeholder.
            }
        }
        // The keyboard chip drives the text composer; voice (orb) stays static for now.
        dock.setOnKeyboardToggleListener { keyboard -> if (keyboard) showComposer() else hideComposer() }
    }

    private fun setupChatList() {
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter
    }

    private fun setupComposer() {
        findViewById<ImageButton>(R.id.composerSend).setOnClickListener { sendTypedMessage() }
    }

    /** Prototype: echoes the typed text as a user bubble; no AI reply yet. */
    private fun sendTypedMessage() {
        val text = composerInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        messages.add(ChatMessage(text = text, fromUser = true))
        adapter.notifyItemInserted(messages.lastIndex)
        chatList.smoothScrollToPosition(messages.lastIndex)
        composerInput.text?.clear()
    }

    private fun showComposer() {
        composerBar.apply {
            alpha = 0f
            translationY = dpF(24f)
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        composerInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(composerInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideComposer() {
        composerBar.animate()
            .alpha(0f)
            .translationY(dpF(24f))
            .setDuration(180)
            .withEndAction { composerBar.visibility = View.GONE }
            .start()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(composerInput.windowToken, 0)
        composerInput.clearFocus()
    }

    /**
     * Edge-to-edge: pad the header below the status bar, float the dock above the
     * nav bar/keyboard, and reserve room so the last message clears the dock.
     */
    private fun applyWindowInsets() {
        val header = findViewById<View>(R.id.chatHeader)
        headerBaseTopPadding = header.paddingTop
        val dockBaseMargin = dp(18)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)

            header.updatePadding(top = headerBaseTopPadding + bars.top)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dockBaseMargin + bottom
            }
            // Keep the last bubble clear of the floating dock (incl. the chip above it).
            chatList.updatePadding(bottom = dp(152) + bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        fun samplePlaceholderConversation(): List<ChatMessage> = listOf(
            ChatMessage("Hi, I'm DIVYA — your personal AI assistant. How can I help you today?", fromUser = false),
            ChatMessage("Can you organize my tasks for this week?", fromUser = true),
            ChatMessage("Absolutely. I've grouped everything by priority and deadline. Want me to walk you through them?", fromUser = false),
            ChatMessage("Yes, and remind me about the design review.", fromUser = true),
            ChatMessage("Done — I'll remind you at 5 PM today. Anything else on your mind?", fromUser = false)
        )
    }
}

package com.app.taskade_mobile

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.chat.ChatAdapter
import com.app.taskade_mobile.chat.ChatItem
import com.app.taskade_mobile.chat.ChatMessage
import com.app.taskade_mobile.chat.MetadataItem
import com.app.taskade_mobile.core.ImageLoader
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.core.SettingsStore
import com.app.taskade_mobile.location.LocationProvider
import com.app.taskade_mobile.ui.DockView
import com.app.taskade_mobile.voice.VoiceForegroundService
import com.app.taskade_mobile.voice.VoiceSessionManager
import com.app.taskade_mobile.voice.VoiceState
import com.app.taskade_mobile.voice.VoiceUiEvent
import com.app.taskade_mobile.voice.protocol.MetadataTask
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * DIVYA — the primary chat screen and the first destination after login.
 *
 * The visual scaffolding (header, glassmorphism [DockView], scrolling conversation,
 * blur, edge-to-edge insets) is unchanged. What's new is the live
 * backend wiring: the dock's center voice orb toggles a full-duplex voice session
 * hosted by [VoiceForegroundService], whose [VoiceSessionManager] streams the live
 * transcript, the assistant's streamed reply, tool activity and status into this
 * screen. On open it also loads the personalized greeting and delivers due
 * reminders (PRD §6 / §9).
 */
class ChatActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }
    private val locationProvider by lazy { LocationProvider(this) }

    private lateinit var chatList: RecyclerView
    private lateinit var dock: DockView
    private lateinit var agentStatus: TextView

    private val messages = mutableListOf<ChatItem>()
    private val adapter = ChatAdapter(messages)

    private var headerBaseTopPadding = 0

    // Gentle infinite "breathing" on the header's presence dot; running only while
    // the screen is started so it costs nothing in the background.
    private var dotPulse: ObjectAnimator? = null

    // ---- voice session binding ----
    private var boundManager: VoiceSessionManager? = null
    private var voiceActive = false
    private var collectJob: Job? = null

    // Identity of the in-progress (live) bubbles while a turn streams in.
    private var liveUserId: Long? = null
    private var liveAgentId: Long? = null
    private val liveAgentRaw = StringBuilder()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val manager = (service as? VoiceForegroundService.LocalBinder)?.manager ?: return
            boundManager = manager
            observeSession(manager)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundManager = null
        }
    }

    /**
     * Requests ONLY the mic (single-permission contract): voice must never be gated
     * on anything else. The old multi-permission version bundled POST_NOTIFICATIONS
     * and read the result map — when the mic was already granted and only the
     * (denied) notification permission was re-requested, the map had no RECORD_AUDIO
     * entry and the callback treated that as "mic denied", silently bricking the orb.
     * The decision below also re-checks the REAL permission state, so a granted mic
     * always starts the session no matter what the result delivered.
     */
    private val voicePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            when {
                hasMicPermission() -> startVoiceSession()
                // Rationale=false right after a denial means the system auto-denied
                // without showing any dialog ("don't ask again"). A silent bail here
                // is what reads as "the orb does nothing" — route to app settings.
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                    showMicSettingsDialog()
                else -> toast(getString(R.string.voice_permission_required))
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) resolveAndPersistLocation()
        }

    // Android 13+ runtime notification permission. The FCM token is registered
    // regardless of the outcome, so reminders work as soon as it's granted.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_chat)

        // Gate: no valid session -> back to login.
        if (!authManager.isAuthenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        ServiceLocator.init(this)

        chatList = findViewById(R.id.chatList)
        dock = findViewById(R.id.dock)
        agentStatus = findViewById(R.id.agentStatus)

        setupChatList()
        applyWindowInsets()

        // Live frosted-glass: blur the conversation behind the dock.
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.CHAT)
        // Leaving chat tucks the voice orb into the dock WHILE the transition
        // crossfades (parallel, so navigation feels instant) — the destination's
        // orb-less nav pill then reads as the same dock continuing, not a hard
        // swap. The orb pops back in onResume.
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.TASKS -> {
                    dock.condense()
                    startActivity(Intent(this, TasksActivity::class.java))
                    overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
                }
                DockView.Item.PROFILE -> {
                    dock.condense()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(R.anim.slide_up, R.anim.stay)
                }
                else -> Unit // Chat is current; Calendar is a placeholder.
            }
        }
        // The center orb toggles the full-duplex voice session.
        dock.setOnVoiceClickListener { toggleVoiceSession() }

        gateThenInit()
    }

    /**
     * Provisions the user's record and gates on onboarding before revealing chat.
     *
     * Syncs the session (persists name/email/timezone/locale server-side — on
     * fresh login AND on a resumed session), then:
     *  - if the profile isn't onboarded yet → route to [OnboardingActivity];
     *  - otherwise → reveal the chat and run the usual startup loads.
     * The chat is held invisible until this resolves so onboarding never flashes
     * the chat first. A sync failure never traps the user — it just shows chat
     * (they'll be gated again on the next successful launch).
     */
    private fun gateThenInit() {
        val root = findViewById<View>(R.id.chatRoot)
        val store = SettingsStore.getInstance(this)
        val timezone = java.util.TimeZone.getDefault().id
        val locale = resources.configuration.locales[0].toLanguageTag()

        // Fast path: onboarding is a one-way flip, so once we've recorded it locally we
        // reveal the chat INSTANTLY and heal the session in the background — no blank
        // wait on a network round-trip (the cause of the old blank launch screens).
        if (store.onboardingComplete) {
            root.visibility = View.VISIBLE
            lifecycleScope.launch {
                ServiceLocator.profileRepository.syncSession(timezone, locale)
                    .onFailure { android.util.Log.w("TaskadeSync", "session sync failed: ${it.message}") }
            }
            revealAndLoad(root, alreadyVisible = true)
            return
        }

        // First run: we must wait for the server's verdict before choosing
        // chat-vs-onboarding, so hold the reveal until sync resolves.
        root.visibility = View.INVISIBLE
        lifecycleScope.launch {
            val profile = ServiceLocator.profileRepository.syncSession(timezone, locale)
                .onFailure { android.util.Log.w("TaskadeSync", "session sync failed: ${it.message}") }
                .getOrNull()

            if (profile != null && !profile.onboardingComplete) {
                startActivity(OnboardingActivity.intent(this@ChatActivity, profile.displayName))
                finish()
                return@launch
            }
            // Onboarded (or sync failed — never trap the user). Remember a confirmed
            // onboarded state so every later launch takes the instant path above.
            if (profile?.onboardingComplete == true) store.onboardingComplete = true
            revealAndLoad(root, alreadyVisible = false)
        }
    }

    /** Reveals the chat (if not already) and runs the usual startup loads. */
    private fun revealAndLoad(root: View, alreadyVisible: Boolean) {
        if (!alreadyVisible) root.visibility = View.VISIBLE
        loadEngagement()
        ensureLocationCaptured()
        loadUserAvatar()
        registerForPush()
    }

    /**
     * Ensures reminder pushes can reach this device: requests the Android 13+
     * notification permission (ONCE, ever — repeat prompts nag users into permanent
     * denial, and re-requesting a permanently-denied permission auto-fails with no
     * dialog), then registers the current FCM token with the backend. Best-effort —
     * failures never affect the chat screen, and notifications never gate voice.
     */
    private fun registerForPush() {
        val store = SettingsStore.getInstance(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !store.notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            store.notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            ServiceLocator.deviceTokenRepository.registerCurrentToken()
                .onSuccess { android.util.Log.i("TaskadeFCM", "device token registered with backend OK") }
                .onFailure { android.util.Log.w("TaskadeFCM", "push registration FAILED: ${it.message}", it) }
        }
    }

    /** Loads the signed-in user's photo (Auth0) for the chat user-avatar, async. */
    private fun loadUserAvatar() {
        authManager.getCredentials(
            onSuccess = { creds ->
                authManager.getProfile(
                    accessToken = creds.accessToken,
                    onSuccess = { profile ->
                        val url = profile.pictureURL
                        if (!url.isNullOrBlank()) {
                            lifecycleScope.launch {
                                ImageLoader.load(ServiceLocator.apiProvider.httpClient, url)
                                    ?.let { adapter.setUserAvatar(it) }
                            }
                        }
                    },
                    onFailure = { }
                )
            },
            onFailure = { }
        )
    }

    override fun onStart() {
        super.onStart()
        startDotPulse()
    }

    override fun onResume() {
        super.onResume()
        // Back from another screen: the orb pops back out of the dock. No-ops on
        // a fresh launch (nothing was condensed). The auth-gate redirect finishes
        // in onCreate before `dock` is assigned, hence the initialization check.
        if (::dock.isInitialized) dock.expand()
    }

    override fun onStop() {
        stopDotPulse()
        super.onStop()
    }

    override fun onDestroy() {
        if (voiceActive) {
            stopVoiceSession()
        }
        super.onDestroy()
    }

    /** Subtle live-presence breathing on the header's online dot. */
    private fun startDotPulse() {
        if (dotPulse != null) return
        val dot = findViewById<View>(R.id.onlineDot) ?: return
        dotPulse = ObjectAnimator.ofPropertyValuesHolder(
            dot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f)
        ).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        dotPulse?.cancel()
        dotPulse = null
        findViewById<View>(R.id.onlineDot)?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
    }

    // ---- voice session control --------------------------------------------

    private fun toggleVoiceSession() {
        if (voiceActive) stopVoiceSession() else requestVoicePermissions()
    }

    private fun requestVoicePermissions() {
        if (hasMicPermission()) {
            startVoiceSession()
            return
        }
        // Deliberately NOT bundling POST_NOTIFICATIONS here: it is optional for
        // voice (the foreground service starts fine without it — its "listening"
        // notification is simply not shown) and is already requested once at app
        // start by registerForPush(). Re-requesting a permanently-denied permission
        // auto-fails with no dialog, which used to kill this flow on every tap.
        voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Shown when the mic permission is permanently denied: the system no longer
     * displays any dialog, so without this the orb would do nothing visible at all.
     */
    private fun showMicSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_permission_denied_title)
            .setMessage(R.string.voice_permission_denied_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.voice_permission_open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
            .show()
    }

    private fun startVoiceSession() {
        if (voiceActive) return
        voiceActive = true
        try {
            VoiceForegroundService.start(this)
            bindService(
                Intent(this, VoiceForegroundService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (t: Throwable) {
            // Some OEM/Android-version combinations refuse to start the voice service
            // (e.g. foreground-service limits). Degrade gracefully instead of crashing.
            android.util.Log.e("ChatActivity", "Could not start voice session", t)
            voiceActive = false
            dock.setVoiceState(DockView.VoicePulse.IDLE)
            toast(getString(R.string.voice_error, t.message ?: "unavailable"))
        }
    }

    private fun stopVoiceSession() {
        if (!voiceActive) return
        voiceActive = false
        collectJob?.cancel()
        collectJob = null
        runCatching { unbindService(serviceConnection) }
        VoiceForegroundService.stop(this)
        boundManager = null
        liveUserId = null
        liveAgentId = null
        // Kill any in-flight status crossfade so its end action can't resurrect a
        // stale voice status after the session is gone.
        agentStatus.animate().cancel()
        agentStatus.alpha = 1f
        agentStatus.text = getString(R.string.agent_status)
        dock.setVoiceState(DockView.VoicePulse.IDLE)
    }

    /** Collects the session's state + UI events while the screen is started. */
    private fun observeSession(manager: VoiceSessionManager) {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { manager.state.collect { renderStatus(it, manager) } }
                launch { manager.activeTool.collect { renderStatus(manager.state.value, manager) } }
                launch { manager.events.collect { handleVoiceEvent(it) } }
            }
        }
    }

    private fun renderStatus(state: VoiceState, manager: VoiceSessionManager) {
        val statusText = when (state) {
            VoiceState.Idle -> getString(R.string.agent_status)
            VoiceState.Connecting -> getString(R.string.voice_status_connecting)
            VoiceState.Listening -> getString(R.string.voice_status_listening)
            VoiceState.Recording -> getString(R.string.voice_status_recording)
            VoiceState.Processing -> manager.activeTool.value
                ?.let { getString(R.string.voice_status_tool, it) }
                ?: getString(R.string.voice_status_thinking)
            VoiceState.Speaking -> getString(R.string.voice_status_speaking)
        }
        // Crossfade only on an actual change so streamed re-renders don't flicker.
        if (agentStatus.text?.toString() != statusText) crossfadeStatus(statusText)

        // Voice orb feedback: pulse only while actually capturing mic input.
        dock.setVoiceState(
            when (state) {
                VoiceState.Listening -> DockView.VoicePulse.LISTENING
                VoiceState.Recording -> DockView.VoicePulse.CAPTURING
                else -> DockView.VoicePulse.IDLE
            }
        )
    }

    /**
     * Swaps the header status with a quick dip-and-return fade. A new call always
     * cancels the previous sequence and schedules its own complete fade-in, so the
     * label can never be left mid-fade even under rapid state changes.
     */
    private fun crossfadeStatus(text: String) {
        agentStatus.animate().cancel()
        agentStatus.animate()
            .alpha(0f)
            .setDuration(80)
            .withEndAction {
                agentStatus.text = text
                agentStatus.animate().alpha(1f).setDuration(140).start()
            }
            .start()
    }

    private fun handleVoiceEvent(event: VoiceUiEvent) {
        when (event) {
            is VoiceUiEvent.Interim -> upsertLiveUser(event.text)
            is VoiceUiEvent.UserFinal -> commitUser(event.text)
            is VoiceUiEvent.AssistantDelta -> appendAgentDelta(event.text)
            is VoiceUiEvent.AssistantFinal -> finalizeAgent(event.text)
            is VoiceUiEvent.Tool -> Unit // surfaced via the status line
            is VoiceUiEvent.Metadata -> addMetadata(event.tasks)
            VoiceUiEvent.TasksChanged -> Unit // the Tasks screen refetches when shown
            VoiceUiEvent.HistoryCleared -> clearConversation()
            is VoiceUiEvent.Error -> toast(getString(R.string.voice_error, event.message))
        }
    }

    // ---- live conversation bubble helpers ---------------------------------

    private fun upsertLiveUser(text: String) {
        if (text.isBlank()) return
        val id = liveUserId
        if (id == null) {
            val msg = ChatMessage(text = text, fromUser = true)
            liveUserId = msg.id
            addMessage(msg)
        } else {
            updateMessage(id, text)
        }
    }

    private fun commitUser(text: String) {
        val id = liveUserId
        if (id != null && text.isNotBlank()) {
            updateMessage(id, text)
        } else if (text.isNotBlank()) {
            addMessage(ChatMessage(text = text, fromUser = true))
        }
        // End of the user turn — the next assistant reply starts a fresh bubble.
        liveUserId = null
        liveAgentId = null
        liveAgentRaw.setLength(0)
    }

    /**
     * Appends a streamed reply chunk. The full raw text is buffered and a sanitized
     * view (with any stray tool-call markup removed) is what's displayed, so a model
     * that wrongly emits `<function=…>` text never shows it to the user.
     */
    private fun appendAgentDelta(text: String) {
        if (text.isEmpty()) return
        liveAgentRaw.append(text)
        val display = sanitizeAssistantText(liveAgentRaw.toString())
        if (display.isEmpty()) return // nothing presentable yet (pure markup)

        val id = liveAgentId
        if (id == null) {
            val msg = ChatMessage(text = display, fromUser = false)
            liveAgentId = msg.id
            addMessage(msg)
        } else {
            updateMessage(id, display)
        }
    }

    private fun finalizeAgent(text: String) {
        val display = sanitizeAssistantText(text.ifBlank { liveAgentRaw.toString() })
        val id = liveAgentId
        if (id != null) {
            if (display.isNotBlank()) updateMessage(id, display)
        } else if (display.isNotBlank()) {
            addMessage(ChatMessage(text = display, fromUser = false))
        }
        liveAgentId = null
        liveAgentRaw.setLength(0)
    }

    /** Strips inline pseudo tool-call markup the model may emit as text. */
    private fun sanitizeAssistantText(raw: String): String {
        var c = raw.replace(TOOL_BLOCK, " ")
        c = c.replace(TOOL_UNCLOSED, " ")
        c = c.replace(TOOL_TAG, " ")
        return c.replace(MULTISPACE, " ").trim()
    }

    /** Appends a metadata details card (backend `metadata` channel). */
    private fun addMetadata(tasks: List<MetadataTask>) {
        if (tasks.isEmpty()) return
        addMessage(MetadataItem(tasks))
    }

    private fun addMessage(message: ChatItem) {
        messages.add(message)
        adapter.notifyItemInserted(messages.lastIndex)
        scrollToBottom()
    }

    private fun updateMessage(id: Long, text: String) {
        val index = indexOfId(id)
        val msg = messages.getOrNull(index) as? ChatMessage ?: return
        val follow = isAtBottom()
        msg.text = text
        adapter.notifyItemChanged(index)
        if (follow) scrollToBottom()
    }

    private fun indexOfId(id: Long): Int = messages.indexOfFirst { it.id == id }

    private fun clearConversation() {
        val count = messages.size
        if (count == 0) return
        messages.clear()
        adapter.notifyItemRangeRemoved(0, count)
        // Positions restart at 0 — re-arm the arrival animation for new bubbles.
        adapter.resetEntranceState()
        liveUserId = null
        liveAgentId = null
        liveAgentRaw.setLength(0)
    }

    // ---- engagement (greeting + due reminders) ----------------------------

    private fun loadEngagement() {
        lifecycleScope.launch {
            ServiceLocator.engagementRepository.getGreeting().getOrNull()?.let { greeting ->
                if (greeting.isNotBlank()) addMessage(ChatMessage(text = greeting, fromUser = false))
            }
            // Consume-on-read delivery: only call this when actually surfacing it.
            ServiceLocator.engagementRepository.getDueReminders().getOrNull()?.let { due ->
                if (due.count > 0) {
                    val text = due.message
                        ?: due.tasks.joinToString(prefix = "Reminders:\n• ", separator = "\n• ") { it.title }
                    addMessage(ChatMessage(text = text, fromUser = false))
                }
            }
        }
    }

    // ---- one-time location capture (PRD §7) -------------------------------

    private fun ensureLocationCaptured() {
        lifecycleScope.launch {
            // syncSession already populated the profile cache — reuse it instead of a
            // second /profile round-trip; only fetch if we somehow have nothing.
            val profile = ServiceLocator.profileRepository.cached
                ?: ServiceLocator.profileRepository.getProfile().getOrNull()
            if (!profile?.location.isNullOrBlank()) return@launch // already set
            if (locationProvider.hasPermission()) {
                resolveAndPersistLocation()
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    private fun resolveAndPersistLocation() {
        lifecycleScope.launch {
            val place = locationProvider.resolve() ?: return@launch
            ServiceLocator.profileRepository.setLocation(place.label, place.timezone)
            // Make the current live turn location-aware too, if connected.
            boundManager?.pushLocation(place.label)
        }
    }

    // ---- existing UI behavior (unchanged) ---------------------------------

    private fun setupChatList() {
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter
        // Smooth scrolling: fixed size (content doesn't change RV bounds) and a
        // larger view cache. The ItemAnimator is removed entirely: the adapter owns
        // the arrival pop-in, and streamed notifyItemChanged updates rebind directly
        // with zero animator churn — no flicker, no double-driven alpha.
        chatList.setHasFixedSize(true)
        chatList.setItemViewCacheSize(20)
        chatList.itemAnimator = null
    }

    /** True when the newest message is (almost) visible — used to auto-follow streaming. */
    private fun isAtBottom(): Boolean {
        val lm = chatList.layoutManager as? LinearLayoutManager ?: return true
        return lm.findLastVisibleItemPosition() >= messages.size - 2
    }

    /** Instant jump to the latest bubble (cheaper than smoothScroll during streaming). */
    private fun scrollToBottom() {
        if (messages.isNotEmpty()) chatList.scrollToPosition(messages.lastIndex)
    }

    /**
     * Edge-to-edge: pad the header below the status bar, float the dock above the
     * nav bar/keyboard, and reserve room so the last message clears the dock.
     */
    private fun applyWindowInsets() {
        val header = findViewById<View>(R.id.chatHeader)
        headerBaseTopPadding = header.paddingTop
        val dockBaseMargin = dp(28)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)

            header.updatePadding(top = headerBaseTopPadding + bars.top)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dockBaseMargin + bottom
            }
            // Keep the last bubble clear of the floating dock (incl. the chip above it).
            chatList.updatePadding(bottom = dp(162) + bottom)
            insets
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        // Strip inline pseudo tool-call markup (e.g. <function=query_tasks>{…}</function>).
        val TOOL_BLOCK =
            Regex("(?is)<\\s*(function|tool_call|tool_calls)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>")
        val TOOL_UNCLOSED =
            Regex("(?is)<\\s*(?:function|tool_call|tool_calls)\\b.*$")
        val TOOL_TAG =
            Regex("(?i)<\\s*/?\\s*(?:function|tool_call|tool_calls)\\b[^>]*>")
        val MULTISPACE = Regex("\\s{2,}")
    }
}

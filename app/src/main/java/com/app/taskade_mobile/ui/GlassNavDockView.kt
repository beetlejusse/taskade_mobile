package com.app.taskade_mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.app.taskade_mobile.R
import eightbitlab.com.blurview.BlurTarget

/**
 * Standalone glassmorphism navigation dock — a single frosted stadium pill holding
 * the four nav destinations (Tasks / Chat / Calendar / Profile) with **no** voice orb.
 *
 * Used on screens where the orb doesn't belong (Tasks, Settings); the chat screen
 * keeps the orb-cradling [DockView]. It deliberately mirrors [DockView]'s nav-facing
 * API — [setOnNavClickListener], [setActiveItem], [setupBlur] — and reuses
 * [DockView.Item] as the shared route vocabulary, so a host can swap one dock for the
 * other by changing only the view type.
 */
class GlassNavDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val pill: PillBlurView
    private val iconPadding: Int

    private var onNavClick: ((DockView.Item) -> Unit)? = null

    // Double-tap guard: rapid taps must not stack duplicate activities.
    private var lastNavClickAt = 0L
    private var entrancePlayed = false

    init {
        // The pill carries a soft elevation shadow, so don't clip it to our bounds.
        clipChildren = false
        clipToPadding = false
        inflate(context, R.layout.view_glass_nav_dock, this)

        pill = findViewById(R.id.glassNavPill)
        // Plain stadium silhouette — no concave orb notch (there is no orb here).
        pill.setStadium()
        // Stronger glass: a light sheen across the pill's top edge.
        pill.enableTopHighlight()

        val tasksButton = findViewById<ImageButton>(R.id.dockButtonTasks)
        val chatButton = findViewById<ImageButton>(R.id.dockButtonChat)
        val calendarButton = findViewById<ImageButton>(R.id.dockButtonCalendar)
        val profileButton = findViewById<ImageButton>(R.id.dockButtonProfile)
        iconPadding = tasksButton.paddingLeft

        tasksButton.setOnClickListener { navClick(DockView.Item.TASKS) }
        chatButton.setOnClickListener { navClick(DockView.Item.CHAT) }
        calendarButton.setOnClickListener { navClick(DockView.Item.CALENDAR) }
        profileButton.setOnClickListener { navClick(DockView.Item.PROFILE) }

        // Springy press feedback, matching DockView.
        listOf(tasksButton, chatButton, calendarButton, profileButton).forEach { Motion.pressScale(it) }
    }

    /** Invokes the nav listener unless an identical tap landed a moment ago. */
    private fun navClick(item: DockView.Item) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNavClickAt < NAV_DEBOUNCE_MS) return
        lastNavClickAt = now
        onNavClick?.invoke(item)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // One-shot entrance: the dock rises into place when its screen opens.
        if (!entrancePlayed) {
            entrancePlayed = true
            Motion.riseIn(this)
        }
    }

    /** Tapped a nav button (Tasks / Chat / Calendar / Profile). */
    fun setOnNavClickListener(listener: (DockView.Item) -> Unit) { onNavClick = listener }

    /** Highlights the active screen's nav button. */
    fun setActiveItem(item: DockView.Item) {
        applyState(R.id.dockButtonTasks, item == DockView.Item.TASKS)
        applyState(R.id.dockButtonChat, item == DockView.Item.CHAT)
        applyState(R.id.dockButtonCalendar, item == DockView.Item.CALENDAR)
        applyState(R.id.dockButtonProfile, item == DockView.Item.PROFILE)
    }

    /**
     * Enables live blur-through on the pill. [target] must wrap the screen content to
     * blur and must not contain this dock.
     */
    fun setupBlur(target: BlurTarget) {
        pill.setupWith(target)
            .setBlurRadius(BLUR_RADIUS)
            .setOverlayColor(ContextCompat.getColor(context, R.color.glass_overlay_light))
    }

    private fun applyState(id: Int, active: Boolean) {
        val button = findViewById<ImageButton>(id)
        button.setBackgroundResource(
            if (active) R.drawable.bg_nav_squircle_active else R.drawable.bg_nav_squircle
        )
        // setBackgroundResource resets padding to the drawable's — restore the icon inset.
        button.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        val tint = if (active) R.color.primary else R.color.muted
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tint))
    }

    private companion object {
        // Deeper frost than the chat dock (22 vs 18): with the lighter overlay the
        // backdrop shows through more, so the stronger blur keeps it abstract.
        const val BLUR_RADIUS = 22f
        /** Ignore repeat nav taps landing within this window (double-launch guard). */
        const val NAV_DEBOUNCE_MS = 500L
    }
}

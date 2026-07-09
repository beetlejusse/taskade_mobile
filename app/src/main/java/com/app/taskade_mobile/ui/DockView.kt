package com.app.taskade_mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Space
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.app.taskade_mobile.R
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Self-contained, reusable concave-notch dock — the app's single persistent
 * navigation element.
 *
 * Structure: two glassmorphism pills whose inner edges curve inward to cradle a
 * detached, raised voice orb. It has **no hardcoded screen dependencies** — the
 * host wires routes via [setOnNavClickListener] and voice via
 * [setOnVoiceClickListener]. Use [setActiveItem] to highlight the current screen's
 * button, [setVoiceState] to animate the orb, and [setupBlur] for live blur-through.
 */
class DockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** The four nav destinations; [NONE] clears the active highlight. */
    enum class Item { TASKS, CHAT, CALENDAR, PROFILE, NONE }

    /**
     * Visual feedback for the voice orb:
     *  - [IDLE] — at rest, no animation;
     *  - [LISTENING] — connected & waiting for speech (calm espresso pulse);
     *  - [CAPTURING] — actively capturing the user's voice (faster green pulse).
     */
    enum class VoicePulse { IDLE, LISTENING, CAPTURING }

    private val leftPill: PillBlurView
    private val rightPill: PillBlurView
    private val iconPadding: Int

    private val voiceOrb: ImageButton
    private val orbPulse1: View
    private val orbPulse2: View
    private val ringInterpolator = DecelerateInterpolator()
    private var pulseAnimator: ValueAnimator? = null
    private var voicePulse = VoicePulse.IDLE

    private var onNavClick: ((Item) -> Unit)? = null
    private var onVoiceClick: (() -> Unit)? = null

    // Double-tap guard: rapid taps on a nav button must not stack duplicate
    // activities while the first launch's transition is still playing.
    private var lastNavClickAt = 0L
    private var entrancePlayed = false

    // Morph state: while condensed the orb is tucked into the dock (screen is
    // navigating away) and incoming pulse states are deferred until expand().
    private var condensed = false

    init {
        // The orb carries elevation and sits outside the row bounds.
        clipChildren = false
        clipToPadding = false
        inflate(context, R.layout.view_dock, this)

        leftPill = findViewById(R.id.dockLeftPill)
        rightPill = findViewById(R.id.dockRightPill)
        voiceOrb = findViewById(R.id.dockButtonVoice)
        orbPulse1 = findViewById(R.id.dockOrbPulse1)
        orbPulse2 = findViewById(R.id.dockOrbPulse2)

        // Concave notch radius derived from the orb radius at runtime (not static).
        val arcRadiusPx = dp(ORB_DIAMETER_DP / 2f + NOTCH_EXTRA_DP)
        leftPill.setNotch(concaveOnRight = true, arcRadiusPx = arcRadiusPx)
        rightPill.setNotch(concaveOnRight = false, arcRadiusPx = arcRadiusPx)

        // Size the orb gap so the orb is concentric with the notch arc: the arc
        // center sits dx past each inner edge, so a gap of 2*dx centers the orb
        // between them with a uniform (arcRadius - orbRadius) cradle clearance.
        val pillHalfPx = dp(PILL_HEIGHT_DP / 2f)
        val dx = sqrt(arcRadiusPx * arcRadiusPx - pillHalfPx * pillHalfPx)
        val gap = findViewById<Space>(R.id.dockOrbGap)
        gap.layoutParams = gap.layoutParams.apply { width = (2f * dx).toInt() }

        val tasksButton = findViewById<ImageButton>(R.id.dockButtonTasks)
        iconPadding = tasksButton.paddingLeft

        val chatButton = findViewById<ImageButton>(R.id.dockButtonChat)
        val calendarButton = findViewById<ImageButton>(R.id.dockButtonCalendar)
        val profileButton = findViewById<ImageButton>(R.id.dockButtonProfile)

        tasksButton.setOnClickListener { navClick(Item.TASKS) }
        chatButton.setOnClickListener { navClick(Item.CHAT) }
        calendarButton.setOnClickListener { navClick(Item.CALENDAR) }
        profileButton.setOnClickListener { navClick(Item.PROFILE) }
        voiceOrb.setOnClickListener { onVoiceClick?.invoke() }

        // Springy press feedback on every dock control. The orb skips it while a
        // pulse is running — the pulse animator owns the orb's scale each frame,
        // and two writers would make it stutter.
        listOf(tasksButton, chatButton, calendarButton, profileButton).forEach { Motion.pressScale(it) }
        Motion.pressScale(voiceOrb, depth = 0.9f, enabled = { pulseAnimator == null })
    }

    /** Invokes the nav listener unless an identical tap landed a moment ago. */
    private fun navClick(item: Item) {
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
    fun setOnNavClickListener(listener: (Item) -> Unit) { onNavClick = listener }

    /** Tapped the center voice orb. */
    fun setOnVoiceClickListener(listener: () -> Unit) { onVoiceClick = listener }

    /**
     * Drives the voice orb's live feedback animation so the user can tell when the
     * assistant is taking mic input ([VoicePulse.LISTENING] / [VoicePulse.CAPTURING])
     * versus idle. Idempotent — repeated calls with the same state are ignored.
     */
    fun setVoiceState(state: VoicePulse) {
        if (state == voicePulse) return
        voicePulse = state
        // The orb is hidden while condensed — remember the state; expand() applies it.
        if (condensed) return
        applyPulse(state)
    }

    private fun applyPulse(state: VoicePulse) {
        when (state) {
            VoicePulse.IDLE -> stopPulse()
            VoicePulse.LISTENING -> startPulse(
                colorRes = R.color.primary,
                periodMs = 1600L,
                scaleTo = 1.85f,
                peakAlpha = 0.35f
            )
            VoicePulse.CAPTURING -> startPulse(
                colorRes = R.color.online,
                periodMs = 1050L,
                scaleTo = 2.0f,
                peakAlpha = 0.5f
            )
        }
    }

    /**
     * Morph, half one — call as the host screen navigates away (in the same frame
     * as startActivity, NOT before it): the voice orb is drawn back into the dock
     * (shrink + fade) while the screen transition crossfades, so the destination's
     * plain nav pill reads as a continuation of this dock. Running in parallel is
     * deliberate — serializing "shrink, then navigate" reads as launch lag.
     */
    fun condense() {
        if (condensed) return
        condensed = true
        stopPulse() // rings off; orb scale released (at 1) for the shrink
        voiceOrb.isEnabled = false // an invisible orb must not take taps
        voiceOrb.animate().cancel()
        voiceOrb.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setStartDelay(0)
            .setDuration(CONDENSE_MS)
            .setInterpolator(AccelerateInterpolator())
            .start()
    }

    /**
     * Morph, half two — call when the host screen comes back: the orb pops back out
     * of the dock with a small overshoot, then any voice state that arrived while
     * hidden is re-applied. No-op unless [condense] ran first.
     */
    fun expand() {
        if (!condensed) return
        condensed = false
        voiceOrb.animate().cancel()
        voiceOrb.scaleX = 0f
        voiceOrb.scaleY = 0f
        voiceOrb.alpha = 0f
        // Plain postDelayed instead of animator start delays: a VPA's startDelay is
        // sticky per view and would silently lag the orb's press feedback later.
        postDelayed({
            if (condensed) return@postDelayed // re-condensed while waiting
            voiceOrb.isEnabled = true
            voiceOrb.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(0)
                .setDuration(EXPAND_MS)
                .setInterpolator(OvershootInterpolator(1.35f))
                .start()
            postDelayed({ if (!condensed) applyPulse(voicePulse) }, EXPAND_MS)
        }, EXPAND_DELAY_MS)
    }

    /** Highlights the active screen's nav button. */
    fun setActiveItem(item: Item) {
        applyState(R.id.dockButtonTasks, item == Item.TASKS)
        applyState(R.id.dockButtonChat, item == Item.CHAT)
        applyState(R.id.dockButtonCalendar, item == Item.CALENDAR)
        applyState(R.id.dockButtonProfile, item == Item.PROFILE)
    }

    /**
     * Enables live blur-through on both pills. [target] must wrap the screen
     * content to blur and must not contain this dock.
     */
    fun setupBlur(target: BlurTarget) {
        // Pills clip themselves to their concave shape; just wire the blur.
        leftPill.setupWith(target).setBlurRadius(BLUR_RADIUS).setOverlayColor(overlayColor())
        rightPill.setupWith(target).setBlurRadius(BLUR_RADIUS).setOverlayColor(overlayColor())
    }

    // ---- voice orb pulse animation ----------------------------------------

    /**
     * Single [ValueAnimator] driving everything for efficiency: two sonar rings
     * (the second offset by half a cycle for a continuous ripple) plus a gentle
     * "breathing" scale on the orb itself. Linear driver; the ring expansion is
     * eased so it decelerates as it fades.
     */
    private fun startPulse(@ColorRes colorRes: Int, periodMs: Long, scaleTo: Float, peakAlpha: Float) {
        pulseAnimator?.cancel()
        // The pulse owns the orb's scale from here — stop any in-flight press spring.
        voiceOrb.animate().cancel()

        val tint = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
        orbPulse1.backgroundTintList = tint
        orbPulse2.backgroundTintList = tint
        orbPulse1.visibility = VISIBLE
        orbPulse2.visibility = VISIBLE

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val f = va.animatedFraction
                applyRing(orbPulse1, f, scaleTo, peakAlpha)
                applyRing(orbPulse2, (f + 0.5f) % 1f, scaleTo, peakAlpha)
                val breathe = 1f + 0.05f * sin(f * 2.0 * PI).toFloat()
                voiceOrb.scaleX = breathe
                voiceOrb.scaleY = breathe
            }
            start()
        }
    }

    private fun applyRing(view: View, fraction: Float, scaleTo: Float, peakAlpha: Float) {
        val eased = ringInterpolator.getInterpolation(fraction)
        val scale = 1f + (scaleTo - 1f) * eased
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = peakAlpha * (1f - fraction)
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        resetRing(orbPulse1)
        resetRing(orbPulse2)
        voiceOrb.scaleX = 1f
        voiceOrb.scaleY = 1f
    }

    private fun resetRing(view: View) {
        view.alpha = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
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

    private fun overlayColor(): Int = ContextCompat.getColor(context, R.color.glass_overlay)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        // 18 (down from 22) trims per-frame blur work during scroll with no
        // meaningful change to the frosted look.
        const val BLUR_RADIUS = 18f
        const val ORB_DIAMETER_DP = 72f
        const val PILL_HEIGHT_DP = 64f
        /** Arc radius is slightly larger than the orb radius so the notch cradles it. */
        const val NOTCH_EXTRA_DP = 6f
        /** Ignore repeat nav taps landing within this window (double-launch guard). */
        const val NAV_DEBOUNCE_MS = 500L

        // Dock morph timing: quick shrink on the way out; on return the pop waits a
        // beat so the screen transition settles before the orb draws attention.
        const val CONDENSE_MS = 170L
        const val EXPAND_DELAY_MS = 140L
        const val EXPAND_MS = 300L
    }
}

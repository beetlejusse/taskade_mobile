package com.app.taskade_mobile.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.app.taskade_mobile.R
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlin.math.sqrt

/**
 * Self-contained, reusable concave-notch dock — the app's single persistent
 * navigation element.
 *
 * Structure: two glassmorphism pills whose inner edges curve inward to cradle a
 * detached, raised voice orb, plus a keyboard-toggle chip floating above. It has
 * **no hardcoded screen dependencies** — the host wires routes via
 * [setOnNavClickListener], voice via [setOnVoiceClickListener], and the keyboard
 * input via [setOnKeyboardToggleListener]. Use [setActiveItem] to highlight the
 * current screen's button, and [setupBlur] to enable live blur-through.
 */
class DockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** The four nav destinations; [NONE] clears the active highlight. */
    enum class Item { TASKS, CHAT, CALENDAR, PROFILE, NONE }

    private val leftPill: PillBlurView
    private val rightPill: PillBlurView
    private val keyboardChip: BlurView
    private val chipIcon: ImageView
    private val chipLabel: TextView
    private val iconPadding: Int

    private var onNavClick: ((Item) -> Unit)? = null
    private var onVoiceClick: (() -> Unit)? = null
    private var onKeyboardToggle: ((Boolean) -> Unit)? = null
    private var keyboardMode = false

    init {
        // Orb/chip carry elevation and sit outside the row bounds.
        clipChildren = false
        clipToPadding = false
        inflate(context, R.layout.view_dock, this)

        leftPill = findViewById(R.id.dockLeftPill)
        rightPill = findViewById(R.id.dockRightPill)
        keyboardChip = findViewById(R.id.dockKeyboardChip)
        chipIcon = findViewById(R.id.dockChipIcon)
        chipLabel = findViewById(R.id.dockChipLabel)

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

        tasksButton.setOnClickListener { onNavClick?.invoke(Item.TASKS) }
        findViewById<ImageButton>(R.id.dockButtonChat).setOnClickListener { onNavClick?.invoke(Item.CHAT) }
        findViewById<ImageButton>(R.id.dockButtonCalendar).setOnClickListener { onNavClick?.invoke(Item.CALENDAR) }
        findViewById<ImageButton>(R.id.dockButtonProfile).setOnClickListener { onNavClick?.invoke(Item.PROFILE) }
        findViewById<ImageButton>(R.id.dockButtonVoice).setOnClickListener { onVoiceClick?.invoke() }
        keyboardChip.setOnClickListener { toggleKeyboard() }
        updateChip()
    }

    /** Tapped a nav button (Tasks / Chat / Calendar / Profile). */
    fun setOnNavClickListener(listener: (Item) -> Unit) { onNavClick = listener }

    /** Tapped the center voice orb. */
    fun setOnVoiceClickListener(listener: () -> Unit) { onVoiceClick = listener }

    /** Toggled the keyboard chip; emits the new keyboard-input mode. */
    fun setOnKeyboardToggleListener(listener: (Boolean) -> Unit) { onKeyboardToggle = listener }

    /** Forces the chip's mode (e.g., when the host closes the input form itself). */
    fun setKeyboardMode(enabled: Boolean) {
        if (keyboardMode != enabled) {
            keyboardMode = enabled
            updateChip()
        }
    }

    /** Highlights the active screen's nav button. */
    fun setActiveItem(item: Item) {
        applyState(R.id.dockButtonTasks, item == Item.TASKS)
        applyState(R.id.dockButtonChat, item == Item.CHAT)
        applyState(R.id.dockButtonCalendar, item == Item.CALENDAR)
        applyState(R.id.dockButtonProfile, item == Item.PROFILE)
    }

    /**
     * Enables live blur-through on both pills and the chip. [target] must wrap the
     * screen content to blur and must not contain this dock.
     */
    fun setupBlur(target: BlurTarget) {
        // Pills clip themselves to their concave shape; just wire the blur.
        leftPill.setupWith(target).setBlurRadius(BLUR_RADIUS).setOverlayColor(overlayColor())
        rightPill.setupWith(target).setBlurRadius(BLUR_RADIUS).setOverlayColor(overlayColor())

        roundOutline(keyboardChip)
        keyboardChip.setupWith(target).setBlurRadius(BLUR_RADIUS).setOverlayColor(overlayColor())
    }

    private fun toggleKeyboard() {
        keyboardMode = !keyboardMode
        updateChip()
        onKeyboardToggle?.invoke(keyboardMode)
    }

    private fun updateChip() {
        // Icon + label reflect the current input mode.
        chipIcon.setImageResource(if (keyboardMode) R.drawable.ic_mic else R.drawable.ic_keyboard)
        chipLabel.setText(if (keyboardMode) R.string.dock_chip_voice else R.string.dock_chip_type)
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

    private fun roundOutline(view: View) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
            }
        }
        view.clipToOutline = true
    }

    private fun overlayColor(): Int = ContextCompat.getColor(context, R.color.glass_overlay)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val BLUR_RADIUS = 22f
        const val ORB_DIAMETER_DP = 72f
        const val PILL_HEIGHT_DP = 64f
        /** Arc radius is slightly larger than the orb radius so the notch cradles it. */
        const val NOTCH_EXTRA_DP = 6f
    }
}

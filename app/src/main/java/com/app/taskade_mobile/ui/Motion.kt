package com.app.taskade_mobile.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Shared micro-interaction toolkit — the app's single source of truth for touch
 * feedback and entrance motion, so every screen moves with the same personality
 * (quick decelerating entrances, springy release on press — the feel of modern
 * iOS/Material apps).
 *
 * Everything here drives `View` properties (hardware-accelerated, respects the
 * system animator-duration scale, auto-safe when views detach).
 */
object Motion {

    private const val PRESS_DOWN_MS = 90L
    private const val PRESS_UP_MS = 240L
    private const val RISE_DURATION_MS = 420L

    /**
     * Tactile press feedback: the view sinks to [depth] while touched and springs
     * back on release — the standard "squishy button" from modern mobile apps.
     *
     * Attach only to clickable views: the listener never consumes the event
     * (returns false), so ripples, clicks and long-presses keep working. The
     * optional [enabled] gate lets a host skip the effect while another animation
     * owns the view's scale (e.g. the voice orb while its pulse is breathing).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressScale(view: View, depth: Float = 0.94f, enabled: () -> Boolean = { true }) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (enabled()) {
                    v.animate().scaleX(depth).scaleY(depth)
                        .setDuration(PRESS_DOWN_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (enabled()) {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(PRESS_UP_MS)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            }
            false
        }
    }

    /**
     * Entrance for floating chrome (docks, FAB-like elements): rises from just
     * below its resting spot while fading in.
     */
    fun riseIn(view: View, liftDp: Float = 28f, startDelayMs: Long = 80L) {
        view.translationY = liftDp * view.resources.displayMetrics.density
        view.alpha = 0f
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setStartDelay(startDelayMs)
            .setDuration(RISE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Clears any transient entrance/press values a recycled view may carry. */
    fun resetTransforms(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}

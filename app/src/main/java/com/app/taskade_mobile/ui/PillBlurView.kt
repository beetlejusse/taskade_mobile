package com.app.taskade_mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.app.taskade_mobile.R
import eightbitlab.com.blurview.BlurView
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A [BlurView] shaped like a stadium pill whose inner edge — the side facing the
 * center orb — is carved into a concave arc, so the left and right pills cradle
 * the floating orb into one cohesive unit.
 *
 * The arc geometry is derived from the orb radius at runtime via [setNotch] (no
 * static magic numbers): it spans the full pill height and the glass border
 * stroke is drawn continuously around the whole perimeter, the concave arc
 * included. The convex stadium silhouette is used for the elevation shadow only
 * (a concave [Outline] can't cast a shadow); the extra shadow over the notch is
 * hidden behind the orb that nestles into it.
 */
class PillBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BlurView(context, attrs, defStyleAttr) {

    private var concaveOnRight = true
    private var arcRadius = 0f
    private var plain = false

    // Opt-in "light catching the glass" sheen across the pill's top half.
    private var topHighlight = false
    private var highlightPaint: Paint? = null

    private val shape = Path()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = ContextCompat.getColor(context, R.color.glass_stroke)
    }

    init {
        // Convex stadium silhouette drives the (valid) elevation shadow. Clipping to
        // it keeps the rounded shadow from falling back to the rectangular bounds
        // (which showed as blocky corners).
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width == 0 || view.height == 0) return
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = true
    }

    /**
     * @param concaveOnRight true for the left pill (notch on its right edge),
     *        false for the right pill (notch on its left edge).
     * @param arcRadiusPx radius of the concave arc — derived from the orb radius
     *        (slightly larger) by the caller.
     */
    fun setNotch(concaveOnRight: Boolean, arcRadiusPx: Float) {
        this.plain = false
        this.concaveOnRight = concaveOnRight
        this.arcRadius = arcRadiusPx
        buildShape()
        invalidate()
    }

    /**
     * Switches this pill to a plain stadium silhouette with **no** concave orb notch —
     * used by the standalone [GlassNavDockView] where no orb is cradled. Without this
     * the pill always carves a notch on one edge (the default arc), which looks broken
     * when there's no orb to fill it.
     */
    fun setStadium() {
        this.plain = true
        buildShape()
        invalidate()
    }

    /**
     * Adds a soft white sheen fading down from the pill's top edge — the classic
     * glassmorphism cue of light hitting the pane. Opt-in so hosts that want the
     * plainer frosted look are unaffected.
     */
    fun enableTopHighlight() {
        topHighlight = true
        buildHighlight()
        invalidate()
    }

    private fun buildHighlight() {
        if (!topHighlight || height == 0) return
        highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height * 0.55f,
                HIGHLIGHT_TOP_COLOR, HIGHLIGHT_END_COLOR,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShape()
        buildHighlight()
        // Recompute the stadium outline for the real size so the shadow hugs the
        // pill instead of falling back to the rectangular bounding box.
        invalidateOutline()
    }

    private fun buildShape() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (plain) {
            // Plain stadium (no orb notch). Inset by half the stroke so the full glass
            // border renders inside the clip instead of being shaved by the outline.
            val inset = borderPaint.strokeWidth / 2f
            val r = (h - 2f * inset) / 2f
            shape.reset()
            shape.addRoundRect(inset, inset, w - inset, h - inset, r, r, Path.Direction.CW)
            return
        }

        val cap = h / 2f                                   // fully-rounded stadium ends
        val half = h / 2f
        // Keep the arc able to span the full height; widen slightly if needed.
        val a = if (arcRadius > half) arcRadius else half + dp(2f)
        val dx = sqrt(a * a - half * half)                 // arc-center offset past the inner edge
        val phi = Math.toDegrees(atan2(half.toDouble(), dx.toDouble())).toFloat()

        shape.reset()
        if (concaveOnRight) {
            // Left pill: rounded cap on the left, concave arc on the right.
            val cx = w + dx
            val arcRect = RectF(cx - a, half - a, cx + a, half + a)
            shape.moveTo(cap, 0f)
            shape.lineTo(w, 0f)                             // top-right = top of the arc
            shape.arcTo(arcRect, phi - 180f, -2f * phi)    // concave arc, bulging left
            shape.lineTo(cap, h)
            shape.arcTo(RectF(0f, 0f, h, h), 90f, 180f)    // left semicircular cap
        } else {
            // Right pill: concave arc on the left, rounded cap on the right.
            val cx = -dx
            val arcRect = RectF(cx - a, half - a, cx + a, half + a)
            shape.moveTo(w - cap, 0f)
            shape.lineTo(0f, 0f)                            // top-left = top of the arc
            shape.arcTo(arcRect, -phi, 2f * phi)           // concave arc, bulging right
            shape.lineTo(w - cap, h)
            shape.arcTo(RectF(w - h, 0f, w, h), 90f, -180f) // right semicircular cap
        }
        shape.close()
    }

    override fun draw(canvas: Canvas) {
        if (shape.isEmpty) {
            super.draw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipPath(shape)
        super.draw(canvas)                                 // blurred backdrop + icons, clipped to pill
        // Light-on-glass sheen over the top half (still clipped to the pill).
        highlightPaint?.let {
            if (topHighlight) canvas.drawRect(0f, 0f, width.toFloat(), height * 0.55f, it)
        }
        canvas.restoreToCount(save)
        // Crisp glass edge around the entire perimeter, concave arc included.
        canvas.drawPath(shape, borderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        // White sheen: ~16% alpha at the top edge fading to nothing mid-pill.
        const val HIGHLIGHT_TOP_COLOR = 0x29FFFFFF
        const val HIGHLIGHT_END_COLOR = 0x00FFFFFF
    }
}

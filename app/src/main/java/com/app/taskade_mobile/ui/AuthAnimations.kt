package com.app.taskade_mobile.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.children
import androidx.core.view.doOnLayout

/**
 * Entrance choreography shared by the login and sign-up screens.
 *
 * The auth sheet starts just below the screen and glides up into place with a
 * slow, decelerating motion, while the form rows fade and lift in with a gentle
 * stagger — giving the "dragged upward" feel from the design without requiring
 * the user to actually drag.
 */
object AuthAnimations {

    private const val SHEET_DURATION = 720L
    private const val SHEET_SETTLE_DELAY = 160L
    private const val ROW_DURATION = 440L
    private const val ROW_STAGGER = 65L
    private const val ROW_LIFT_DP = 16f

    /**
     * Animates [sheet] sliding up from below and the children of [content]
     * fading/lifting in. Safe to call from `onCreate`; the work is deferred
     * until the views have been measured.
     */
    fun playEntrance(sheet: View, content: ViewGroup) {
        sheet.doOnLayout {
            slideSheetUp(sheet)
            staggerRowsIn(content)
        }
    }

    private fun slideSheetUp(sheet: View) {
        sheet.translationY = sheet.height.toFloat()
        sheet.animate()
            .translationY(0f)
            .setDuration(SHEET_DURATION)
            .setInterpolator(DecelerateInterpolator(2.2f))
            .start()
    }

    private fun staggerRowsIn(content: ViewGroup) {
        val lift = ROW_LIFT_DP * content.resources.displayMetrics.density
        content.children.forEachIndexed { index, row ->
            row.alpha = 0f
            row.translationY = lift
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(SHEET_SETTLE_DELAY + index * ROW_STAGGER)
                .setDuration(ROW_DURATION)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

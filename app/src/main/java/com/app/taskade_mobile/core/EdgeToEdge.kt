package com.app.taskade_mobile.core

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Enables edge-to-edge with **fully transparent** status and navigation bars and
 * dark icons (for the light beige UI), and disables the system's bar-contrast
 * scrim.
 *
 * Plain `enableEdgeToEdge()` applies a translucent *white* scrim to the navigation
 * bar by default (the "white line" at the bottom). Passing transparent scrims via
 * [SystemBarStyle.light] removes it on every device / navigation mode, so content
 * (beige) shows right to the screen edges.
 */
fun ComponentActivity.enableSeamlessEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
}

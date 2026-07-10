package com.app.taskade_mobile

import android.app.Application
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.core.SettingsStore
import com.app.taskade_mobile.notifications.NotificationHelper

/**
 * Application entry point. Applies the persisted theme before any activity is
 * created (so the chosen day/night mode is honored on cold start) and warms the
 * shared [ServiceLocator] dependency container.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.getInstance(this).applyTheme()
        ServiceLocator.init(this)
        // Create the reminder notification channel up front so a push that
        // arrives before any screen is opened still lands on a real channel.
        NotificationHelper.ensureChannel(this)
    }
}

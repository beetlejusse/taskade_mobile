package com.app.taskade_mobile.core

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Local, persisted user settings (SharedPreferences) for the options the backend
 * doesn't own: notification + privacy toggles, the app theme, and a local profile
 * name/bio override. Backend-owned data (tasks, location, the Auth0 identity) is
 * never duplicated here.
 *
 * The theme is applied process-wide via [applyTheme] (called from [com.app.taskade_mobile.App]).
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("taskade_settings", Context.MODE_PRIVATE)

    var pushNotifications: Boolean
        get() = prefs.getBoolean(KEY_PUSH, true)
        set(v) = prefs.edit().putBoolean(KEY_PUSH, v).apply()

    var notificationSounds: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    var appBadges: Boolean
        get() = prefs.getBoolean(KEY_BADGES, false)
        set(v) = prefs.edit().putBoolean(KEY_BADGES, v).apply()

    var showOnlineStatus: Boolean
        get() = prefs.getBoolean(KEY_VISIBILITY, true)
        set(v) = prefs.edit().putBoolean(KEY_VISIBILITY, v).apply()

    var shareAnalytics: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS, false)
        set(v) = prefs.edit().putBoolean(KEY_ANALYTICS, v).apply()

    /** Local display-name override (the backend has no name-update endpoint). */
    var profileName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    var profileBio: String
        get() = prefs.getString(KEY_BIO, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_BIO, v).apply()

    /**
     * Local mirror of the server-side onboarding gate. Onboarding is a one-way flip,
     * so once it's `true` the launcher can reveal the chat instantly and heal the
     * session in the background instead of blocking on a network round-trip.
     */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /**
     * Whether the startup POST_NOTIFICATIONS request has already been shown once.
     * The system dialog is a one-shot ask: repeating it on every launch nags users
     * into permanent denial, and a permanently-denied re-request auto-fails silently.
     */
    var notificationPermissionRequested: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, v).apply()

    /** One of [THEME_LIGHT] / [THEME_DARK] / [THEME_SYSTEM]. */
    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    /** Applies [theme] to AppCompat's day/night mode (affects the whole app). */
    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        private const val KEY_PUSH = "push_notifications"
        private const val KEY_SOUND = "notification_sounds"
        private const val KEY_BADGES = "app_badges"
        private const val KEY_VISIBILITY = "show_online_status"
        private const val KEY_ANALYTICS = "share_analytics"
        private const val KEY_NAME = "profile_name"
        private const val KEY_BIO = "profile_bio"
        private const val KEY_THEME = "theme"
        private const val KEY_ONBOARDED = "onboarding_complete"
        private const val KEY_NOTIF_ASKED = "notification_permission_requested"

        @Volatile
        private var instance: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}

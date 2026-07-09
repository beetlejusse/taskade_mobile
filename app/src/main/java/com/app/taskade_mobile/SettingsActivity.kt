package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.core.ImageLoader
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.core.SettingsStore
import com.app.taskade_mobile.ui.DockView
import com.app.taskade_mobile.ui.GlassNavDockView
import com.google.android.material.card.MaterialCardView
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.launch

/**
 * Settings screen — opened from the dock's profile button (slide-up transition).
 * A scrolling avatar header + static settings rows sit beneath the persistent
 * dock; each row opens a [SettingsSheetFragment]. Entrance is choreographed
 * (avatar pop, header fade, staggered rows) and each row pulses before its sheet.
 */
class SettingsActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }

    private lateinit var scroll: NestedScrollView
    private lateinit var content: View
    private lateinit var avatar: ImageView
    private lateinit var name: TextView
    private lateinit var email: TextView
    private lateinit var dock: GlassNavDockView
    private val rows = mutableListOf<View>()

    private var contentBaseTopPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_settings)

        if (!authManager.isAuthenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        scroll = findViewById(R.id.settingsScroll)
        content = findViewById(R.id.settingsContent)
        avatar = findViewById(R.id.settingsAvatar)
        name = findViewById(R.id.settingsName)
        email = findViewById(R.id.settingsEmail)
        dock = findViewById(R.id.dock)

        bindRows()
        avatar.setOnClickListener { openSheet(SettingsSheetFragment.Section.PROFILE) }
        setupDock()
        applyWindowInsets()
        prepareAndPlayEntrance()
        refreshHeader()
    }

    /**
     * Loads the profile header in realtime: a local name override (if the user set
     * one) shows instantly, while the Auth0 identity (name, email, avatar) and the
     * backend display name fill in asynchronously. Public so the profile sheet can
     * trigger a refresh after a save.
     */
    fun refreshHeader() {
        val store = SettingsStore.getInstance(this)
        val localName = store.profileName
        if (localName.isNotBlank()) {
            name.text = localName
        } else {
            // Instant paint from the cached backend profile while Auth0 /userinfo loads.
            ServiceLocator.profileRepository.cached?.displayName?.let {
                if (it.isNotBlank()) name.text = it
            }
        }

        authManager.getCredentials(
            onSuccess = { creds ->
                authManager.getProfile(
                    accessToken = creds.accessToken,
                    onSuccess = { profile ->
                        if (store.profileName.isBlank()) {
                            name.text = profile.name
                                ?: profile.nickname
                                ?: getString(R.string.settings_user_name)
                        }
                        profile.email?.let { email.text = it }
                        profile.pictureURL?.let { loadAvatar(it) }
                    },
                    onFailure = { /* keep whatever is shown */ }
                )
            },
            onFailure = { /* the auth gate handles a missing session */ }
        )

        // Backend display name as a last-resort fallback — use the cache if present
        // (no extra /profile call), otherwise fetch once.
        if (store.profileName.isBlank() && name.text.isNullOrBlank()) {
            val cachedName = ServiceLocator.profileRepository.cached?.displayName
            if (!cachedName.isNullOrBlank()) {
                name.text = cachedName
            } else {
                lifecycleScope.launch {
                    val displayName = ServiceLocator.profileRepository.getProfile().getOrNull()?.displayName
                    if (store.profileName.isBlank() && !displayName.isNullOrBlank() && name.text.isNullOrBlank()) {
                        name.text = displayName
                    }
                }
            }
        }
    }

    private fun loadAvatar(url: String) {
        lifecycleScope.launch {
            ImageLoader.load(ServiceLocator.apiProvider.httpClient, url)?.let { avatar.setImageBitmap(it) }
        }
    }

    private fun bindRows() {
        bindRow(R.id.rowProfile, R.drawable.ic_person, R.string.settings_profile,
            R.string.settings_profile_sub, SettingsSheetFragment.Section.PROFILE)
        bindRow(R.id.rowNotifications, R.drawable.ic_notifications, R.string.settings_notifications,
            R.string.settings_notifications_sub, SettingsSheetFragment.Section.NOTIFICATIONS)
        bindRow(R.id.rowPrivacy, R.drawable.ic_privacy, R.string.settings_privacy,
            R.string.settings_privacy_sub, SettingsSheetFragment.Section.PRIVACY)
        bindRow(R.id.rowAppearance, R.drawable.ic_appearance, R.string.settings_appearance,
            R.string.settings_appearance_sub, SettingsSheetFragment.Section.APPEARANCE)
        bindRow(R.id.rowAbout, R.drawable.ic_about, R.string.settings_about,
            R.string.settings_about_sub, SettingsSheetFragment.Section.ABOUT)
        bindRow(R.id.rowSignOut, R.drawable.ic_signout, R.string.settings_sign_out,
            R.string.settings_sign_out_sub, SettingsSheetFragment.Section.SIGN_OUT, destructive = true)
    }

    private fun bindRow(
        cardId: Int,
        @DrawableRes iconRes: Int,
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        section: SettingsSheetFragment.Section,
        destructive: Boolean = false
    ) {
        val card = findViewById<MaterialCardView>(cardId)
        val icon = card.findViewById<ImageView>(R.id.rowIcon)
        val title = card.findViewById<TextView>(R.id.rowTitle)
        val subtitle = card.findViewById<TextView>(R.id.rowSubtitle)

        icon.setImageResource(iconRes)
        title.setText(titleRes)
        subtitle.setText(subtitleRes)

        if (destructive) {
            val danger = getColor(R.color.danger)
            icon.setColorFilter(danger)
            icon.setBackgroundResource(R.drawable.bg_icon_tile_danger)
            title.setTextColor(danger)
        }

        card.setOnClickListener { pulseThenOpen(card, section) }
        rows.add(card)
    }

    private fun setupDock() {
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.PROFILE)
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.TASKS -> {
                    startActivity(Intent(this, TasksActivity::class.java))
                    overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
                }
                DockView.Item.CHAT -> {
                    startActivity(
                        Intent(this, ChatActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
                }
                else -> Unit // Profile is current; Calendar is a placeholder.
            }
        }
    }

    /** Row tap: quick scale pulse, then open the sheet once it settles. */
    private fun pulseThenOpen(card: View, section: SettingsSheetFragment.Section) {
        card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70)
            .withEndAction {
                card.animate().scaleX(1f).scaleY(1f).setDuration(70)
                    .withEndAction { openSheet(section) }
                    .start()
            }
            .start()
    }

    private fun openSheet(section: SettingsSheetFragment.Section) {
        SettingsSheetFragment.newInstance(section).show(supportFragmentManager, section.name)
    }

    private fun applyWindowInsets() {
        contentBaseTopPadding = content.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = contentBaseTopPadding + bars.top)
            scroll.updatePadding(bottom = dp(162) + bars.bottom)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dp(28) + bars.bottom
            }
            insets
        }
    }

    /** Set the pre-animation state, then run the entrance once views are laid out. */
    private fun prepareAndPlayEntrance() {
        avatar.alpha = 0f
        avatar.scaleX = 0.82f
        avatar.scaleY = 0.82f
        listOf(name, email).forEach { it.alpha = 0f; it.translationY = dpF(12f) }
        rows.forEach { it.alpha = 0f; it.translationY = dpF(28f) }

        content.doOnPreDraw {
            avatar.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(OvershootInterpolator(1.2f)).start()

            listOf(name, email).forEach { view ->
                view.animate().alpha(1f).translationY(0f)
                    .setStartDelay(120).setDuration(300)
                    .setInterpolator(DecelerateInterpolator()).start()
            }

            rows.forEachIndexed { index, row ->
                row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(120L + index * 55L).setDuration(300)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    override fun finish() {
        super.finish()
        // Slide back down on the way out.
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * resources.displayMetrics.density
}

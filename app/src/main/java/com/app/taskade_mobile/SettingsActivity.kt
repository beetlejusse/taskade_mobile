package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.ui.DockView
import com.google.android.material.card.MaterialCardView
import eightbitlab.com.blurview.BlurTarget

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
    private lateinit var avatar: View
    private lateinit var name: View
    private lateinit var email: View
    private lateinit var dock: DockView
    private val rows = mutableListOf<View>()

    private var contentBaseTopPadding = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            title.setTextColor(danger)
        }

        card.setOnClickListener { pulseThenOpen(card, section) }
        rows.add(card)
    }

    private fun setupDock() {
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.PROFILE)
        ViewCompat.setZ(dock, 99f)
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.TASKS -> startActivity(Intent(this, TasksActivity::class.java))
                DockView.Item.CHAT -> startActivity(
                    Intent(this, ChatActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
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
            scroll.updatePadding(bottom = dp(152) + bars.bottom)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dp(18) + bars.bottom
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

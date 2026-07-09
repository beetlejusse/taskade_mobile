package com.app.taskade_mobile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.core.SettingsStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * A single bottom sheet that adapts to the tapped settings [Section]: it inflates a
 * shared shell (drag handle + title) plus the section's body layout, prefills it
 * from live state, and persists changes on save.
 *
 * Backend-owned identity (name/email/avatar) is read-only here; the local toggles,
 * theme and profile name/bio persist via [SettingsStore]; Sign Out clears the
 * session.
 */
class SettingsSheetFragment : BottomSheetDialogFragment() {

    enum class Section(@StringRes val titleRes: Int, @LayoutRes val bodyRes: Int) {
        PROFILE(R.string.settings_profile, R.layout.sheet_body_profile),
        NOTIFICATIONS(R.string.settings_notifications, R.layout.sheet_body_notifications),
        PRIVACY(R.string.settings_privacy, R.layout.sheet_body_privacy),
        APPEARANCE(R.string.settings_appearance, R.layout.sheet_body_appearance),
        ABOUT(R.string.settings_about, R.layout.sheet_body_about),
        SIGN_OUT(R.string.settings_sign_out, R.layout.sheet_body_signout)
    }

    private val section: Section
        get() = Section.valueOf(requireArguments().getString(ARG_SECTION, Section.PROFILE.name))

    private val store by lazy { SettingsStore.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = inflater.inflate(R.layout.sheet_shell, container, false)
        shell.findViewById<TextView>(R.id.sheetTitle).setText(section.titleRes)

        val body = shell.findViewById<LinearLayout>(R.id.sheetBody)
        inflater.inflate(section.bodyRes, body, true)

        bindSection(body)

        // Generic footer buttons (each body declares whichever it needs).
        body.findViewById<View>(R.id.sheetSaveButton)?.setOnClickListener { saveSection(body) }
        body.findViewById<View>(R.id.sheetCloseButton)?.setOnClickListener { dismiss() }
        body.findViewById<View>(R.id.sheetCancelButton)?.setOnClickListener { dismiss() }
        body.findViewById<View>(R.id.sheetSignOutButton)?.setOnClickListener { signOut() }
        return shell
    }

    override fun onStart() {
        super.onStart()
        // Let our rounded bg_bottom_sheet show by clearing the default sheet surface.
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    /** Prefills the body from live state when the sheet opens. */
    private fun bindSection(body: View) {
        when (section) {
            Section.PROFILE -> {
                body.findViewById<TextInputEditText>(R.id.profileNameInput)?.setText(store.profileName)
                body.findViewById<TextInputEditText>(R.id.profileBioInput)?.setText(store.profileBio)
            }
            Section.NOTIFICATIONS -> {
                body.findViewById<SwitchMaterial>(R.id.notifPush)?.isChecked = store.pushNotifications
                body.findViewById<SwitchMaterial>(R.id.notifSound)?.isChecked = store.notificationSounds
                body.findViewById<SwitchMaterial>(R.id.notifBadges)?.isChecked = store.appBadges
            }
            Section.PRIVACY -> {
                body.findViewById<SwitchMaterial>(R.id.privacyVisibility)?.isChecked = store.showOnlineStatus
                body.findViewById<SwitchMaterial>(R.id.privacyAnalytics)?.isChecked = store.shareAnalytics
            }
            Section.APPEARANCE -> {
                val checkedId = when (store.theme) {
                    SettingsStore.THEME_LIGHT -> R.id.themeLight
                    SettingsStore.THEME_DARK -> R.id.themeDark
                    else -> R.id.themeSystem
                }
                body.findViewById<RadioGroup>(R.id.themeGroup)?.check(checkedId)
            }
            Section.ABOUT -> bindAbout(body)
            Section.SIGN_OUT -> Unit
        }
    }

    private fun bindAbout(body: View) {
        val version = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        body.findViewById<TextView>(R.id.aboutVersion)?.text = getString(R.string.settings_version_fmt, version)
        body.findViewById<View>(R.id.aboutSupport)?.setOnClickListener { sendEmail("Taskade support") }
        body.findViewById<View>(R.id.aboutFeedback)?.setOnClickListener { sendEmail("Taskade feedback") }
    }

    /** Persists the section's changes and dismisses. */
    private fun saveSection(body: View) {
        when (section) {
            Section.PROFILE -> {
                store.profileName = body.findViewById<TextInputEditText>(R.id.profileNameInput)
                    ?.text?.toString()?.trim().orEmpty()
                store.profileBio = body.findViewById<TextInputEditText>(R.id.profileBioInput)
                    ?.text?.toString()?.trim().orEmpty()
                (activity as? SettingsActivity)?.refreshHeader()
            }
            Section.NOTIFICATIONS -> {
                store.pushNotifications = body.findViewById<SwitchMaterial>(R.id.notifPush).isChecked
                store.notificationSounds = body.findViewById<SwitchMaterial>(R.id.notifSound).isChecked
                store.appBadges = body.findViewById<SwitchMaterial>(R.id.notifBadges).isChecked
            }
            Section.PRIVACY -> {
                store.showOnlineStatus = body.findViewById<SwitchMaterial>(R.id.privacyVisibility).isChecked
                store.shareAnalytics = body.findViewById<SwitchMaterial>(R.id.privacyAnalytics).isChecked
            }
            Section.APPEARANCE -> {
                store.theme = when (body.findViewById<RadioGroup>(R.id.themeGroup).checkedRadioButtonId) {
                    R.id.themeLight -> SettingsStore.THEME_LIGHT
                    R.id.themeDark -> SettingsStore.THEME_DARK
                    else -> SettingsStore.THEME_SYSTEM
                }
                store.applyTheme() // AppCompat re-creates the activity to apply day/night.
            }
            else -> Unit
        }
        toast(R.string.settings_saved)
        dismiss()
    }

    private fun sendEmail(subject: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(R.string.settings_no_email_app) }
    }

    private fun signOut() {
        val appContext = requireContext().applicationContext
        val activity = requireActivity()
        // Best-effort: unregister this device's push token while the session is
        // still valid (bounded), THEN clear creds and navigate to login.
        lifecycleScope.launch {
            runCatching {
                withTimeout(5_000) { ServiceLocator.deviceTokenRepository.unregisterCurrentToken() }
            }
            AuthManager.getInstance(appContext).logout()
            activity.startActivity(
                Intent(appContext, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            activity.finish()
        }
    }

    private fun toast(@StringRes msg: Int) {
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }
            ?: requireActivity().findViewById<View>(android.R.id.content)?.let {
                Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show()
            }
    }

    companion object {
        private const val ARG_SECTION = "section"
        private const val SUPPORT_EMAIL = "support@taskade.app"

        fun newInstance(section: Section) = SettingsSheetFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section.name) }
        }
    }
}

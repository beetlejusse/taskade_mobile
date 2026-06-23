package com.app.taskade_mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.app.taskade_mobile.auth.AuthManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar

/**
 * A single bottom sheet that adapts to the tapped settings [Section]: it inflates
 * a shared shell (drag handle + title) plus the section's body layout, then wires
 * whichever footer buttons that body provides. Save is a stub (Snackbar) for now;
 * Sign Out confirms here before clearing the session.
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val shell = inflater.inflate(R.layout.sheet_shell, container, false)
        shell.findViewById<TextView>(R.id.sheetTitle).setText(section.titleRes)

        val body = shell.findViewById<LinearLayout>(R.id.sheetBody)
        inflater.inflate(section.bodyRes, body, true)

        // Wire whichever footer buttons this body declares.
        body.findViewById<View>(R.id.sheetSaveButton)?.setOnClickListener { saveAndDismiss() }
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

    private fun saveAndDismiss() {
        requireActivity().findViewById<View>(android.R.id.content)?.let {
            Snackbar.make(it, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
        }
        dismiss()
    }

    private fun signOut() {
        AuthManager.getInstance(requireContext()).logout()
        startActivity(
            Intent(requireContext(), LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        requireActivity().finish()
    }

    companion object {
        private const val ARG_SECTION = "section"

        fun newInstance(section: Section) = SettingsSheetFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section.name) }
        }
    }
}

package com.app.taskade_mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.core.SettingsStore
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import com.app.taskade_mobile.location.LocationProvider
import com.app.taskade_mobile.onboarding.PromptPlayer
import com.app.taskade_mobile.onboarding.VoiceInput
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * First-run onboarding — an app-orchestrated, voice-first flow that captures the
 * user's preferred name, location (with consent), and daily check-in hour, then
 * marks the profile onboarded.
 *
 * DIVYA speaks each prompt in her own voice (server `/tts`); the user answers by
 * voice (on-device [VoiceInput]) OR by typing — every answer lands in an editable
 * field they confirm with "Continue", so speech-recognition slips never corrupt
 * the data. Location uses a spoken consent in front of the OS permission dialog.
 *
 * The flow is skippable and never blocks: any failure falls back to typing /
 * proceeding, and only a successful save flips the server-side onboarding gate.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { NAME, LOCATION, CHECKIN }

    private lateinit var stepLabel: android.widget.TextView
    private lateinit var promptText: android.widget.TextView
    private lateinit var hintText: android.widget.TextView
    private lateinit var inputLayout: TextInputLayout
    private lateinit var inputField: TextInputEditText
    private lateinit var micButton: MaterialButton
    private lateinit var primaryButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var progressBar: ProgressBar

    private val promptPlayer by lazy {
        PromptPlayer(ServiceLocator.get(this).apiProvider.apiService, this)
    }
    private val voiceInput by lazy { VoiceInput(this) }
    private val locationProvider by lazy { LocationProvider(this) }

    private var suggestedName: String? = null

    // Collected answers.
    private var enteredName: String = ""
    private var locationLabel: String? = null
    private var locationTimezone: String? = null
    private var checkinHour: Int? = null

    private var step = Step.NAME
    private var busy = false

    // --- permission launchers ---
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast(getString(R.string.onboarding_mic_unavailable))
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) resolveLocationThenAdvance() else goto(Step.CHECKIN)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        ServiceLocator.init(this)

        stepLabel = findViewById(R.id.stepLabel)
        promptText = findViewById(R.id.promptText)
        hintText = findViewById(R.id.hintText)
        inputLayout = findViewById(R.id.inputLayout)
        inputField = findViewById(R.id.inputField)
        micButton = findViewById(R.id.micButton)
        primaryButton = findViewById(R.id.primaryButton)
        secondaryButton = findViewById(R.id.secondaryButton)
        skipButton = findViewById(R.id.skipButton)
        progressBar = findViewById(R.id.progressBar)

        suggestedName = intent.getStringExtra(EXTRA_SUGGESTED_NAME)?.takeIf { it.isNotBlank() }

        micButton.setOnClickListener { onMicTap() }
        primaryButton.setOnClickListener { onPrimary() }
        secondaryButton.setOnClickListener { onSecondary() }
        skipButton.setOnClickListener { skip() }
        // Keep the latest typed value as the source of truth for the name step.
        inputField.doAfterTextChanged {
            if (step == Step.NAME) enteredName = it?.toString()?.trim().orEmpty()
        }

        render(Step.NAME)
    }

    override fun onDestroy() {
        promptPlayer.stop()
        voiceInput.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // No half-onboarded dead-ends: back = skip (best-effort save + go to app).
        if (!busy) skip()
    }

    // ── rendering ───────────────────────────────────────────────────────
    private fun goto(next: Step) {
        step = next
        render(next)
    }

    private fun render(s: Step) {
        promptPlayer.stop()
        when (s) {
            Step.NAME -> {
                promptText.text = getString(R.string.onboarding_name_prompt)
                hintText.text = getString(R.string.onboarding_name_hint)
                inputLayout.visibility = View.VISIBLE
                inputLayout.hint = getString(R.string.onboarding_answer_hint)
                inputField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                inputField.setText(enteredName.ifEmpty { suggestedName ?: "" })
                inputField.setSelection(inputField.text?.length ?: 0)
                micButton.visibility = View.VISIBLE
                primaryButton.text = getString(R.string.onboarding_continue)
                secondaryButton.visibility = View.GONE
                speak(getString(R.string.onboarding_name_spoken))
            }
            Step.LOCATION -> {
                promptText.text = getString(R.string.onboarding_location_prompt)
                hintText.text = getString(R.string.onboarding_location_hint)
                inputLayout.visibility = View.GONE
                micButton.visibility = View.GONE
                primaryButton.text = getString(R.string.onboarding_location_allow)
                secondaryButton.visibility = View.VISIBLE
                secondaryButton.text = getString(R.string.onboarding_not_now)
                speak(getString(R.string.onboarding_location_spoken))
            }
            Step.CHECKIN -> {
                promptText.text = getString(R.string.onboarding_checkin_prompt)
                hintText.text = getString(R.string.onboarding_checkin_hint)
                inputLayout.visibility = View.VISIBLE
                inputLayout.hint = getString(R.string.onboarding_checkin_field_hint)
                inputField.inputType = InputType.TYPE_CLASS_TEXT
                inputField.setText("")
                micButton.visibility = View.VISIBLE
                primaryButton.text = getString(R.string.onboarding_continue)
                secondaryButton.visibility = View.GONE
                speak(getString(R.string.onboarding_checkin_spoken))
            }
        }
    }

    // ── voice ───────────────────────────────────────────────────────────
    private fun speak(text: String) {
        lifecycleScope.launch { promptPlayer.speak(text) }
    }

    private fun onMicTap() {
        if (!voiceInput.isAvailable()) {
            toast(getString(R.string.onboarding_mic_unavailable))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        promptPlayer.stop() // don't record DIVYA's own voice
        val lang = resources.configuration.locales[0].toLanguageTag()
        micButton.isEnabled = false
        val original = micButton.text
        micButton.text = getString(R.string.onboarding_listening)
        lifecycleScope.launch {
            val result = voiceInput.listen(lang)
            micButton.isEnabled = true
            micButton.text = original
            if (!result.isNullOrBlank()) {
                inputField.setText(result.trim())
                inputField.setSelection(inputField.text?.length ?: 0)
                if (step == Step.NAME) enteredName = result.trim()
            }
        }
    }

    // ── step actions ────────────────────────────────────────────────────
    private fun onPrimary() {
        if (busy) return
        when (step) {
            Step.NAME -> {
                val name = inputField.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    inputLayout.error = getString(R.string.onboarding_name_required)
                    return
                }
                inputLayout.error = null
                enteredName = name
                goto(Step.LOCATION)
            }
            Step.LOCATION -> requestLocation()
            Step.CHECKIN -> {
                checkinHour = parseHour(inputField.text?.toString())
                save()
            }
        }
    }

    private fun onSecondary() {
        if (busy) return
        if (step == Step.LOCATION) goto(Step.CHECKIN)
    }

    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            resolveLocationThenAdvance()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun resolveLocationThenAdvance() {
        setBusy(true)
        lifecycleScope.launch {
            val place = runCatching { locationProvider.resolve() }.getOrNull()
            if (place != null) {
                locationLabel = place.label
                locationTimezone = place.timezone
            }
            setBusy(false)
            goto(Step.CHECKIN)
        }
    }

    private fun save() {
        val name = enteredName.ifEmpty { suggestedName ?: getString(R.string.home_default_user) }
        setBusy(true)
        lifecycleScope.launch {
            val result = ServiceLocator.profileRepository.completeOnboarding(
                displayName = name,
                location = locationLabel,
                timezone = locationTimezone ?: TimeZone.getDefault().id,
                dailyCheckinHour = checkinHour,
            )
            if (result.isSuccess) {
                SettingsStore.getInstance(this@OnboardingActivity).onboardingComplete = true
                finishOnboarding(name)
            } else {
                setBusy(false)
                toast(getString(R.string.onboarding_save_failed))
            }
        }
    }

    /** Skip = persist just the (suggested/typed) name so the gate flips, then go. */
    private fun skip() {
        val name = enteredName.ifEmpty { suggestedName ?: getString(R.string.home_default_user) }
        setBusy(true)
        lifecycleScope.launch {
            val result = ServiceLocator.profileRepository.completeOnboarding(
                displayName = name,
                timezone = TimeZone.getDefault().id,
            )
            if (result.isSuccess) {
                SettingsStore.getInstance(this@OnboardingActivity).onboardingComplete = true
            }
            // Whether or not it succeeded, don't trap the user on this screen.
            goToChat()
        }
    }

    private fun finishOnboarding(name: String) {
        speak(getString(R.string.onboarding_done_spoken, name))
        primaryButton.postDelayed({ goToChat() }, DONE_DELAY_MS)
    }

    private fun goToChat() {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun setBusy(value: Boolean) {
        busy = value
        progressBar.visibility = if (value) View.VISIBLE else View.GONE
        primaryButton.isEnabled = !value
        secondaryButton.isEnabled = !value
        micButton.isEnabled = !value
        skipButton.isEnabled = !value
        inputField.isEnabled = !value
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Parse "7am" / "7 am" / "19" / "7:30 pm" / "noon" into an hour 0-23, or null. */
    private fun parseHour(raw: String?): Int? {
        val text = raw?.lowercase()?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.contains("noon")) return 12
        if (text.contains("midnight")) return 0
        val num = Regex("\\d{1,2}").find(text)?.value?.toIntOrNull() ?: return null
        var hour = num
        val pm = text.contains("pm")
        val am = text.contains("am")
        if (pm && hour < 12) hour += 12
        if (am && hour == 12) hour = 0
        return hour.coerceIn(0, 23)
    }

    companion object {
        private const val EXTRA_SUGGESTED_NAME = "extra_suggested_name"
        private const val DONE_DELAY_MS = 1500L

        fun intent(context: Context, suggestedName: String?): Intent =
            Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_SUGGESTED_NAME, suggestedName)
            }
    }
}

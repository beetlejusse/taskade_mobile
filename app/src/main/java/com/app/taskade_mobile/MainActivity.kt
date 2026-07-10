package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.core.ServiceLocator
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Authenticated home screen. Acts as the app's gatekeeper: if there is no valid
 * session it immediately redirects to [LoginActivity]; otherwise it loads and
 * displays the user's profile and exposes a logout action.
 */
class MainActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }

    private lateinit var welcomeText: TextView
    private lateinit var emailText: TextView
    private lateinit var logoutButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        // Gate the screen: no session -> straight to login.
        if (!authManager.isAuthenticated) {
            navigateToLogin()
            return
        }

        welcomeText = findViewById(R.id.welcomeText)
        emailText = findViewById(R.id.emailText)
        logoutButton = findViewById(R.id.logoutButton)
        progressBar = findViewById(R.id.homeProgress)

        logoutButton.setOnClickListener { startLogout() }

        loadProfile()
    }

    private fun loadProfile() {
        setLoading(true)
        authManager.getCredentials(
            onSuccess = { credentials ->
                authManager.getProfile(
                    accessToken = credentials.accessToken,
                    onSuccess = { profile ->
                        setLoading(false)
                        welcomeText.text =
                            getString(R.string.home_welcome, profile.name ?: getString(R.string.home_default_user))
                        emailText.text = profile.email ?: ""
                    },
                    onFailure = { error ->
                        setLoading(false)
                        Toast.makeText(
                            this,
                            getString(R.string.profile_failed, error.getDescription()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            },
            onFailure = {
                // Credentials are gone or could not be refreshed -> force re-login.
                navigateToLogin()
            }
        )
    }

    private fun startLogout() {
        // Best-effort: drop this device's push token while the session is still
        // valid (bounded so a slow network can't block sign-out), THEN clear local
        // creds and navigate. Native logins keep no browser session to sign out of.
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                withTimeout(5_000) { ServiceLocator.deviceTokenRepository.unregisterCurrentToken() }
            }
            authManager.logout()
            navigateToLogin()
        }
    }

    private fun navigateToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        logoutButton.isEnabled = !loading
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

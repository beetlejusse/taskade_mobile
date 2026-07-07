package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.auth.AuthValidators
import com.app.taskade_mobile.auth.GoogleAuthClient
import com.app.taskade_mobile.ui.AuthAnimations
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Entry screen for unauthenticated users. Offers native email/password login and
 * a native "Continue with Google" flow, plus a link to [SignupActivity]. On
 * success it hands off to [MainActivity]. No browser is involved.
 */
class LoginActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }
    private val googleAuthClient by lazy { GoogleAuthClient(this) }

    private lateinit var emailLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var googleButton: MaterialButton
    private lateinit var goToSignupButton: MaterialButton
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_login)
        applyWindowInsets()

        emailLayout = findViewById(R.id.emailInputLayout)
        passwordLayout = findViewById(R.id.passwordInputLayout)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        googleButton = findViewById(R.id.googleButton)
        goToSignupButton = findViewById(R.id.goToSignupButton)
        progressBar = findViewById(R.id.loginProgress)

        loginButton.setOnClickListener { startEmailLogin() }
        googleButton.setOnClickListener { startGoogleLogin() }
        goToSignupButton.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        AuthAnimations.playEntrance(
            sheet = findViewById<MaterialCardView>(R.id.authCard),
            content = findViewById<ViewGroup>(R.id.loginForm)
        )
    }

    private fun startEmailLogin() {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        if (!validate(email, password)) return

        setLoading(true)
        authManager.loginWithEmail(
            email = email,
            password = password,
            onSuccess = { navigateToHome() },
            onFailure = { error ->
                setLoading(false)
                toast(getString(R.string.login_failed, error.getDescription()))
            }
        )
    }

    private fun startGoogleLogin() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val idToken = googleAuthClient.getGoogleIdToken(this@LoginActivity)
                authManager.loginWithGoogle(
                    googleIdToken = idToken,
                    onSuccess = { navigateToHome() },
                    onFailure = { error ->
                        setLoading(false)
                        toast(getString(R.string.google_login_failed, error.getDescription()))
                    }
                )
            } catch (cancelled: GetCredentialCancellationException) {
                // User dismissed the Google sheet — not an error worth surfacing.
                setLoading(false)
            } catch (error: Exception) {
                setLoading(false)
                toast(getString(R.string.google_login_failed, error.localizedMessage ?: ""))
            }
        }
    }

    private fun validate(email: String, password: String): Boolean {
        val emailError = AuthValidators.emailError(email)
        val passwordError = AuthValidators.passwordError(password)
        emailLayout.error = emailError?.let(::getString)
        passwordLayout.error = passwordError?.let(::getString)
        return emailError == null && passwordError == null
    }

    private fun navigateToHome() {
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        loginButton.isEnabled = !loading
        googleButton.isEnabled = !loading
        goToSignupButton.isEnabled = !loading
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.loginRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

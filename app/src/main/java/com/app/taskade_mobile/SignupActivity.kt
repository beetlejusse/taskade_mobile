package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.auth.AuthValidators
import com.app.taskade_mobile.ui.AuthAnimations
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Native email/password registration screen. On success Auth0 returns
 * credentials directly (the user is signed in immediately) and we hand off to
 * [MainActivity]. A link returns to [LoginActivity] for existing users.
 */
class SignupActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }

    private lateinit var emailLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var confirmLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var confirmInput: TextInputEditText
    private lateinit var signupButton: MaterialButton
    private lateinit var goToLoginButton: MaterialButton
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_signup)
        applyWindowInsets()

        emailLayout = findViewById(R.id.emailInputLayout)
        passwordLayout = findViewById(R.id.passwordInputLayout)
        confirmLayout = findViewById(R.id.confirmInputLayout)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        confirmInput = findViewById(R.id.confirmInput)
        signupButton = findViewById(R.id.signupButton)
        goToLoginButton = findViewById(R.id.goToLoginButton)
        progressBar = findViewById(R.id.signupProgress)

        signupButton.setOnClickListener { startSignup() }
        goToLoginButton.setOnClickListener { finish() }

        AuthAnimations.playEntrance(
            sheet = findViewById<MaterialCardView>(R.id.authCard),
            content = findViewById<ViewGroup>(R.id.signupForm)
        )
    }

    private fun startSignup() {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        val confirm = confirmInput.text?.toString().orEmpty()
        if (!validate(email, password, confirm)) return

        setLoading(true)
        authManager.signUpWithEmail(
            email = email,
            password = password,
            onSuccess = { navigateToHome() },
            onFailure = { error ->
                setLoading(false)
                toast(getString(R.string.signup_failed, error.getDescription()))
            }
        )
    }

    private fun validate(email: String, password: String, confirm: String): Boolean {
        val emailError = AuthValidators.emailError(email)
        val passwordError = AuthValidators.passwordError(password)
        val confirmError = AuthValidators.confirmPasswordError(password, confirm)
        emailLayout.error = emailError?.let(::getString)
        passwordLayout.error = passwordError?.let(::getString)
        confirmLayout.error = confirmError?.let(::getString)
        return emailError == null && passwordError == null && confirmError == null
    }

    private fun navigateToHome() {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        signupButton.isEnabled = !loading
        goToLoginButton.isEnabled = !loading
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.signupRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

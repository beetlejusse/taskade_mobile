package com.app.taskade_mobile.auth

import android.util.Patterns
import androidx.annotation.StringRes
import com.app.taskade_mobile.R

/**
 * Stateless input validation shared by the login and sign-up screens. Each
 * function returns a string resource describing the problem, or `null` when the
 * value is valid.
 */
object AuthValidators {

    const val MIN_PASSWORD_LENGTH = 8

    @StringRes
    fun emailError(email: String): Int? = when {
        email.isBlank() -> R.string.error_email_required
        !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> R.string.error_email_invalid
        else -> null
    }

    @StringRes
    fun passwordError(password: String): Int? = when {
        password.isEmpty() -> R.string.error_password_required
        password.length < MIN_PASSWORD_LENGTH -> R.string.error_password_too_short
        else -> null
    }

    @StringRes
    fun confirmPasswordError(password: String, confirm: String): Int? =
        if (password != confirm) R.string.error_passwords_mismatch else null
}

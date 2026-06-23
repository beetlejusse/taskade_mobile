package com.app.taskade_mobile.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.app.taskade_mobile.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID

/**
 * Thin wrapper around the Android [CredentialManager] that obtains a Google ID
 * token entirely in-app (the system "Sign in with Google" sheet, no browser).
 * The resulting token is meant to be handed to [AuthManager.loginWithGoogle] for
 * exchange into Auth0 credentials.
 *
 * Uses [GetSignInWithGoogleOption] (the button-initiated flow) rather than
 * GetGoogleIdOption: it always presents the account picker, so it works for a
 * first-time sign-in instead of failing with "no credentials available" when the
 * device has no previously-authorized account.
 *
 * The server client ID must be the Google **Web** OAuth client ID — the same one
 * configured in the Auth0 google-oauth2 connection — so the token's audience
 * matches what Auth0 trusts.
 */
class GoogleAuthClient(context: Context) {

    private val credentialManager = CredentialManager.create(context.applicationContext)
    private val webClientId = context.applicationContext.getString(R.string.google_web_client_id)

    /**
     * Launches the Google account picker and returns the selected account's ID
     * token. Suspends until the user picks an account or cancels.
     *
     * @param activityContext an Activity context so the picker UI launches in the
     *   correct task.
     * @throws GetCredentialException if the user cancels or no account is available.
     * @throws IllegalStateException if the returned credential is not a Google ID token.
     */
    suspend fun getGoogleIdToken(activityContext: Context): String {
        // Auth0's native social login rejects Google tokens without a nonce.
        // Google embeds this value in the issued ID token's `nonce` claim.
        val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(generateNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val result = credentialManager.getCredential(activityContext, request)
        val credential = result.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }

    /** A random, single-use nonce hashed with SHA-256 (per Google's guidance). */
    private fun generateNonce(): String {
        val raw = UUID.randomUUID().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

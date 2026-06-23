package com.app.taskade_mobile.auth

import android.content.Context
import com.app.taskade_mobile.R
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.result.Credentials
import com.auth0.android.result.UserProfile

/**
 * Single entry point for every Auth0 interaction in the app.
 *
 * Unlike the browser-based Universal Login flow, this manager authenticates
 * **natively** (no browser tab):
 *  - email / password against an Auth0 database connection, and
 *  - "Sign in with Google" by exchanging a Google ID token (obtained via the
 *    Credential Manager API, see [GoogleAuthClient]) for Auth0 credentials.
 *
 * Successful logins are persisted encrypted on device by a
 * [SecureCredentialsManager], so the rest of the app never touches the SDK.
 *
 * Use [getInstance] to obtain the shared instance.
 */
class AuthManager private constructor(context: Context) {

    private val account: Auth0 = Auth0.getInstance(
        context.getString(R.string.com_auth0_client_id),
        context.getString(R.string.com_auth0_domain)
    )

    private val authentication = AuthenticationAPIClient(account)

    /** Database connection email/password sign-ups and logins go through. */
    private val dbConnection = context.getString(R.string.auth0_db_connection)

    // Auth0.android 3.x builds the API client internally, so the manager takes the
    // [Auth0] account directly (the v2 AuthenticationAPIClient overload was removed).
    private val credentialsManager = SecureCredentialsManager(
        context.applicationContext,
        account,
        SharedPreferencesStorage(context.applicationContext)
    )

    /** True when a non-expired session (or a refreshable one) is stored on device. */
    val isAuthenticated: Boolean
        get() = credentialsManager.hasValidCredentials()

    /**
     * Authenticates an existing user with their email and password against the
     * Auth0 database connection. Requires the "Password" grant to be enabled on
     * the Auth0 application.
     */
    fun loginWithEmail(
        email: String,
        password: String,
        onSuccess: (Credentials) -> Unit,
        onFailure: (AuthenticationException) -> Unit
    ) {
        authentication
            .login(email, password, dbConnection)
            .setScope(SCOPE)
            .start(storingCallback(onSuccess, onFailure))
    }

    /**
     * Registers a new user and signs them in. On success Auth0 returns a fresh set
     * of [Credentials], which are stored just like a normal login.
     */
    fun signUpWithEmail(
        email: String,
        password: String,
        onSuccess: (Credentials) -> Unit,
        onFailure: (AuthenticationException) -> Unit
    ) {
        // Named `connection` disambiguates from the (email, password, username,
        // connection) overload so no username is required.
        authentication
            .signUp(email = email, password = password, connection = dbConnection)
            .setScope(SCOPE)
            .start(storingCallback(onSuccess, onFailure))
    }

    /**
     * Exchanges a Google ID token (from the native Credential Manager flow) for
     * Auth0 [Credentials] using token exchange. The Google connection in Auth0
     * must trust the client ID that issued the token (see [GoogleAuthClient]).
     */
    fun loginWithGoogle(
        googleIdToken: String,
        onSuccess: (Credentials) -> Unit,
        onFailure: (AuthenticationException) -> Unit
    ) {
        authentication
            .loginWithNativeSocialToken(googleIdToken, GOOGLE_ID_TOKEN_TYPE)
            .setScope(SCOPE)
            .start(storingCallback(onSuccess, onFailure))
    }

    /**
     * Clears the locally stored session. Native logins keep no browser session, so
     * there is nothing to sign out of remotely — wiping the encrypted credentials
     * is enough.
     */
    fun logout() {
        credentialsManager.clearCredentials()
    }

    /**
     * Returns a valid set of [Credentials], transparently refreshing them with the
     * stored refresh token when the access token has expired.
     */
    fun getCredentials(
        onSuccess: (Credentials) -> Unit,
        onFailure: (CredentialsManagerException) -> Unit
    ) {
        credentialsManager.getCredentials(object :
            Callback<Credentials, CredentialsManagerException> {
            override fun onSuccess(result: Credentials) = onSuccess(result)
            override fun onFailure(error: CredentialsManagerException) = onFailure(error)
        })
    }

    /** Fetches the authenticated user's profile from the Auth0 /userinfo endpoint. */
    fun getProfile(
        accessToken: String,
        onSuccess: (UserProfile) -> Unit,
        onFailure: (AuthenticationException) -> Unit
    ) {
        authentication.userInfo(accessToken)
            .start(object : Callback<UserProfile, AuthenticationException> {
                override fun onSuccess(result: UserProfile) = onSuccess(result)
                override fun onFailure(error: AuthenticationException) = onFailure(error)
            })
    }

    /**
     * Shared callback that persists credentials on success before forwarding the
     * result, so every authentication path stores its session the same way.
     */
    private fun storingCallback(
        onSuccess: (Credentials) -> Unit,
        onFailure: (AuthenticationException) -> Unit
    ) = object : Callback<Credentials, AuthenticationException> {
        override fun onSuccess(result: Credentials) {
            credentialsManager.saveCredentials(result)
            onSuccess(result)
        }

        override fun onFailure(error: AuthenticationException) = onFailure(error)
    }

    companion object {
        // offline_access is required for the credentials manager to refresh expired tokens.
        private const val SCOPE = "openid profile email offline_access"
        private const val GOOGLE_ID_TOKEN_TYPE =
            "http://auth0.com/oauth/token-type/google-id-token"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager =
            instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
    }
}

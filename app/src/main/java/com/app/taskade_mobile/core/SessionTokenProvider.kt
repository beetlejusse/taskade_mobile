package com.app.taskade_mobile.core

import android.content.Context
import android.util.Base64
import com.app.taskade_mobile.auth.AuthManager
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.result.Credentials
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Supplies a **fresh Auth0 ID token** to the networking layer.
 *
 * The backend authenticates every surface with the raw ID token — `Bearer` for
 * REST and `?token=` for the WebSocket (PRD §3.1). This provider is a thin,
 * coroutine-friendly adapter over the existing [AuthManager]; it does **not**
 * modify the auth implementation. [AuthManager.getCredentials] transparently
 * renews the token with the stored refresh token when it has expired, so callers
 * always receive a usable token (or an exception when the session is gone).
 */
interface SessionTokenProvider {
    /** Returns a valid raw ID token, refreshing it if necessary. */
    suspend fun freshIdToken(): String

    /** Blocking variant for the OkHttp interceptor (runs on a background thread). */
    fun freshIdTokenBlocking(): String
}

class Auth0TokenProvider(
    private val authManager: AuthManager
) : SessionTokenProvider {

    constructor(context: Context) : this(AuthManager.getInstance(context))

    // In-memory cache of the last ID token + its own `exp`. A single screen open fires
    // several REST calls; without this, each one decrypts the credential store. Auth0's
    // manager still transparently refreshes when the cached token nears expiry.
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiryMs: Long = 0L

    override suspend fun freshIdToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < cachedExpiryMs - EXPIRY_MARGIN_MS) return token
        }
        return suspendCancellableCoroutine { cont ->
            authManager.getCredentials(
                onSuccess = { credentials: Credentials ->
                    cachedToken = credentials.idToken
                    cachedExpiryMs = jwtExpiryMs(credentials.idToken)
                    cont.resume(credentials.idToken)
                },
                onFailure = { error: CredentialsManagerException -> cont.resumeWithException(error) }
            )
        }
    }

    override fun freshIdTokenBlocking(): String = runBlocking { freshIdToken() }

    /** Reads the `exp` (seconds) claim from a JWT → epoch millis, or 0 (never cache) if unreadable. */
    private fun jwtExpiryMs(jwt: String): Long = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return 0L
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        (Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLong() ?: 0L) * 1000L
    }.getOrDefault(0L)

    private companion object {
        /** Refresh a bit before the real expiry so an in-flight request never carries a just-expired token. */
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}

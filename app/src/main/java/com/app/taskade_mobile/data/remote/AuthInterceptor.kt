package com.app.taskade_mobile.data.remote

import android.util.Log
import com.app.taskade_mobile.core.SessionTokenProvider
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <raw_id_token>` to every REST request (PRD §3.1).
 *
 * The token is fetched fresh per-request from the [SessionTokenProvider], which
 * auto-renews it via the stored refresh token, so an expired access token never
 * reaches the network. OkHttp runs interceptors on a background thread, so the
 * blocking token fetch here is safe. If no session is available the request goes
 * out unauthenticated and the backend answers `401` — surfaced to the caller.
 */
class AuthInterceptor(
    private val tokenProvider: SessionTokenProvider
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runCatching { tokenProvider.freshIdTokenBlocking() }
            .onFailure { Log.w(TAG, "No valid session token for ${original.url}", it) }
            .getOrNull()

        val request = if (token != null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    private companion object {
        const val TAG = "AuthInterceptor"
    }
}

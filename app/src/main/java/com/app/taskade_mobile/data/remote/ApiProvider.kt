package com.app.taskade_mobile.data.remote

import com.app.taskade_mobile.BuildConfig
import com.app.taskade_mobile.core.ApiConfig
import com.app.taskade_mobile.core.BackendHostManager
import com.app.taskade_mobile.core.SessionTokenProvider
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds and holds the shared networking stack:
 *  - a lenient [Json] used by both REST (Retrofit) and the WebSocket layer,
 *  - a base [OkHttpClient] (also reused for the voice WebSocket — long read
 *    timeout, ping keep-alive), and
 *  - the [ApiService] Retrofit client (base client + [AuthInterceptor]).
 *
 * A single instance is created by `ServiceLocator`; do not construct per call.
 */
class ApiProvider(
    tokenProvider: SessionTokenProvider,
    hostManager: BackendHostManager
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Shared base client. The WebSocket reuses this (its own ping handled in-client). */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // The voice socket is long-lived and mostly idle between turns.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                )
            }
        }
        .build()

    private val restClient: OkHttpClient = httpClient.newBuilder()
        // REST needs REAL timeouts. The base client uses readTimeout(0) (infinite)
        // for the long-lived voice socket — inheriting that here would let a slow or
        // half-open host hang a request forever (and never throw, so failover would
        // never trigger). These bounds also convert a hung host into a failure that
        // the interceptor can fail over.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // Failover must wrap auth so a retried request re-runs AuthInterceptor and
        // re-attaches the bearer token for the failover host.
        .addInterceptor(HostFailoverInterceptor(hostManager))
        .addInterceptor(AuthInterceptor(tokenProvider))
        .build()

    val apiService: ApiService = Retrofit.Builder()
        .baseUrl(ApiConfig.apiBaseUrl + "/")
        .client(restClient)
        .addConverterFactory(KotlinxJsonConverterFactory(json, "application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)
}

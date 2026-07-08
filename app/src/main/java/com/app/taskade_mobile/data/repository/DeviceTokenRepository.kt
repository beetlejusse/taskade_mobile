package com.app.taskade_mobile.data.repository

import com.app.taskade_mobile.data.remote.ApiService
import com.app.taskade_mobile.data.remote.dto.DeviceTokenRequest
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers this device's FCM token with the backend so the reminder-delivery
 * sweep can push to it, and acknowledges deliveries back.
 *
 * Every method is best-effort (`Result`-wrapped) — push is an enhancement, so a
 * failure here must never disrupt the screen that called it. The backend upserts
 * by token and prunes dead ones, so re-registering is always safe.
 */
class DeviceTokenRepository(
    private val api: ApiService
) {

    /** Fetch the current FCM token and register it. */
    suspend fun registerCurrentToken(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Bound the FCM token fetch so a misconfigured Firebase can't hang.
            val token = withTimeout(15_000) { currentToken() }
            api.registerDevice(DeviceTokenRequest(token = token))
            Unit
        }
    }

    /** Register a specific token (used from onNewToken when FCM rotates it). */
    suspend fun register(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.registerDevice(DeviceTokenRequest(token = token))
            Unit
        }
    }

    /** Best-effort unregister of this device's current token, e.g. on logout. */
    suspend fun unregisterCurrentToken(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.unregisterDevice(DeviceTokenRequest(token = currentToken()))
            Unit
        }
    }

    /** Tell the backend a reminder notification actually surfaced (delivery proof). */
    suspend fun acknowledgeDelivered(reminderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.markReminderDelivered(reminderId)
                Unit
            }
        }

    /** Suspend until FCM yields the current registration token. */
    private suspend fun currentToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> cont.resume(token) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}

package com.app.taskade_mobile.notifications

import android.util.Log
import com.app.taskade_mobile.core.ServiceLocator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes from the backend reminder-delivery sweep.
 *
 * The backend sends DATA-ONLY messages, so [onMessageReceived] runs in both the
 * foreground and background. This service therefore:
 *   1. builds the notification itself (full control of look + tap routing), and
 *   2. acknowledges delivery back to the backend (`delivered_at`) — the
 *      end-to-end proof half of the zero-loss guarantee.
 * It also re-registers a rotated token via [onNewToken].
 */
class TaskadeMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // FCM rotated our token — push it to the backend so reminders keep
        // reaching this device. Best effort: only succeeds when a session exists.
        scope.launch {
            runCatching {
                ServiceLocator.init(applicationContext)
                ServiceLocator.deviceTokenRepository.register(token)
            }.onFailure { Log.w(TAG, "token re-register failed: ${it.message}") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "Reminder"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: "You have a task due."
        val taskId = data["task_id"]
        val reminderId = data["reminder_id"]

        NotificationHelper.show(applicationContext, title, body, taskId, reminderId)

        if (!reminderId.isNullOrBlank()) {
            scope.launch {
                runCatching {
                    ServiceLocator.init(applicationContext)
                    ServiceLocator.deviceTokenRepository.acknowledgeDelivered(reminderId)
                }.onFailure { Log.w(TAG, "delivery ack failed: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "TaskadeFCM"
    }
}

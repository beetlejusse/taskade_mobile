package com.app.taskade_mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.app.taskade_mobile.ChatActivity
import com.app.taskade_mobile.R

/**
 * Builds and posts reminder notifications, and owns the notification channel.
 *
 * The channel id MUST match the backend's `push_service.ANDROID_CHANNEL_ID` and
 * the `default_notification_channel_id` meta-data in the manifest, so a reminder
 * always lands on the same user-configurable channel.
 */
object NotificationHelper {

    const val CHANNEL_ID = "task_reminders"
    private const val CHANNEL_NAME = "Task reminders"
    private const val CHANNEL_DESC = "Notifications for your scheduled tasks and reminders."

    // Brand accent used to tint the small icon + app name on most launchers.
    private const val ACCENT_COLOR = 0xFF6C5CE7.toInt()
    // Small contextual label shown above the title in the shade.
    private const val SUB_TEXT = "Reminder"

    /** Extras carried on the tap intent so the app can route to the task later. */
    const val EXTRA_TASK_ID = "reminder_task_id"
    const val EXTRA_REMINDER_ID = "reminder_id"

    /** Create the channel (idempotent). Safe to call on every app start. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            enableLights(true)
            lightColor = ACCENT_COLOR
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** Build + post a reminder notification. No-op if the user disabled them. */
    fun show(
        context: Context,
        title: String,
        body: String,
        taskId: String?,
        reminderId: String?,
    ) {
        ensureChannel(context)

        val tapIntent = Intent(context, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!taskId.isNullOrBlank()) putExtra(EXTRA_TASK_ID, taskId)
            if (!reminderId.isNullOrBlank()) putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
        }
        // Stable per-reminder id so the at-time and the lead notification are
        // distinct entries rather than overwriting each other.
        val notifId = (reminderId ?: title).hashCode()
        val tapPending = PendingIntent.getActivity(context, notifId, tapIntent, piFlags)

        val largeIcon = runCatching {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        }.getOrNull()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT_COLOR)
            .setColorized(false)
            .setSubText(SUB_TEXT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(body)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(tapPending)

        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        val notification = builder.build()

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+ — silently skip.
        }
    }
}

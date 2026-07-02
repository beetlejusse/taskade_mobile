package com.app.taskade_mobile.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.app.taskade_mobile.ChatActivity
import com.app.taskade_mobile.R

/**
 * Hosts the live [VoiceSessionManager] in a foreground service (microphone +
 * dataSync types) so the socket and capture survive when the screen is off or the
 * app is backgrounded, behind a persistent "DIVYA is listening" notification
 * (PRD §5.5).
 *
 * The conversation screen [bind]s to read [VoiceSessionManager.state] /
 * [VoiceSessionManager.events] and drive controls; the service keeps running while
 * started even when no client is bound.
 */
class VoiceForegroundService : Service() {

    private val binder = LocalBinder()
    lateinit var sessionManager: VoiceSessionManager
        private set

    inner class LocalBinder : Binder() {
        val manager: VoiceSessionManager get() = sessionManager
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        sessionManager = VoiceSessionManager.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Promoting to a foreground service can be REJECTED by the platform on newer
        // Android versions even when it succeeded on older ones — e.g. the Android
        // 14+/15 microphone & dataSync foreground-service rules that Android 13 never
        // enforced (SecurityException / ForegroundServiceStartNotAllowedException /
        // MissingForegroundServiceTypeException). Never let that take down the whole
        // app: log it, tell the UI, and stop cleanly instead of crashing.
        try {
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the voice foreground service", t)
            runCatching { sessionManager.signalError(t.message ?: "voice unavailable on this device") }
            stopSelf()
            return START_NOT_STICKY
        }
        sessionManager.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessionManager.dispose()
        super.onDestroy()
    }

    private fun startInForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags()
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(getString(R.string.voice_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        private const val TAG = "VoiceFgService"
        private const val CHANNEL_ID = "voice_assistant"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.app.taskade_mobile.voice.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

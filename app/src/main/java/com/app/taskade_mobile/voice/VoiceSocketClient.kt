package com.app.taskade_mobile.voice

import android.util.Log
import com.app.taskade_mobile.core.ApiConfig
import com.app.taskade_mobile.core.SessionTokenProvider
import com.app.taskade_mobile.voice.protocol.ClientMessage
import com.app.taskade_mobile.voice.protocol.ServerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.resume

/**
 * The authenticated voice WebSocket (PRD §4). Mixed-frame: JSON **text** frames
 * for control/events and **binary** frames for raw PCM audio in both directions.
 *
 * Responsibilities:
 *  - open `wss://…/ws/voice?token=<fresh ID token>` (token fetched per attempt so
 *    it is always current — also satisfies the `1008` close → refresh flow),
 *  - decode incoming frames to [ServerEvent] (binary → [ServerEvent.AudioChunk]),
 *  - send outgoing audio ([sendAudio]) and control messages ([send]),
 *  - auto-reconnect with a short backoff while [start]ed.
 *
 * It is transport-only: it holds no audio or UI state. The
 * [VoiceSessionManager] consumes [events] / [connection] and drives the pipeline.
 */
class VoiceSocketClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val tokenProvider: SessionTokenProvider
) {
    enum class ConnectionState { Disconnected, Connecting, Connected }

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 512)
    val events = _events.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection = _connection.asStateFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var running = false
    private var loopJob: Job? = null

    /** Begins connecting and keeps the socket alive (reconnecting) until [stop]. */
    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        loopJob = scope.launch { connectLoop() }
    }

    /** Sends a raw PCM-16 audio frame. Returns false if the socket isn't open. */
    fun sendAudio(frame: ByteArray, length: Int = frame.size): Boolean =
        webSocket?.send(frame.toByteString(0, length)) ?: false

    /** Sends a JSON control message. Returns false if the socket isn't open. */
    fun send(message: ClientMessage): Boolean =
        webSocket?.send(ClientMessage.encode(message)) ?: false

    /** Permanently closes the socket and stops reconnecting. */
    fun stop() {
        running = false
        loopJob?.cancel()
        webSocket?.close(NORMAL_CLOSE, "client closing")
        webSocket = null
        _connection.value = ConnectionState.Disconnected
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (running) {
            try {
                val token = tokenProvider.freshIdToken()
                val request = Request.Builder()
                    .url(ApiConfig.voiceSocketUrl(token))
                    .build()
                _connection.value = ConnectionState.Connecting
                val everOpened = runSocket(request)
                attempt = if (everOpened) 0 else attempt + 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Voice socket attempt failed", e)
                _events.tryEmit(ServerEvent.Error(e.message ?: "connection failed"))
                attempt++
            }
            if (!running) break
            delay(backoffMs(attempt))
        }
        _connection.value = ConnectionState.Disconnected
    }

    /** Opens the socket and suspends until it closes/fails. Returns whether it ever opened. */
    private suspend fun runSocket(request: Request): Boolean =
        suspendCancellableCoroutine { cont ->
            var everOpened = false
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    everOpened = true
                    webSocket = ws
                    _connection.value = ConnectionState.Connected
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    _events.tryEmit(ServerEvent.parse(json, text))
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    _events.tryEmit(ServerEvent.AudioChunk(bytes.toByteArray()))
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(NORMAL_CLOSE, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Socket closed: $code $reason")
                    webSocket = null
                    _connection.value = ConnectionState.Disconnected
                    if (cont.isActive) cont.resume(everOpened)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Socket failure (${response?.code})", t)
                    webSocket = null
                    _connection.value = ConnectionState.Disconnected
                    if (cont.isActive) cont.resume(everOpened)
                }
            }

            val ws = httpClient.newWebSocket(request, listener)
            cont.invokeOnCancellation { ws.cancel() }
        }

    private fun backoffMs(attempt: Int): Long =
        (BASE_BACKOFF_MS * attempt.coerceAtLeast(1)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val TAG = "VoiceSocketClient"
        const val NORMAL_CLOSE = 1000
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 10_000L
    }
}

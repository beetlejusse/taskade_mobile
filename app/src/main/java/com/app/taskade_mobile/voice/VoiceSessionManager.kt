package com.app.taskade_mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.voice.audio.AudioPlayer
import com.app.taskade_mobile.voice.audio.MicStreamer
import com.app.taskade_mobile.voice.audio.PcmUtils
import com.app.taskade_mobile.voice.protocol.ClientMessage
import com.app.taskade_mobile.voice.protocol.ServerEvent
import com.app.taskade_mobile.voice.vad.VadDetector
import com.app.taskade_mobile.voice.vad.VadModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The heart of the voice feature: a coordinator that wires the WebSocket, mic
 * capture, VAD/barge-in and playback into the [VoiceState] machine (PRD §9), and
 * exposes [state] + [events] for the UI to render. It holds no views.
 *
 * Frame flow:
 *  - while **not** speaking → each mic frame is streamed to the server as binary
 *    (kept flowing through silence so Deepgram can fire end-of-turn — PRD §9),
 *  - while **speaking** → frames feed the [VadDetector]; a confirmed speech onset
 *    triggers barge-in: stop playback, send `interrupt`, flush the ~500 ms ring
 *    buffer, and resume streaming (PRD §4.4).
 *
 * Construct via [create]; call [start] (after `RECORD_AUDIO` is granted) and
 * [stop] for the lifecycle.
 */
class VoiceSessionManager private constructor(
    private val appContext: Context,
    private val socket: VoiceSocketClient,
    private val vad: VadDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL

    private val player = AudioPlayer(onTurnDrained = { onPlaybackDrained() })
    private val mic = MicStreamer(onFrame = { onMicFrame(it) })

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceUiEvent>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    /** The current tool name while one is running, for the status line. */
    private val _activeTool = MutableStateFlow<String?>(null)
    val activeTool = _activeTool.asStateFlow()

    @Volatile private var started = false
    @Volatile private var bargeInTriggered = false

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        requestAudioFocus()
        routeAudioToSpeaker()
        observeSocket()
        socket.start(scope)
    }

    fun stop() {
        if (!started) return
        started = false
        socket.stop()
        mic.stop()
        player.stopNow()
        vad.reset()
        restoreAudioRouting()
        abandonAudioFocus()
        _state.value = VoiceState.Idle
    }

    fun dispose() {
        stop()
        vad.close()
        scope.cancel()
    }

    /**
     * Surfaces a non-recoverable startup failure (e.g. the platform refused to start
     * the foreground service) to the UI as an [VoiceUiEvent.Error] and returns the
     * machine to [VoiceState.Idle], without ever throwing.
     */
    fun signalError(message: String) {
        _events.tryEmit(VoiceUiEvent.Error(message))
        _state.value = VoiceState.Idle
    }

    // ---- outbound controls -------------------------------------------------

    /** Push a freshly-resolved location into the live session (PRD §7). */
    fun pushLocation(location: String) {
        socket.send(ClientMessage.Location(location))
    }

    /** Clears the server-side conversation history. */
    fun clearHistory() {
        socket.send(ClientMessage.ClearHistory)
    }

    // ---- socket event handling --------------------------------------------

    private fun observeSocket() {
        scope.launch {
            socket.connection.collect { conn ->
                when (conn) {
                    VoiceSocketClient.ConnectionState.Connecting ->
                        if (_state.value == VoiceState.Idle) _state.value = VoiceState.Connecting
                    VoiceSocketClient.ConnectionState.Connected -> onConnected()
                    VoiceSocketClient.ConnectionState.Disconnected ->
                        if (started) _state.value = VoiceState.Connecting
                }
            }
        }
        scope.launch {
            socket.events.collect { handleEvent(it) }
        }
    }

    private fun onConnected() {
        if (!mic.isRunning) mic.start()
        // Don't override an in-flight turn that resumed mid-reconnect.
        if (_state.value == VoiceState.Connecting || _state.value == VoiceState.Idle) {
            _state.value = VoiceState.Listening
        }
    }

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.Processing -> {
                _state.value = VoiceState.Processing
            }
            is ServerEvent.SttInterim -> {
                if (!player.isActive) _state.value = VoiceState.Recording
                _events.tryEmit(VoiceUiEvent.Interim(event.text))
            }
            is ServerEvent.SttFinal -> _events.tryEmit(VoiceUiEvent.Interim(event.text))
            is ServerEvent.SttResult -> _events.tryEmit(VoiceUiEvent.UserFinal(event.text))
            ServerEvent.SttReconnecting -> Unit
            is ServerEvent.LlmToken -> {
                if (_state.value != VoiceState.Speaking) _state.value = VoiceState.Processing
                _events.tryEmit(VoiceUiEvent.AssistantDelta(event.text))
            }
            is ServerEvent.ToolStart -> {
                _activeTool.value = event.name
                _events.tryEmit(VoiceUiEvent.Tool(event.name, running = true))
            }
            is ServerEvent.ToolResult -> {
                _activeTool.value = null
                _events.tryEmit(VoiceUiEvent.Tool(event.name, running = false, ok = event.ok))
                _events.tryEmit(VoiceUiEvent.TasksChanged)
            }
            is ServerEvent.Metadata -> {
                if (event.tasks.isNotEmpty()) _events.tryEmit(VoiceUiEvent.Metadata(event.tasks))
            }
            is ServerEvent.LlmDone -> _events.tryEmit(VoiceUiEvent.AssistantFinal(event.text))
            is ServerEvent.TtsStart -> {
                bargeInTriggered = false
                vad.reset()
                _state.value = VoiceState.Speaking
                player.beginTurn(event.sampleRate)
            }
            is ServerEvent.AudioChunk -> player.enqueue(event.pcm)
            ServerEvent.TtsDone -> player.endTurn()
            is ServerEvent.Error -> {
                _events.tryEmit(VoiceUiEvent.Error(event.message))
                resumeListening()
            }
            ServerEvent.Interrupted -> {
                player.stopNow()
                _state.value = VoiceState.Recording
            }
            ServerEvent.HistoryCleared -> _events.tryEmit(VoiceUiEvent.HistoryCleared)
            ServerEvent.Pong -> Unit
            is ServerEvent.Unknown ->
                Log.d(TAG, "Unknown server event: ${event.type}")
        }
    }

    private fun onPlaybackDrained() {
        // Fired on the player's writer thread after tts.done fully drains.
        resumeListening()
    }

    private fun resumeListening() {
        if (started) _state.value = VoiceState.Listening
    }

    // ---- mic frame handling (capture thread) -------------------------------

    private fun onMicFrame(frame: ShortArray) {
        if (!started) return
        if (player.isActive) {
            // Assistant is speaking → watch for barge-in only (don't stream).
            val speaking = vad.process(frame)
            if (speaking && !bargeInTriggered) handleBargeIn()
        } else {
            // Listening / recording / processing → stream continuously.
            socket.sendAudio(PcmUtils.shortsToLeBytes(frame))
        }
    }

    private fun handleBargeIn() {
        bargeInTriggered = true
        player.stopNow()
        socket.send(ClientMessage.Interrupt)
        // Flush the pre-speech ring buffer so the words that triggered the
        // barge-in aren't lost (PRD §4.4).
        for (f in mic.ringSnapshot()) {
            socket.sendAudio(PcmUtils.shortsToLeBytes(f))
        }
        vad.reset()
        _state.value = VoiceState.Recording
    }

    // ---- audio focus -------------------------------------------------------

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = req
            runCatching { audioManager.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
    }

    // ---- speaker routing (loud playback) -----------------------------------

    /**
     * Routes the assistant's voice to the **loudspeaker** at a usable volume.
     *
     * Playback uses `USAGE_VOICE_COMMUNICATION` (so the platform AEC couples it with
     * the mic for barge-in), but that otherwise routes to the quiet earpiece. We put
     * the device in communication mode and force the built-in speaker — the VoIP
     * pattern that keeps AEC while being loud. Restored in [restoreAudioRouting].
     */
    private fun routeAudioToSpeaker() {
        previousAudioMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) runCatching { audioManager.setCommunicationDevice(speaker) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = true }
        }
    }

    private fun restoreAudioRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.isSpeakerphoneOn = false }
        }
        runCatching { audioManager.mode = previousAudioMode }
    }

    companion object {
        private const val TAG = "VoiceSessionManager"

        /** Builds a manager wired to the shared networking stack + a device VAD. */
        fun create(context: Context): VoiceSessionManager {
            val app = context.applicationContext
            ServiceLocator.init(app)
            // Short connect timeout so an unreachable host is caught fast and the
            // socket can fail over; the read timeout stays 0 (long-lived socket).
            val wsClient = ServiceLocator.apiProvider.httpClient.newBuilder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val socket = VoiceSocketClient(
                httpClient = wsClient,
                json = ServiceLocator.apiProvider.json,
                tokenProvider = ServiceLocator.tokenProvider,
                hostManager = ServiceLocator.backendHostManager
            )
            val vad = VadDetector(VadModel.Factory.create(app))
            return VoiceSessionManager(app, socket, vad)
        }
    }
}

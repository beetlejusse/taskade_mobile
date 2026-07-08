package com.app.taskade_mobile.core

import com.app.taskade_mobile.BuildConfig

/**
 * Single source of truth for the backend endpoints and the shared audio format
 * constants the voice pipeline depends on.
 *
 * The host values come from [BuildConfig] (set per build type in
 * `app/build.gradle.kts`, mirroring `client/.env`, see ANDROID_INTEGRATION_PRD §10):
 *  - debug   → `http://10.0.2.2:8000` / `ws://10.0.2.2:8000/ws/voice`
 *  - release → the production host
 *
 * The audio constants are protocol-level and must match the backend exactly
 * (PRD §4.1 / §5): mic uplink is PCM-16 mono @16 kHz; TTS downlink sample rate is
 * announced dynamically in `tts.start` and must never be hardcoded for playback.
 */
object ApiConfig {

    /**
     * The backend hosts in **priority order**: `[primary, secondary?]`. The primary
     * comes from `API_BASE_URL` / `WS_VOICE_URL`; the secondary is appended only when
     * `*_FALLBACK` is non-blank (it's blank in debug, so local dev has a single host
     * and no failover). [BackendHostManager] uses this list to decide which host to
     * hit and when to fail over.
     */
    val hosts: List<BackendHost> = buildList {
        add(
            BackendHost(
                name = "primary",
                restBase = BuildConfig.API_BASE_URL.trimEnd('/'),
                wsVoice = BuildConfig.WS_VOICE_URL
            )
        )
        val fbRest = BuildConfig.API_BASE_URL_FALLBACK.trimEnd('/')
        val fbWs = BuildConfig.WS_VOICE_URL_FALLBACK
        if (fbRest.isNotBlank() && fbWs.isNotBlank()) {
            add(BackendHost(name = "secondary", restBase = fbRest, wsVoice = fbWs))
        }
    }

    /** REST base of the primary host, e.g. `https://api.example.com`. No trailing slash. */
    val apiBaseUrl: String = hosts.first().restBase

    /**
     * Builds the authenticated voice socket URL for a given WS base. The token must
     * travel as a query param because WebSockets cannot set request headers
     * (PRD §3.1 / §4). The base is chosen per-attempt from [BackendHostManager].
     */
    fun voiceSocketUrl(wsBase: String, rawIdToken: String): String {
        val sep = if (wsBase.contains('?')) '&' else '?'
        return "$wsBase${sep}token=$rawIdToken"
    }

    // ---- Audio format (protocol-level; do not change without the backend) ----

    /** Microphone uplink sample rate. Deepgram is configured for linear16 @16 kHz. */
    const val MIC_SAMPLE_RATE = 16_000

    /** Fallback playback rate if a `tts.start` ever omits `sampleRate` (currently 22050). */
    const val DEFAULT_TTS_SAMPLE_RATE = 22_050

    /** Frame size read from the mic, ≈32 ms @16 kHz — matches the web client cadence. */
    const val MIC_FRAME_SAMPLES = 512

    /** Rolling pre-speech buffer flushed on barge-in (~500 ms ≈ 15 frames). */
    const val RING_BUFFER_FRAMES = 15
}

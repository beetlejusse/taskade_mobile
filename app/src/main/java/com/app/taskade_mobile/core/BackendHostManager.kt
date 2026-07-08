package com.app.taskade_mobile.core

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray

/**
 * One backend deployment: the same stateless server, reachable at two addresses.
 * [restBase] has no trailing slash (e.g. `https://api.example.com`); [wsVoice] is
 * the voice WebSocket base (e.g. `wss://api.example.com/ws/voice`).
 */
data class BackendHost(
    val name: String,
    val restBase: String,
    val wsVoice: String,
)

/**
 * Client-side failover across the ordered [hosts] (`[primary, secondary?]`), shared
 * by BOTH the REST stack (via `HostFailoverInterceptor`) and the voice WebSocket, so
 * they can never disagree about which host is alive. See `BACKEND_FAILOVER_PLAN.md`.
 *
 * Behaviour:
 *  - **Prefer the primary:** [activeHost] returns the first host that is not currently
 *    sidelined, so we use `secondary` only while `primary` is down.
 *  - **Circuit breaker:** a host that fails a real request is *sidelined* for
 *    [cooldownMs]; after the cooldown a background prober re-checks `GET /health` and
 *    restores it only when it truly answers (auto-failback to the primary).
 *  - **Startup race:** on [start] all hosts are probed in parallel so the very first
 *    request already routes to a known-good host.
 *  - **Single-host (debug):** with one host there is nothing to fail over to, so
 *    [markDown] is a no-op and the host is never sidelined.
 *
 * `elapsedRealtime` (monotonic) backs the timers, so a wall-clock change can't skew them.
 */
class BackendHostManager(
    val hosts: List<BackendHost>,
    private val cooldownMs: Long = 30_000L,
    probeTimeoutMs: Long = 3_000L,
) {
    /** Per-host deadline: `0` = healthy/usable; otherwise the `elapsedRealtime` at which the prober may re-probe. */
    private val downUntil = AtomicLongArray(hosts.size)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Dedicated short-timeout client for `/health` probes (no auth, no logging). */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(probeTimeoutMs + 1_000, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var started = false

    // ---- selection (called on every REST request + each WS connect attempt) -----

    /** The host to use right now — the first non-sidelined host, else the primary (best effort). */
    fun activeHost(): BackendHost {
        for (i in hosts.indices) if (downUntil.get(i) == 0L) return hosts[i]
        return hosts.first()
    }

    /** The next healthy host that isn't [from], or `null` if none is currently available. */
    fun failover(from: BackendHost): BackendHost? {
        for (i in hosts.indices) {
            if (hosts[i] != from && downUntil.get(i) == 0L) return hosts[i]
        }
        return null
    }

    // ---- status reporting (called by the interceptor + the WS client) -----------

    /** Sideline [host] for the cooldown. No-op when there's only one host (nothing to fail over to). */
    fun markDown(host: BackendHost) = indexOf(host)?.let(::sideline) ?: Unit

    /** Clear any sidelining on [host] (a real request or a probe just succeeded). */
    fun markHealthy(host: BackendHost) = indexOf(host)?.let(::restore) ?: Unit

    // ---- lifecycle --------------------------------------------------------------

    /** Kicks the parallel startup probe and the background recovery prober. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            probeAll()
            proberLoop()
        }
    }

    // ---- internals --------------------------------------------------------------

    private fun sideline(i: Int) {
        if (hosts.size <= 1) return // nothing to fail over to — keep using the only host
        downUntil.set(i, SystemClock.elapsedRealtime() + cooldownMs)
        Log.w(TAG, "Host '${hosts[i].name}' (${hosts[i].restBase}) marked DOWN for ${cooldownMs}ms")
    }

    private fun restore(i: Int) {
        if (downUntil.getAndSet(i, 0L) != 0L) {
            Log.i(TAG, "Host '${hosts[i].name}' (${hosts[i].restBase}) recovered")
        }
    }

    private fun indexOf(host: BackendHost): Int? = hosts.indexOf(host).takeIf { it >= 0 }

    /** Startup race: probe every host in parallel and set its initial status truthfully. */
    private suspend fun probeAll() = coroutineScope {
        hosts.mapIndexed { i, host ->
            async { if (probe(host)) restore(i) else sideline(i) }
        }.awaitAll()
        Log.i(TAG, "Startup probe complete — active host = '${activeHost().name}'")
    }

    /** Re-probes only sidelined hosts once their cooldown elapses; restores or re-cools them. */
    private suspend fun proberLoop() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            val now = SystemClock.elapsedRealtime()
            hosts.forEachIndexed { i, host ->
                val until = downUntil.get(i)
                if (until != 0L && now >= until) {
                    if (probe(host)) restore(i)
                    else downUntil.set(i, SystemClock.elapsedRealtime() + cooldownMs)
                }
            }
        }
    }

    /** `true` iff `GET {restBase}/health` returns a 2xx within the probe timeout. */
    private suspend fun probe(host: BackendHost): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(host.restBase + "/health").get().build()
            probeClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "BackendHostManager"
        const val TICK_MS = 5_000L
    }
}

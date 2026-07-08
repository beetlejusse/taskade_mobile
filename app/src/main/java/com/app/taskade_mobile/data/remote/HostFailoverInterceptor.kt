package com.app.taskade_mobile.data.remote

import android.util.Log
import com.app.taskade_mobile.core.BackendHost
import com.app.taskade_mobile.core.BackendHostManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Client-side REST failover. Retargets each request to [hosts].activeHost(); if that
 * host is unreachable or returns a gateway error (502/503/504), it marks the host
 * down and resends the request **once** on the failover host. See
 * `BACKEND_FAILOVER_PLAN.md` §7.1 / §9 / §10.
 *
 * Must sit **outside** [AuthInterceptor] so the `Authorization` header is re-applied
 * to the retried request. It only ever touches our own backend hosts — it is added to
 * the REST client only, never to the shared client used for external image loads.
 *
 * Failover triggers (and *only* these): connection failures and 502/503/504. A plain
 * `500` (app bug) or a slow-but-successful response is NOT a failover — that would
 * just double an app error or punish a cold start.
 *
 * Retry safety: idempotent methods (GET/HEAD) always retry; writes retry **only** when
 * the request was provably never delivered (connect/DNS/TLS-handshake failure), so a
 * timed-out write can't be duplicated on the shared database.
 */
class HostFailoverInterceptor(
    private val hosts: BackendHostManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val primary = hosts.activeHost()

        val firstFailure: HostFailure = try {
            val response = attempt(chain, original, primary)
            hosts.markHealthy(primary)
            return response
        } catch (f: HostFailure) {
            hosts.markDown(primary)
            f
        }

        val next = if (isRetryable(original.method, firstFailure)) hosts.failover(primary) else null
        if (next == null) throw firstFailure.toIOException()

        Log.w(TAG, "Failover ${original.method} ${original.url.encodedPath}: '${primary.name}' -> '${next.name}'")
        return try {
            val response = attempt(chain, original, next)
            hosts.markHealthy(next)
            response
        } catch (f: HostFailure) {
            hosts.markDown(next)
            throw f.toIOException()
        }
    }

    /** Sends [request] to [host]; throws [HostFailure] on a connection error or gateway 5xx. */
    private fun attempt(chain: Interceptor.Chain, request: Request, host: BackendHost): Response {
        val response = try {
            chain.proceed(request.retargetTo(host))
        } catch (io: IOException) {
            throw HostFailure.Connection(io)
        }
        if (response.code in 502..504) {
            response.close()
            throw HostFailure.Gateway(response.code)
        }
        return response
    }

    private fun isRetryable(method: String, failure: HostFailure): Boolean {
        // Idempotent reads are always safe to retry on the other host.
        if (method.equals("GET", true) || method.equals("HEAD", true)) return true
        // Writes: only when the request was definitely never delivered, so we can't
        // duplicate a mutation the first host may already have applied.
        return failure is HostFailure.Connection && failure.ioCause.wasNeverDelivered()
    }

    /** Rewrites only the scheme/host/port to [host]; the resolved path/query is preserved. */
    private fun Request.retargetTo(host: BackendHost): Request {
        val base = host.restBase.toHttpUrl()
        val newUrl = url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return newBuilder().url(newUrl).build()
    }

    private fun IOException.wasNeverDelivered(): Boolean =
        this is ConnectException || this is UnknownHostException || this is SSLHandshakeException

    /** Internal signal that a host attempt failed in a way that warrants failover. */
    private sealed class HostFailure : Exception() {
        class Connection(val ioCause: IOException) : HostFailure()
        class Gateway(val code: Int) : HostFailure()

        fun toIOException(): IOException = when (this) {
            is Connection -> ioCause
            is Gateway -> IOException("Backend gateway error $code")
        }
    }

    private companion object {
        const val TAG = "HostFailover"
    }
}

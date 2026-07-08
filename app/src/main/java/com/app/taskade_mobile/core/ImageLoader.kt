package com.app.taskade_mobile.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal image fetcher (used for the Auth0 profile picture). Reuses the shared
 * [OkHttpClient] and decodes off the main thread — no extra image library.
 *
 * A small in-memory [LruCache] keyed by URL means the same avatar loads instantly on
 * every screen after the first fetch, instead of re-downloading and re-decoding it
 * each time the chat / settings / tasks headers appear.
 */
object ImageLoader {

    private val cache = LruCache<String, Bitmap>(8)

    /** The already-decoded bitmap for [url], or null if it hasn't been loaded yet. */
    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(client: OkHttpClient, url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }
}

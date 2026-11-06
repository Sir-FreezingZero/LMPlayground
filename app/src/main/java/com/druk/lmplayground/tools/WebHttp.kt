package com.druk.lmplayground.tools

import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What web_search and web_fetch share: one HTTP client (one connection pool
 * and thread pool instead of one per tool), the browser User-Agent, a
 * size-capped body read, and JSON error replies.
 */
internal object WebHttp {

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Most bytes read from any response; a page is cut to a few thousand characters anyway. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The body's bytes, at most [MAX_BODY_BYTES], and whether more was left unread. */
    fun readCapped(response: Response): Pair<ByteArray, Boolean> {
        val body = response.body ?: return ByteArray(0) to false
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        val buffered = source.buffer.size
        val bytes = source.buffer.readByteArray(minOf(buffered, MAX_BODY_BYTES))
        return bytes to (buffered > MAX_BODY_BYTES)
    }

    /** `{"error": message}`, escaped properly whatever the message holds. */
    fun errorJson(message: String): String = JSONObject().put("error", message).toString()
}

package com.druk.lmplayground.tools

import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * What the web tools share: one HTTP client (one connection and thread pool
 * instead of one per tool), the browser User-Agent, and size-capped reads.
 */
internal object WebHttp {

    /** The chat's context size in tokens, set at the start of each reply. */
    @Volatile
    var contextTokens: Int = 4096

    /**
     * How much of a page to hand the model: about 35% of the context window
     * (at roughly 4 characters a token), between 2,000 and 16,000 characters,
     * so one page can't crowd out the conversation on a small context.
     */
    fun pageChars(): Int = (contextTokens * 4 * 0.35).toInt().coerceIn(2_000, 16_000)

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Most bytes read from any response; a page is cut to a few thousand characters anyway. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The body's bytes, at most [MAX_BODY_BYTES]. */
    fun bytes(response: Response): ByteArray {
        val source = response.body?.source() ?: return ByteArray(0)
        source.request(MAX_BODY_BYTES)
        return source.buffer.readByteArray(minOf(source.buffer.size, MAX_BODY_BYTES))
    }

    /** The body as UTF-8 text, at most [MAX_BODY_BYTES]. */
    fun text(response: Response): String = String(bytes(response), Charsets.UTF_8)
}

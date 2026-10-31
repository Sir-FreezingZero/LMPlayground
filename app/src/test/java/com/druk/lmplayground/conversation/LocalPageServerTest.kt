package com.druk.lmplayground.conversation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [LocalPageServer] over real loopback sockets, the way a browser uses it. */
class LocalPageServerTest {

    @After
    fun tearDown() = LocalPageServer.stop()

    private fun get(url: String, method: String = "GET"): Triple<Int, String, String?> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Triple(code, body, c.getHeaderField("Content-Type"))
    }

    @Test
    fun `published page is served on loopback as utf-8 html`() {
        val html = "<!DOCTYPE html><html><body><h1>Café ✓</h1></body></html>"
        val url = LocalPageServer.publish(html, "tip-calculator.html")
        assertTrue(url, url.startsWith("http://127.0.0.1:"))
        assertTrue(url, url.endsWith("/tip-calculator.html"))
        val (code, body, type) = get(url)
        assertEquals(200, code)
        assertEquals(html, body)
        assertEquals("text/html; charset=utf-8", type)
    }

    @Test
    fun `query string from a reload is ignored`() {
        val url = LocalPageServer.publish("<p>x</p>", "page.html")
        assertEquals(200, get("$url?reload=1").first)
    }

    @Test
    fun `other paths are not found, including the server root`() {
        val url = LocalPageServer.publish("<p>secret</p>", "page.html")
        val root = url.substring(0, url.indexOf('/', "http://".length)) // http://127.0.0.1:PORT
        assertEquals(404, get("$root/").first)
        assertEquals(404, get("$root/page.html").first)
        assertEquals(404, get("$root/favicon.ico").first)
    }

    @Test
    fun `publishing a new page replaces the old one under a new address`() {
        val first = LocalPageServer.publish("<p>one</p>", "page.html")
        val second = LocalPageServer.publish("<p>two</p>", "page.html")
        assertNotEquals(first, second)
        assertEquals(404, get(first).first)
        assertEquals("<p>two</p>", get(second).second)
    }

    @Test
    fun `file names with spaces and non latin letters are encoded`() {
        val url = LocalPageServer.publish("<p>x</p>", "ゲーム page.html")
        assertTrue(url, url.endsWith("/%E3%82%B2%E3%83%BC%E3%83%A0%20page.html"))
        assertEquals(200, get(url).first)
    }

    @Test
    fun `a burst of parallel requests is served by the small worker pool`() {
        val url = LocalPageServer.publish("<p>burst</p>", "page.html")
        val pool = Executors.newFixedThreadPool(20)
        try {
            val results = (1..20).map { pool.submit(Callable { get(url) }) }.map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(results.all { it.first == 200 && it.second == "<p>burst</p>" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `head returns headers only and post is refused`() {
        val url = LocalPageServer.publish("<p>x</p>", "page.html")
        val (headCode, headBody, _) = get(url, "HEAD")
        assertEquals(200, headCode)
        assertEquals("", headBody)
        assertEquals(405, get(url, "POST").first)
    }
}

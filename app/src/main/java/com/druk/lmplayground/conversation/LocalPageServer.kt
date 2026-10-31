package com.druk.lmplayground.conversation

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny HTTP server on the phone's loopback address that serves the last
 * HTML artifact sent to "Open in browser".
 *
 * Browsers reliably open only http(s) addresses: handing them a local file
 * (a content:// URI) works in some browsers and not at all in others. Serving
 * the page at http://127.0.0.1:PORT/... works in every browser, and also
 * gives the page a real origin, so localStorage and fetch() to public APIs
 * behave like they do on a website.
 *
 * - Listens on 127.0.0.1 only, so nothing outside the phone can connect.
 * - The page lives under a random, unguessable path; any other request is a
 *   404, so other apps on the phone can't list or guess it.
 * - Holds one page at a time, in memory; publishing a new page replaces it.
 * - Started on first use and kept for the life of the app process: one
 *   daemon thread accepts connections and a small fixed pool (4 daemon
 *   threads) answers them, so a burst of requests can't spawn unbounded
 *   threads. If the process is killed, the browser keeps the page it already
 *   loaded; reloading it there fails until the next Open in browser.
 *
 * Plain java.net with no Android dependencies, so it is JVM-tested.
 */
object LocalPageServer {

    private class Page(val path: String, val body: ByteArray)

    @Volatile private var page: Page? = null
    private var server: ServerSocket? = null
    private val random = SecureRandom()

    /** Answers requests; daemon threads so it never keeps the process alive. */
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "LocalPageServer-worker").apply { isDaemon = true }
    }

    /**
     * Serve [html] and return its address, e.g.
     * `http://127.0.0.1:41234/Kx3…/tip-calculator.html`. Starts the server on
     * first use. Blocking (binds a socket): call it off the main thread.
     */
    @Synchronized
    fun publish(html: String, fileName: String): String {
        val socket = server?.takeUnless { it.isClosed } ?: start()
        val token = ByteArray(16).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
        val name = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val path = "/$token/$name"
        page = Page(path, html.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:${socket.localPort}$path"
    }

    /** Stop serving and forget the page. Used by tests; the app never needs it. */
    @Synchronized
    fun stop() {
        page = null
        try { server?.close() } catch (_: IOException) {}
        server = null
    }

    private fun start(): ServerSocket {
        // Port 0: the system picks a free port. Loopback only.
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(name = "LocalPageServer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break // closed by stop()
                }
                workers.execute { handle(client) }
            }
        }
        return socket
    }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 5_000
                val requestLine = readRequestLine(c) ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                // Ignore any query string (?v=2) a browser may add on reload.
                val target = parts.getOrNull(1).orEmpty().substringBefore('?')
                val current = page
                val out = c.getOutputStream()
                when {
                    method != "GET" && method != "HEAD" ->
                        out.write(response(405, "Method Not Allowed", ByteArray(0), "text/plain"))
                    current == null || target != current.path ->
                        out.write(response(404, "Not Found", "Not found".toByteArray(), "text/plain"))
                    else -> {
                        val body = if (method == "HEAD") ByteArray(0) else current.body
                        out.write(
                            response(200, "OK", body, "text/html; charset=utf-8", contentLength = current.body.size)
                        )
                    }
                }
                out.flush()
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            }
        }
    }

    /** Read the request line, then drain the headers up to the blank line. */
    private fun readRequestLine(c: Socket): String? {
        val input = BufferedInputStream(c.getInputStream())
        val head = StringBuilder()
        while (head.length < 16_384) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            // Stop at the end of the headers (a blank line).
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        return head.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun response(
        code: Int,
        reason: String,
        body: ByteArray,
        contentType: String,
        contentLength: Int = body.size,
    ): ByteArray {
        val headers = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return headers.toByteArray(Charsets.US_ASCII) + body
    }
}

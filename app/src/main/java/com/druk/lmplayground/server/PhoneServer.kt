package com.druk.lmplayground.server

import android.util.Log
import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.chat.ResponseProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The phone as a chat server, as in PrivateLM: the loaded model answers
 * OpenAI-style requests (`/v1/chat/completions`, streaming or not), so chat
 * apps and code editors on a computer can use it.
 *
 * - By default it listens on 127.0.0.1 only (apps on this phone). Letting
 *   other devices on the Wi-Fi in requires an API key, sent as
 *   `Authorization: Bearer <key>`.
 * - POSTs must be `application/json`, which makes browsers send a CORS
 *   preflight, so web pages can't fire simple cross-origin requests.
 * - One request at a time, and not while the app's own chat is generating
 *   ("Model is busy", 429). Each request gets its own short-lived session
 *   from the loaded model, so the open chat is left alone.
 * - Text only: images in a request are ignored. Tools aren't offered.
 * - The log never contains conversation text.
 */
object PhoneServer {

    /** What the server answers with; set by the chat screen while a model is loaded on the phone. */
    class Served(
        val name: String,
        /** A new session with the app's model settings and [systemPrompt] (null: the app's default). */
        val newSession: (systemPrompt: String?, temperature: Float?, topP: Float?, seed: Int?) -> GenerationSession?,
        val maxTokens: () -> Int,
        val isChatBusy: () -> Boolean,
    )

    @Volatile var served: Served? = null

    /** A server request is generating; the chat waits rather than running a second reply at once. */
    val isBusy: Boolean get() = busy.get()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private var server: ServerSocket? = null
    @Volatile private var apiKey: String? = null
    private val busy = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "PhoneServer-worker").apply { isDaemon = true }
    }

    private const val TAG = "PhoneServer"
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * Starts listening on [port]; [allowNetwork] needs a non-blank [key].
     * Blocking (binds a socket): call it off the main thread. Throws when the
     * port is taken.
     */
    @Synchronized
    fun start(port: Int, allowNetwork: Boolean, key: String?) {
        if (server != null) return
        require(!allowNetwork || !key.isNullOrBlank()) { "An API key is required for network access." }
        apiKey = key?.trim()?.takeIf { it.isNotEmpty() }
        val bind = if (allowNetwork) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(port, 16, bind)
        server = socket
        val host = if (allowNetwork) wifiAddress() ?: "0.0.0.0" else "127.0.0.1"
        _url.value = "http://$host:${socket.localPort}/v1"
        _running.value = true
        log("Listening on ${_url.value}")
        thread(name = "PhoneServer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                workers.execute { handle(client) }
            }
        }
    }

    /** Applies a new API key without a restart. */
    fun updateApiKey(key: String?) {
        apiKey = key?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        _running.value = false
        _url.value = null
        log("Stopped")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (_log.value + "$time  $line").takeLast(50)
    }

    /** This phone's Wi-Fi address (the first private IPv4), or null. */
    private fun wifiAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    // --- HTTP ----------------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 30_000
                val input = BufferedInputStream(c.getInputStream())
                val out = c.getOutputStream()
                val request = readRequest(input) ?: return
                route(request, out)
                out.flush()
            } catch (e: IOException) {
                Log.d(TAG, "Connection ended: ${e.message}")
            } catch (e: Exception) {
                log("Request failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun route(r: Request, out: OutputStream) {
        if (r.method == "OPTIONS") return out.write(response(204, ByteArray(0), "text/plain"))
        if (r.method == "GET" && r.path == "/health") return json(out, 200, JSONObject().put("status", "ok"))
        if (r.path.startsWith("/v1/") && !authorized(r)) {
            log("Rejected a request without the right API key")
            return error(out, 401, "Unauthorized")
        }
        if (r.method == "POST" && r.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
            return error(out, 415, "Content-Type must be application/json")
        }
        when {
            r.method == "GET" && r.path == "/v1/models" -> json(out, 200, models())
            r.method == "POST" && r.path == "/v1/chat/completions" -> chatCompletions(r, out)
            else -> error(out, 404, "Not found")
        }
    }

    private fun authorized(r: Request): Boolean {
        val key = apiKey ?: return true
        val header = r.headers["authorization"]?.trim().orEmpty()
        return MessageDigest.isEqual(header.toByteArray(), "Bearer $key".toByteArray())
    }

    private fun models() = JSONObject().put("object", "list").put("data", JSONArray().apply {
        served?.let {
            put(JSONObject().put("id", it.name).put("object", "model")
                .put("created", System.currentTimeMillis() / 1000).put("owned_by", "local"))
        }
    })

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw IOException("Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Body cut short")
            read += n
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16_384) throw IOException("Header line too long")
            sb.append(b.toChar())
        }
    }

    private fun response(code: Int, body: ByteArray, type: String) = buildString {
        append("HTTP/1.1 $code ${reason(code)}\r\n")
        append("Content-Type: $type\r\n")
        append("Content-Length: ${body.size}\r\n")
        corsHeaders()
        append("Connection: close\r\n\r\n")
    }.toByteArray() + body

    private fun StringBuilder.corsHeaders() {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 415 -> "Unsupported Media Type"; 429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    private fun json(out: OutputStream, code: Int, body: JSONObject) =
        out.write(response(code, body.toString().toByteArray(), "application/json"))

    private fun error(out: OutputStream, code: Int, message: String) =
        json(out, code, JSONObject().put("error", JSONObject().put("message", message)))

    // --- Chat completions ----------------------------------------------------

    /** A request's messages, split into what a session needs. */
    private class Chat(
        val system: String?,
        val users: List<String>,
        val assistants: List<String>,
        val prompt: String,
    )

    /** Text of an OpenAI message content: a string or an array of parts (text parts only). */
    private fun text(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { i ->
            content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
        }.joinToString("\n")
        else -> ""
    }

    private fun parseChat(body: JSONObject): Chat? {
        val messages = body.optJSONArray("messages") ?: return null
        val system = mutableListOf<String>()
        // (role, text) with consecutive same-role messages merged.
        val turns = mutableListOf<Pair<String, StringBuilder>>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = text(m.opt("content"))
            when (role) {
                "system", "developer" -> system += content
                "user", "assistant" -> {
                    val last = turns.lastOrNull()
                    if (last != null && last.first == role) last.second.append("\n\n").append(content)
                    else turns += role to StringBuilder(content)
                }
            }
        }
        if (turns.isEmpty() || turns.last().first != "user") return null
        val prompt = turns.removeAt(turns.size - 1).second.toString()
        // Earlier turns as (user, assistant) pairs; a leading assistant turn is dropped.
        while (turns.isNotEmpty() && turns.first().first != "user") turns.removeAt(0)
        val users = mutableListOf<String>()
        val assistants = mutableListOf<String>()
        var i = 0
        while (i < turns.size) {
            users += turns[i].second.toString()
            assistants += turns.getOrNull(i + 1)?.takeIf { it.first == "assistant" }?.second?.toString().orEmpty()
            i += 2
        }
        return Chat(system.joinToString("\n\n").takeIf { it.isNotBlank() }, users, assistants, prompt)
    }

    private fun stops(body: JSONObject): List<String> = when (val s = body.opt("stop")) {
        is String -> listOf(s)
        is JSONArray -> (0 until s.length()).mapNotNull { s.optString(it).takeIf { t -> t.isNotEmpty() } }.take(4)
        else -> emptyList()
    }.filter { it.isNotEmpty() }

    private fun chatCompletions(r: Request, out: OutputStream) {
        val model = served ?: return error(out, 400, "No model is loaded on the phone.")
        if (model.isChatBusy() || !busy.compareAndSet(false, true)) return error(out, 429, "Model is busy")
        try {
            val body = try { JSONObject(String(r.body, Charsets.UTF_8)) } catch (_: Exception) {
                return error(out, 400, "Invalid JSON")
            }
            val chat = parseChat(body) ?: return error(out, 400, "messages must end with a user message")
            val stream = body.optBoolean("stream", false)
            val maxTokens = body.optInt("max_tokens", body.optInt("max_completion_tokens", model.maxTokens()))
                .coerceIn(1, 32_768)
            val thinking = body.optJSONObject("chat_template_kwargs")?.optBoolean("enable_thinking", false) == true
            val requested = body.optString("model").trim()
            if (requested.isNotEmpty() && requested != model.name) log("Request for \"$requested\"; serving ${model.name}")
            log("Chat request: ${chat.users.size + 1} turns, ${if (stream) "streaming" else "whole reply"}")

            val session = model.newSession(
                chat.system,
                if (body.has("temperature")) body.optDouble("temperature").toFloat() else null,
                if (body.has("top_p")) body.optDouble("top_p").toFloat() else null,
                if (body.has("seed")) body.optInt("seed") else null,
            ) ?: return error(out, 500, "Couldn't start a session")
            try {
                if (chat.users.isNotEmpty()) session.replayHistory(chat.users.toTypedArray(), chat.assistants.toTypedArray())
                session.addMessage(chat.prompt, thinking)
                val id = "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                val created = System.currentTimeMillis() / 1000
                if (stream) {
                    out.write(buildString {
                        append("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n")
                        corsHeaders()
                        append("Connection: close\r\n\r\n")
                    }.toByteArray())
                    out.flush()
                }
                fun chunk(delta: JSONObject, finish: String?) {
                    val obj = JSONObject().put("id", id).put("object", "chat.completion.chunk")
                        .put("created", created).put("model", model.name)
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
                            .put("finish_reason", finish ?: JSONObject.NULL)))
                    out.write("data: $obj\n\n".toByteArray())
                    out.flush()
                }
                val result = generate(session, maxTokens, stops(body)) { reasoning, content ->
                    if (!stream) return@generate
                    if (reasoning.isNotEmpty()) chunk(JSONObject().put("reasoning_content", reasoning), null)
                    if (content.isNotEmpty()) chunk(JSONObject().put("content", content), null)
                }
                if (stream) {
                    chunk(JSONObject(), result.finishReason)
                    out.write("data: [DONE]\n\n".toByteArray())
                } else {
                    val message = JSONObject().put("role", "assistant").put("content", result.content)
                    if (result.reasoning.isNotEmpty()) message.put("reasoning_content", result.reasoning)
                    json(out, 200, JSONObject().put("id", id).put("object", "chat.completion")
                        .put("created", created).put("model", model.name)
                        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message)
                            .put("finish_reason", result.finishReason)))
                        .put("usage", JSONObject().put("completion_tokens", result.tokens)))
                }
                log("Reply sent: ${result.tokens} tokens (${result.finishReason})")
            } finally {
                try { session.destroy() } catch (_: Throwable) {}
            }
        } finally {
            busy.set(false)
        }
    }

    private class Result(val reasoning: String, val content: String, val tokens: Int, val finishReason: String)

    /**
     * Runs [session] to the end, a stop sequence or [maxTokens] answer
     * tokens, calling [onDelta] with the new reasoning and answer text. If
     * writing a delta fails (the client went away), generation is stopped.
     */
    private fun generate(
        session: GenerationSession,
        maxTokens: Int,
        stops: List<String>,
        onDelta: (reasoning: String, content: String) -> Unit,
    ): Result {
        val filter = StopSequenceFilter(stops)
        var reasoningSent = 0
        var tokens = 0
        var answerTokens = 0
        var finish = "stop"
        var aborted = false
        val reasoning = StringBuilder()
        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                if (aborted) return
                tokens++
                val text = ResponseProcessor.process(response)
                // The engine writes thinking as <think>…</think> before the answer.
                val thinkEnd = text.indexOf("</think>")
                val (thought, answer) = when {
                    text.startsWith("<think>") && thinkEnd < 0 -> text.removePrefix("<think>") to ""
                    text.startsWith("<think>") -> text.substring(7, thinkEnd) to text.substring(thinkEnd + 8).trimStart()
                    else -> "" to text
                }
                val newReasoning = if (thought.length > reasoningSent) thought.substring(reasoningSent) else ""
                reasoningSent = thought.length
                reasoning.append(newReasoning)
                if (answer.isNotEmpty() || thinkEnd >= 0) answerTokens++
                val newContent = filter.update(answer)
                try {
                    onDelta(newReasoning, newContent)
                } catch (_: IOException) {
                    aborted = true
                    session.requestAbort()
                    return
                }
                if (filter.stopped) {
                    aborted = true
                    session.requestAbort()
                } else if (answerTokens >= maxTokens) {
                    finish = "length"
                    aborted = true
                    session.requestAbort()
                }
            }
        }
        runBlocking { session.generateAll(callback) }
        val tail = filter.finish()
        if (tail.isNotEmpty()) try { onDelta("", tail) } catch (_: IOException) {}
        return Result(reasoning.toString(), filter.text, tokens, finish)
    }
}

/**
 * Cuts streamed text at the first stop sequence (OpenAI semantics: the stop
 * sequence itself is never sent). Text that could be the start of a stop
 * sequence is held back until it's clear it isn't. Fed the whole answer so
 * far each time; returns the part that's now safe to send.
 */
class StopSequenceFilter(private val stops: List<String>) {
    private val hold = (stops.maxOfOrNull { it.length } ?: 1) - 1
    /** Everything accepted so far, without any stop sequence. */
    var text = ""
        private set
    var stopped = false
        private set
    private var seen = ""

    fun update(full: String): String {
        if (stopped) return ""
        seen = full
        val cut = stops.mapNotNull { s -> full.indexOf(s).takeIf { it >= 0 } }.minOrNull()
        val safeEnd = if (cut != null) { stopped = true; cut } else (full.length - hold).coerceAtLeast(text.length)
        if (safeEnd <= text.length) return ""
        val delta = full.substring(text.length, safeEnd)
        text = full.substring(0, safeEnd)
        return delta
    }

    /** The held-back text once the reply has ended normally. */
    fun finish(): String {
        if (stopped || seen.length <= text.length) return ""
        val delta = seen.substring(text.length)
        text = seen
        return delta
    }
}

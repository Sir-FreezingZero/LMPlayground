package com.druk.lmplayground.remote

import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A computer running an OpenAI-style chat server (LM Studio, Ollama,
 * llama.cpp's llama-server). [url] has a scheme and no trailing slash.
 */
data class RemoteServer(val url: String, val apiKey: String? = null) {
    val label: String get() = url.substringAfter("://")
}

/** What a server says about one model; unknown capabilities default to on. */
data class RemoteModelCaps(
    val contextSize: Int = 8192,
    val tools: Boolean = true,
    val vision: Boolean = false,
    val thinking: Boolean = true,
)

/**
 * Saved servers, and the HTTP calls the app makes to them. A remote model
 * is an ordinary [ModelInfo] whose filename is `remote:<url>|<model id>`, so
 * chats, per-model settings and the picker treat it like a file on the phone.
 */
object RemoteServers {

    private const val PREFIX = "remote:"

    fun saved(prefs: StoragePreferences): List<RemoteServer> = try {
        val arr = JSONArray(prefs.remoteServersJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteServer(o.getString("url"), o.optString("key").ifBlank { null })
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(prefs: StoragePreferences, servers: List<RemoteServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("url", it.url).put("key", it.apiKey.orEmpty())) }
        prefs.remoteServersJson = arr.toString()
    }

    /** "192.168.1.5:1234/v1/" -> "http://192.168.1.5:1234". */
    fun normalize(input: String): String {
        var u = input.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if ("://" !in u) u = "http://$u"
        if (u.endsWith("/v1", ignoreCase = true)) u = u.dropLast(3)
        return u
    }

    fun isRemote(filename: String?) = filename?.startsWith(PREFIX) == true

    /** The server and model id a remote filename points at, or null. */
    fun parse(filename: String, prefs: StoragePreferences): Pair<RemoteServer, String>? {
        if (!isRemote(filename)) return null
        val url = filename.removePrefix(PREFIX).substringBefore('|')
        val id = filename.substringAfter('|', "").ifEmpty { return null }
        return (saved(prefs).firstOrNull { it.url == url } ?: RemoteServer(url)) to id
    }

    fun modelInfo(server: RemoteServer, id: String) = ModelInfo(
        name = id.substringAfterLast('/'),
        filename = "$PREFIX${server.url}|$id",
        description = server.label,
    )

    // One pool for every call; each use sets its own timeouts on top.
    private val http = OkHttpClient()
    private fun client(timeoutMs: Long) = http.newBuilder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    private val quick = client(3_000)
    private val scanner = client(600)

    /** Streaming chat calls have no read timeout: a big model can take minutes. */
    val chatClient: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun request(server: RemoteServer, path: String, json: JSONObject? = null) =
        Request.Builder().url(server.url + path).apply {
            server.apiKey?.let { header("Authorization", "Bearer $it") }
            if (json != null) post(json.toString().toRequestBody("application/json".toMediaType()))
        }.build()

    /** The body of a 2xx reply as JSON, else null. Never throws. */
    private fun get(client: OkHttpClient, server: RemoteServer, path: String, json: JSONObject? = null): JSONObject? =
        try {
            client.newCall(request(server, path, json)).execute().use {
                if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null
            }
        } catch (_: Exception) {
            null
        }

    /** The model ids [server] offers, or null when it doesn't answer. */
    suspend fun models(server: RemoteServer): List<String>? = withContext(Dispatchers.IO) {
        val data = get(quick, server, "/v1/models")?.optJSONArray("data") ?: return@withContext null
        (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.ifBlank { null } }
    }

    fun chatCall(server: RemoteServer, body: JSONObject): Call =
        chatClient.newCall(request(server, "/v1/chat/completions", body))

    /**
     * Capabilities from whichever native API the server has: Ollama's
     * /api/show, llama-server's /props, or LM Studio's /api/v0/models.
     */
    suspend fun caps(server: RemoteServer, id: String): RemoteModelCaps = withContext(Dispatchers.IO) {
        get(quick, server, "/api/show", JSONObject().put("model", id))?.let { o ->
            val list = o.optJSONArray("capabilities") ?: return@let null
            val c = (0 until list.length()).map { list.optString(it) }
            val info = o.optJSONObject("model_info")
            val ctx = info?.keys()?.asSequence()?.firstOrNull { it.endsWith(".context_length") }
                ?.let { info.optInt(it) } ?: 0
            RemoteModelCaps(ctx.takeIf { it > 0 } ?: 8192, "tools" in c, "vision" in c, "thinking" in c)
        } ?: get(quick, server, "/props?model=" + URLEncoder.encode(id, "UTF-8"))?.let { o ->
            val tmpl = o.optJSONObject("chat_template_caps") ?: return@let null
            RemoteModelCaps(
                contextSize = o.optJSONObject("default_generation_settings")?.optInt("n_ctx")
                    ?.takeIf { it > 0 } ?: 8192,
                tools = tmpl.optBoolean("supports_tool_calls", true),
                vision = o.optJSONObject("modalities")?.optBoolean("vision") == true,
            )
        } ?: get(quick, server, "/api/v0/models/" + URLEncoder.encode(id, "UTF-8"))?.let { o ->
            if (!o.has("type")) return@let null
            RemoteModelCaps(
                contextSize = o.optInt("loaded_context_length").takeIf { it > 0 }
                    ?: o.optInt("max_context_length").takeIf { it > 0 } ?: 8192,
                vision = o.optString("type") == "vlm",
            )
        } ?: RemoteModelCaps()
    }

    /**
     * Ask the server to take [id] out of its memory: LM Studio's unload,
     * llama-server's (router mode), else Ollama's keep_alive 0. Best effort.
     */
    fun freeMemory(server: RemoteServer, id: String) {
        fun ok(path: String, json: JSONObject) = try {
            quick.newCall(request(server, path, json)).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
        ok("/api/v1/models/unload", JSONObject().put("instance_id", id)) ||
            ok("/models/unload", JSONObject().put("model", id)) ||
            ok("/api/generate", JSONObject().put("model", id).put("keep_alive", 0))
    }

    /**
     * Servers on this phone's Wi-Fi: every address of its /24 network on the
     * usual ports (LM Studio 1234, Ollama 11434, llama-server 8080).
     */
    suspend fun scan(): List<RemoteServer> = withContext(Dispatchers.IO) {
        val base = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress?.substringBeforeLast('.')
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()
        val limit = Semaphore(64)
        coroutineScope {
            (1..254).flatMap { host ->
                listOf(1234, 11434, 8080).map { port ->
                    async {
                        limit.withPermit {
                            RemoteServer("http://$base.$host:$port")
                                .takeIf { get(scanner, it, "/v1/models")?.has("data") == true }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

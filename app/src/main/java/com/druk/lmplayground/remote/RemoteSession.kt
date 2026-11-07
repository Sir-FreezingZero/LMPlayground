package com.druk.lmplayground.remote

import android.util.Base64
import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.conversation.GenerationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A chat with a model on a [RemoteServer]. The server keeps no state between
 * requests, so the conversation lives here and every reply re-sends it.
 * Implements the same [GenerationSession] contract as the on-phone engine,
 * so the tool loop, Stop, edit and regenerate all work unchanged.
 */
class RemoteSession(
    private val server: RemoteServer,
    private val modelId: String,
    private val systemPrompt: String,
    private val params: GenerationParams,
    private val contextSize: Int,
) : GenerationSession {

    private val history = mutableListOf<JSONObject>()
    private var image: String? = null
    private var tools: JSONArray? = null
    private var thinking = true
    /** The last reply's unanswered calls, as `[{id, name, arguments}]`. */
    private var calls = JSONArray()
    private var contextUsed = 0

    private fun msg(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

    override fun addMessage(message: String, enableThinking: Boolean) {
        closeOpenTurn()
        thinking = enableThinking
        val img = image.also { image = null }
        history += msg(
            "user",
            if (img == null) message else JSONArray()
                .put(JSONObject().put("type", "text").put("text", message))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", img)))
        )
    }

    /**
     * Servers reject a history that ends on an unanswered tool call or has
     * two user turns in a row, which Stop or a failed request leaves behind.
     */
    private fun closeOpenTurn() {
        if (calls.length() > 0) {
            submitToolResults(
                JSONArray((0 until calls.length()).map {
                    JSONObject().put("id", calls.getJSONObject(it).getString("id"))
                        .put("content", "Error: Not run: the reply ended before this tool ran.")
                }).toString(),
                thinking,
            )
        }
        if (history.lastOrNull()?.optString("role") in listOf("user", "tool")) history += msg("assistant", "")
    }

    override fun setImageData(imageData: ByteArray) {
        image = "data:image/jpeg;base64," + Base64.encodeToString(imageData, Base64.NO_WRAP)
    }

    override fun setTools(toolsJson: String) {
        tools = JSONArray(toolsJson).takeIf { it.length() > 0 }
    }

    override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        userMessages.indices.forEach {
            history += msg("user", userMessages[it])
            history += msg("assistant", assistantMessages[it])
        }
    }

    override fun rewindToTurn(turnIndex: Int, expectedText: String): Int {
        val at = history.withIndex().filter { it.value.optString("role") == "user" }
            .getOrNull(turnIndex)?.index ?: return -1
        if (expectedText !in history[at].opt("content").toString()) return -1
        history.subList(at, history.size).clear()
        calls = JSONArray()
        return at
    }

    override fun getToolCallsJson(): String = calls.toString()

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean) {
        thinking = enableThinking
        val results = JSONArray(resultsJson)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            history += msg("tool", r.optString("content")).put("tool_call_id", r.optString("id"))
        }
        calls = JSONArray()
    }

    /** The "Context:" line the chat's context ring reads. */
    override fun getReport(): String = "Context: $contextUsed / $contextSize tokens"

    override fun requestAbort() {}

    override fun destroy() {}

    private fun body(withTools: Boolean) = JSONObject().apply {
        put("model", modelId)
        put("stream", true)
        put("stream_options", JSONObject().put("include_usage", true))
        put("temperature", params.temperature.toDouble())
        put("top_p", params.topP.toDouble())
        // Not in the OpenAI API, but LM Studio, Ollama and llama-server read them.
        put("top_k", params.topK)
        put("min_p", params.minP.toDouble())
        put("repeat_penalty", params.repetitionPenalty.toDouble())
        if (params.presencePenalty > 0f) put("presence_penalty", params.presencePenalty.toDouble())
        if (params.seed >= 0) put("seed", params.seed)
        if (!thinking) put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        if (withTools) tools?.let { put("tools", it) }
        put("messages", JSONArray().apply {
            if (systemPrompt.isNotBlank()) put(msg("system", systemPrompt))
            history.forEach { put(it) }
        })
    }

    /** A JSON string field, treating a missing key and JSON null alike. */
    private fun JSONObject?.text(key: String) = this?.opt(key) as? String ?: ""

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int = coroutineScope {
        val reasoning = StringBuilder()
        val content = StringBuilder()
        val callParts = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
        fun shown() = if (reasoning.isEmpty()) content.toString()
            else "<think>$reasoning" + (if (content.isEmpty()) "" else "</think>") + content
        var done = false
        try {
            withContext(Dispatchers.IO) {
                var withTools = tools != null
                while (true) {
                    val call = RemoteServers.chatCall(server, body(withTools))
                    // A blocking read can't see the coroutine being cancelled; cancel the call instead.
                    val watcher = this@coroutineScope.launch {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                val error = response.body?.string().orEmpty().take(500)
                                // Some models can't take tools (Ollama says so with a 400): try once without.
                                if (withTools && response.code == 400 && "tool" in error) {
                                    withTools = false
                                    return@use
                                }
                                throw java.io.IOException("HTTP ${response.code} $error".trim())
                            }
                            val source = response.body!!.source()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                val data = line.removePrefix("data:").trim()
                                if (!line.startsWith("data:") || data == "[DONE]") continue
                                val obj = try { JSONObject(data) } catch (_: Exception) { continue }
                                obj.optJSONObject("usage")?.let {
                                    contextUsed = it.optInt("prompt_tokens") + it.optInt("completion_tokens")
                                }
                                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                val r = delta.text("reasoning_content").ifEmpty { delta.text("reasoning") }
                                val c = delta.text("content")
                                if (r.isNotEmpty() || c.isNotEmpty()) {
                                    reasoning.append(r)
                                    content.append(c)
                                    callback.onFullResponse(shown())
                                }
                                // Tool calls arrive in pieces, keyed by index.
                                val parts = delta?.optJSONArray("tool_calls") ?: continue
                                if (callParts.isEmpty()) callback.onToolCallStarted()
                                for (j in 0 until parts.length()) {
                                    val p = parts.getJSONObject(j)
                                    val (id, name, args) = callParts.getOrPut(p.optInt("index", j)) {
                                        Triple(StringBuilder(), StringBuilder(), StringBuilder())
                                    }
                                    id.append(p.text("id"))
                                    val fn = p.optJSONObject("function")
                                    name.append(fn.text("name"))
                                    args.append(fn.text("arguments"))
                                }
                            }
                            done = true
                        }
                    } finally {
                        watcher.cancel()
                    }
                    if (done) break
                }
            }
        } catch (e: Exception) {
            // Stop cancels the call, which surfaces here as an IOException.
            ensureActive()
            callback.onFullResponse(shown() + (if (content.isEmpty() && reasoning.isEmpty()) "" else "\n\n") +
                "⚠️ " + (e.message ?: e.javaClass.simpleName))
            return@coroutineScope 1
        } finally {
            // Stopped or failed: keep what was written so the history stays a conversation.
            if (!done && content.isNotEmpty()) history += msg("assistant", content.toString())
        }

        if (callParts.isEmpty()) {
            history += msg("assistant", content.toString())
            return@coroutineScope 0
        }
        val sent = JSONArray()
        calls = JSONArray()
        callParts.forEach { (index, part) ->
            val id = part.first.ifEmpty { "call_$index" }.toString()
            val args = part.third.ifEmpty { "{}" }.toString()
            sent.put(JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", part.second.toString()).put("arguments", args)))
            calls.put(JSONObject().put("id", id).put("name", part.second.toString()).put("arguments", args))
        }
        history += msg("assistant", content.toString()).put("tool_calls", sent)
        2
    }
}

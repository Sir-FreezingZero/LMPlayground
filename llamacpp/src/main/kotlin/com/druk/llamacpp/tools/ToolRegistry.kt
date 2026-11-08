package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val enabledState = mutableMapOf<String, Boolean>()

    /**
     * Shared by web_search and its page reader for compact result references. Exposed so
     * the conversation layer can snapshot it into a session's metadata and
     * restore it when that conversation is reopened. See [WebLinkStore].
     */
    val webLinkStore = WebLinkStore()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * A tool's JSON result as plain text, which every model reads the same way:
     * an error as "Error: …", a lone result as itself, anything else as
     * "Key: value" lines (empty ones left out), with lists as blocks between "---".
     */
    fun asText(json: String): String {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return json
        o.optString("error").takeIf { it.isNotEmpty() }?.let { return "Error: $it" }
        if (o.length() == 1 && o.has("result")) return o.optString("result").ifEmpty { "(empty)" }
        return lines(o)
    }

    private fun lines(o: JSONObject): String = o.keys().asSequence().filter {
        o.opt(it).let { v -> v != null && v != JSONObject.NULL && v.toString().isNotBlank() }
    }.joinToString("\n") { key ->
        when (val v = o.get(key)) {
            is JSONArray -> (0 until v.length()).joinToString("\n\n---\n\n", postfix = "\n") { i ->
                v.optJSONObject(i)?.let { lines(it) } ?: v.optString(i)
            }
            is JSONObject -> lines(v)
            else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": " + v
        }
    }

    fun isToolEnabled(name: String): Boolean = enabledState[name] ?: false

    fun setToolEnabled(name: String, enabled: Boolean) {
        enabledState[name] = enabled
    }

    fun hasEnabledTools(): Boolean = tools.keys.any { isToolEnabled(it) }

    /** Abort in-flight work across all tools (e.g. on Stop during a tool call). */
    fun cancelInFlight() {
        for (tool in tools.values) {
            try { tool.cancelInFlight() } catch (_: Throwable) {}
        }
    }

    /** The enabled tools' functions in OpenAI format. */
    fun toOpenAIToolsJson(): String = JSONArray(
        tools.values.filter { isToolEnabled(it.name) }.flatMap { it.functions }.map { f ->
            JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", f.name).put("description", f.description).put("parameters", JSONObject(f.parametersSchema)))
        }
    ).toString()

    /**
     * Run the calls the model made and return `[{id,name,content}]`.
     *
     * With [enforceEnabled] a tool that is registered but switched off is
     * refused. The model is only shown the enabled tools, but nothing else
     * stops it naming one that was left out, e.g. a fetched page telling it
     * to call `location`.
     */
    fun executeToolCalls(toolCallsJson: String, enforceEnabled: Boolean = false): String {
        val calls = JSONArray(toolCallsJson)
        return JSONArray((0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            val tool = tools.values.firstOrNull { t -> t.functions.any { it.name == name } }
            val content = when {
                tool == null -> "Error: Unknown tool: $name"
                enforceEnabled && !isToolEnabled(tool.name) -> "Error: The $name tool is not enabled."
                else -> runCatching { asText(tool.execute(name, call.getString("arguments"))) }
                    .getOrElse { "Error: " + (it.message ?: "The $name tool failed.") }
            }
            JSONObject().put("id", call.getString("id")).put("name", name).put("content", content)
        }).toString()
    }

    /**
     * Exists so :app can hang [createDefault] off it as an extension: the
     * concrete tools need a Context / okhttp / the JS engine and stay there,
     * while the registry itself must be usable from a plain JVM.
     */
    companion object
}

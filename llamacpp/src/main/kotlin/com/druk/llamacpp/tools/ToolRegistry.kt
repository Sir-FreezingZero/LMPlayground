package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val enabledState = mutableMapOf<String, Boolean>()

    /**
     * Shared by web_search / web_fetch for compact result references. Exposed so
     * the conversation layer can snapshot it into a session's metadata and
     * restore it when that conversation is reopened. See [WebLinkStore].
     */
    val webLinkStore = WebLinkStore()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getTool(name: String): Tool? = tools[name]

    /** The tool that offers [function], the name the model called. */
    fun toolFor(function: String): Tool? = functionFor(function)?.first

    /**
     * The tool and function [called] means: an exact match, else the one
     * function whose name differs only in case or "-" / "_" (WEB_SEARCH, web-search).
     */
    private fun functionFor(called: String): Pair<Tool, ToolFunction>? {
        val all = tools.values.flatMap { t -> t.functions.map { t to it } }
        all.firstOrNull { it.second.name == called }?.let { return it }
        fun key(n: String) = n.lowercase().replace('-', '_')
        return all.filter { key(it.second.name) == key(called.trim()) }.singleOrNull()
    }

    /**
     * [raw] arguments as a JSON object the tool can read. A function with one
     * input also takes a bare value ("2+2") or a single input under another
     * name ({"q": "..."}), which small models often write.
     */
    private fun normalizedArguments(raw: String, function: ToolFunction): String {
        val props = runCatching { JSONObject(function.parametersSchema).optJSONObject("properties") }.getOrNull()
        val only = props?.takeIf { it.length() == 1 }?.keys()?.next()
        val parsed = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
        return when {
            parsed == null -> if (only != null) JSONObject().put(only, raw.trim().removeSurrounding("\"")).toString() else raw
            only != null && !parsed.has(only) && parsed.length() == 1 ->
                JSONObject().put(only, parsed.opt(parsed.keys().next())).toString()
            else -> parsed.toString()
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

    /**
     * Returns OpenAI-format JSON for enabled tools only.
     */
    fun toOpenAIToolsJson(): String {
        val arr = JSONArray()
        for (tool in tools.values) {
            if (!isToolEnabled(tool.name)) continue
            for (f in tool.functions) {
                val func = JSONObject()
                    .put("name", f.name)
                    .put("description", f.description)
                    .put("parameters", JSONObject(f.parametersSchema))
                arr.put(JSONObject().put("type", "function").put("function", func))
            }
        }
        return arr.toString()
    }

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
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            val arguments = call.getString("arguments")
            val id = call.getString("id")

            val match = functionFor(name)
            val content = when {
                match == null -> """{"error":"Unknown tool: $name"}"""
                enforceEnabled && !isToolEnabled(match.first.name) -> """{"error":"The $name tool is not enabled."}"""
                else -> match.first.execute(match.second.name, normalizedArguments(arguments, match.second))
            }

            val result = JSONObject()
            result.put("id", id)
            result.put("name", name)
            result.put("content", content)
            results.put(result)
        }
        return results.toString()
    }

    /**
     * Exists so :app can hang [createDefault] off it as an extension: the
     * concrete tools need a Context / okhttp / the JS engine and stay there,
     * while the registry itself must be usable from a plain JVM.
     */
    companion object
}

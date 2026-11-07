package com.druk.lmplayground.harness

import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.ToolRegistry

/**
 * The app's real [ToolRegistry], populated with deterministic stand-ins.
 *
 * name / description / parametersSchema are copied VERBATIM from the app's
 * WebSearchTool and WebFetchTool, so the chat template renders a preamble
 * byte-identical to production — a paraphrased schema would change the prompt
 * and could mask or invent a formatting bug. Only execute() differs: canned
 * results, so probes have no network dependency and reproduce exactly.
 *
 * JavaScriptTool has no stand-in: it needs androidx.javascriptengine, and a
 * calculator fake fills the same role (a question the model cannot answer
 * from training data).
 */
object HarnessTools {

    fun registry(): ToolRegistry = ToolRegistry().apply {
        register(FakeWebSearch)
        register(FakeCalculator)
        getAllTools().forEach { setToolEnabled(it.name, true) }
    }

    /** Adapts the registry to the loop's executor port. */
    fun executor(registry: ToolRegistry) = object : ToolCallLoop.ToolExecutor {
        override fun executeToolCalls(toolCallsJson: String) =
            registry.executeToolCalls(toolCallsJson)
        override fun cancelInFlight() = registry.cancelInFlight()
    }

    object FakeWebSearch : Tool {
        override val name = "web_search"
        override val description = "Search the web and fetch page content. Returns snippets for all results. Use the url parameter to fetch full page text from a specific URL."
        override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"The search query"},"url":{"type":"string","description":"A URL to fetch full page content from (instead of searching). Use this to read a page found in search results."}},"required":[]}"""
        override fun execute(arguments: String): String =
            if (org.json.JSONObject(arguments).has("url")) """{"title":"Weather in Kyiv","content":"Kyiv, Ukraine. Currently 12°C, partly cloudy, wind 8 km/h."}"""
            else """{"results":[{"title":"Weather in Kyiv","snippet":"Currently 12°C and partly cloudy in Kyiv, Ukraine.","url":"ddg:1"}]}"""
    }

    object FakeCalculator : Tool {
        override val name = "calculator"
        override val description = "Evaluate an arithmetic expression and return the numeric result."
        override val parametersSchema = """{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression, e.g. 17+25"}},"required":["expression"]}"""
        override fun execute(arguments: String): String = """{"result":42}"""
    }
}

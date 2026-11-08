package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class Search : Tool {
        var lastArgs: String? = null
        override val name = "web_search"
        override val description = "Searches the web."
        override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"What to search for"},""" +
            """"count":{"type":"integer"}},"required":["query"]}"""
        override fun execute(arguments: String): String {
            lastArgs = arguments
            return toolResult("hits for " + JSONObject(arguments).getString("query"))
        }
    }

    private class Clock : Tool {
        override val name = "datetime"
        override val functionName = "get_time"
        override val description = "Tells the time."
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String) = toolResult("12:00")
    }

    private val search = Search()
    private val registry = ToolRegistry().apply {
        register(search)
        register(Clock())
        setToolEnabled("web_search", true)
    }

    private fun run(name: String, args: String, enforce: Boolean = true): String =
        JSONArray(registry.executeToolCalls(JSONArray().put(JSONObject().put("id", "1").put("name", name).put("arguments", args)).toString(), enforce))
            .getJSONObject(0).getString("content")

    @Test
    fun runsAValidCall() {
        assertEquals("hits for cats", run("web_search", """{"query":"cats"}"""))
    }

    @Test
    fun unknownToolListsTheEnabledOnes() {
        val r = run("search_web", """{"query":"cats"}""")
        assertTrue(r, r.startsWith("Error: There is no tool called \"search_web\"."))
        assertTrue(r, r.endsWith("Available tools: web_search."))
    }

    @Test
    fun disabledToolIsRefusedWithWhatIsOn() {
        val r = run("get_time", "{}")
        assertTrue(r, r.startsWith("Error: The get_time tool is turned off"))
        assertTrue(r, "Available tools: web_search." in r)
    }

    @Test
    fun missingRequiredInputSaysWhatItTakesWithoutRunningTheTool() {
        val r = run("web_search", """{"count":3}""")
        assertTrue(r, r.startsWith("Error: web_search needs \"query\"."))
        assertTrue(r, "query (string, required): What to search for" in r)
        assertTrue(r, "count (integer, optional)" in r)
        assertEquals(null, search.lastArgs)
    }

    @Test
    fun blankRequiredInputCountsAsMissing() {
        assertTrue(run("web_search", """{"query":"  ","count":1}""").startsWith("Error: web_search needs \"query\"."))
    }

    @Test
    fun aliasesAndBareValuesStillWork() {
        assertEquals("hits for dogs", run("web_search", """{"q":"dogs"}"""))
        assertEquals("hits for owls", run("WEB-SEARCH", """{"query":"owls"}"""))
    }

    @Test
    fun argumentsThatAreNotAnObjectAreExplained() {
        // Two inputs, so a bare value can't be mapped onto one of them.
        val r = run("web_search", "cats and dogs")
        assertTrue(r, r.startsWith("Error: The arguments for web_search must be a JSON object."))
    }

    @Test
    fun toolWithoutRequiredInputsRunsWithBlankArguments() {
        registry.setToolEnabled("datetime", true)
        assertEquals("12:00", run("get_time", ""))
    }
}

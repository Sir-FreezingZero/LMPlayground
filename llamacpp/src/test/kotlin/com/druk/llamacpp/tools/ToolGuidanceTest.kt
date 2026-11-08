package com.druk.llamacpp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGuidanceTest {

    @Test
    fun noToolsNoGuidance() {
        assertEquals(null, ToolGuidance.forFunctions(emptyList()))
    }

    @Test
    fun saysWhenNotToUseATool() {
        val g = ToolGuidance.forFunctions(listOf("get_date_time"))!!
        assertTrue(g, g.startsWith("Most messages do not need a tool."))
        assertTrue(g, "greetings" in g && "just reply" in g)
        assertTrue(g, "call it right away instead of saying you will" in g)
    }

    @Test
    fun onlyEnabledToolsGetALine() {
        val g = ToolGuidance.forFunctions(listOf("calculate", "get_location"))!!
        assertTrue(g, "- calculate:" in g)
        assertTrue(g, "- get_location: only when the user asks where they are" in g)
        assertFalse(g, "web_search" in g)
        assertFalse(g, "set_alarm" in g)
    }

    @Test
    fun everyToolGetsGuidanceNotJustWebMathAndCode() {
        // Before, a chat with only the alarm or memory tool got no guidance at all.
        val g = ToolGuidance.forFunctions(listOf("set_alarm", "remember", "forget"))!!
        assertTrue(g, "- set_alarm:" in g && "- remember:" in g && "- forget:" in g)
    }

    @Test
    fun smallModelsGetTheShortWebTip() {
        val small = ToolGuidance.forFunctions(listOf("web_search"), compact = true)!!
        val big = ToolGuidance.forFunctions(listOf("web_search"), compact = false)!!
        assertTrue(small, small.endsWith("Do not repeat the same search."))
        assertTrue(big, "read its page" in big)
        assertTrue(small.length < big.length)
    }

    @Test
    fun unknownToolStillGetsTheGeneralRule() {
        val g = ToolGuidance.forFunctions(listOf("custom_tool"))!!
        assertTrue(g, "Use a tool only when the request needs what it gives." in g)
        assertFalse(g, "\n-" in g)
    }
}

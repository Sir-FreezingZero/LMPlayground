package com.druk.llamacpp.chat

import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallLoopTest {

    /** One scripted model round: the text it writes, and the calls it makes (none ends the reply). */
    private data class Round(val text: String, val calls: List<Pair<String, String>> = emptyList())

    private class FakeSession(rounds: List<Round>) : GenerationSession {
        private val script = ArrayDeque(rounds)
        private var current: Round? = null
        val submitted = mutableListOf<JSONArray>()
        val messages = mutableListOf<String>()

        override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
            val r = script.removeFirstOrNull() ?: Round("done")
            current = r
            callback.onFullResponse(r.text)
            return if (r.calls.isEmpty()) 0 else ToolCallLoop.RC_TOOL_CALLS
        }

        override fun getToolCallsJson(): String = JSONArray(current!!.calls.mapIndexed { i, (name, args) ->
            JSONObject().put("id", "call_${submitted.size}_$i").put("name", name).put("arguments", args)
        }).toString()

        override fun submitToolResults(resultsJson: String, enableThinking: Boolean) {
            submitted += JSONArray(resultsJson)
        }

        override fun addMessage(message: String, enableThinking: Boolean) { messages += message }
        override fun setImageData(imageData: ByteArray) {}
        override fun setTools(toolsJson: String) {}
        override fun rewindToTurn(turnIndex: Int, expectedText: String) = -1
        override fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {}
        override fun requestAbort() {}
        override fun destroy() {}
    }

    /** Answers each call with [answer]; counts how many calls actually ran. */
    private class FakeTools(val answer: (name: String, args: String, attempt: Int) -> String) : ToolCallLoop.ToolExecutor {
        val ran = mutableListOf<String>()
        override fun executeToolCalls(toolCallsJson: String): String {
            val calls = JSONArray(toolCallsJson)
            return JSONArray((0 until calls.length()).map { i ->
                val c = calls.getJSONObject(i)
                ran += c.getString("name")
                val attempt = ran.count { it == c.getString("name") }
                JSONObject().put("id", c.getString("id")).put("name", c.getString("name"))
                    .put("content", answer(c.getString("name"), c.getString("arguments"), attempt))
            }).toString()
        }
        override fun cancelInFlight() {}
    }

    private val callback = object : LlamaGenerationCallback { override fun onFullResponse(response: String) {} }

    private fun loop(session: FakeSession, tools: FakeTools, rounds: Int = 10) =
        ToolCallLoop(session, tools, maxToolRounds = rounds, toolNames = listOf("web_search"))

    private fun content(session: FakeSession, round: Int, call: Int = 0) =
        session.submitted[round].getJSONObject(call).getString("content")

    @Test
    fun repeatedCallGetsItsEarlierResultWithoutRunningAgain() = runBlocking {
        val q = """{"query":"cats"}"""
        val session = FakeSession(listOf(Round("", listOf("web_search" to q)), Round("", listOf("web_search" to q)), Round("Cats.")))
        val tools = FakeTools { _, _, _ -> "result A" }
        val r = loop(session, tools).run(callback, supportsThinking = false, enableThinking = false)
        assertEquals(0, r.rc)
        assertEquals(1, tools.ran.size)
        val second = content(session, 1)
        assertTrue(second, second.startsWith("result A\n\n[You already made this exact call"))
    }

    @Test
    fun sameCallTwiceInOneRoundRunsOnceAndBothGetTheResult() = runBlocking {
        val q = """{"query":"cats"}"""
        val q2 = """{ "query" : "cats" }"""
        val session = FakeSession(listOf(Round("", listOf("web_search" to q, "web_search" to q2)), Round("Cats.")))
        val tools = FakeTools { _, _, _ -> "result A" }
        loop(session, tools).run(callback, supportsThinking = false, enableThinking = false)
        assertEquals(1, tools.ran.size)
        assertEquals("result A", content(session, 0, 0))
        assertTrue(content(session, 0, 1).startsWith("result A\n\n"))
    }

    @Test
    fun failedCallCanBeRetried() = runBlocking {
        val q = """{"query":"cats"}"""
        val session = FakeSession(listOf(Round("", listOf("web_search" to q)), Round("", listOf("web_search" to q)), Round("Cats.")))
        val tools = FakeTools { _, _, attempt -> if (attempt == 1) "Error: timeout" else "result B" }
        loop(session, tools).run(callback, supportsThinking = false, enableThinking = false)
        assertEquals(2, tools.ran.size)
        assertTrue(content(session, 0).startsWith("Error: timeout\n\nThe call did not work."))
        assertEquals("result B", content(session, 1))
    }

    @Test
    fun roundLimitAsksForAnAnswerWithoutRunningMore() = runBlocking {
        val session = FakeSession(listOf(
            Round("", listOf("web_search" to """{"query":"a"}""")),
            Round("", listOf("web_search" to """{"query":"b"}""")),
            Round("Answer."),
        ))
        val tools = FakeTools { _, _, _ -> "ok" }
        val r = loop(session, tools, rounds = 1).run(callback, supportsThinking = false, enableThinking = false)
        assertTrue(r.hitRoundLimit)
        assertEquals(1, tools.ran.size)
        assertTrue(content(session, 1).startsWith("You have used all available tool calls."))
    }

    @Test
    fun emptyReplyWithoutThinkingIsNotBlamedOnThinking() = runBlocking {
        val session = FakeSession(listOf(Round(""), Round("Hello!")))
        loop(session, FakeTools { _, _, _ -> "" }).run(callback, supportsThinking = false, enableThinking = false)
        assertEquals(1, session.messages.size)
        assertTrue(session.messages[0].startsWith("Your last reply was empty."))
        assertFalse("thinking" in session.messages[0])
    }

    @Test
    fun emptyReplyAfterThinkingQuotesTheThinking() = runBlocking {
        val session = FakeSession(listOf(Round("<think>planning the poem"), Round("A poem.")))
        loop(session, FakeTools { _, _, _ -> "" }).run(callback, supportsThinking = true, enableThinking = true)
        assertTrue(session.messages[0].startsWith("You ran out of room while thinking"))
        assertTrue("planning the poem" in session.messages[0])
    }

    @Test
    fun announcedActionWithoutACallIsNudgedOnce() = runBlocking {
        val session = FakeSession(listOf(Round("Let me search for that."), Round("Let me search for that."), Round("Here it is.")))
        loop(session, FakeTools { _, _, _ -> "" }).run(callback, supportsThinking = false, enableThinking = false)
        // Never twice for the same words.
        assertEquals(1, session.messages.size)
        assertTrue("call web_search now" in session.messages[0])
    }
}

package com.druk.llamacpp.chat

import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One generation turn, including any tool rounds.
 *
 * Extracted from the app's GenerationCoordinator so the same loop runs in
 * production and in the test harness — the failure modes here (a round that
 * comes back empty, a model that never stops calling tools, thinking state
 * getting out of step across rounds) are exactly what a harness needs to
 * observe, and they can't be observed from a reimplementation.
 */
class ToolCallLoop(
    private val session: GenerationSession,
    private val tools: ToolExecutor,
    private val observer: Observer = Observer.NONE,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /**
     * The function names offered this reply. With any, a reply that only
     * announces an action ("Let me check…") is sent back once more with a
     * nudge to call a tool (see [repromptMessage]); empty turns that off.
     */
    private val toolNames: List<String> = emptyList(),
) {

    /** The tool side of the loop; [com.druk.llamacpp.tools.ToolRegistry] implements it. */
    interface ToolExecutor {
        fun executeToolCalls(toolCallsJson: String): String
        fun cancelInFlight()
    }

    interface Observer {
        /** A round's calls completed, paired with their results. */
        fun onToolCalls(calls: List<ToolCallRecord>) {}

        /**
         * A new round is starting. [responseThinking] is the thinking flag
         * handed to the model for it — the UI resets its per-round token
         * counters and thinking timer from this.
         */
        fun onRoundStarted(round: Int, responseThinking: Boolean) {}

        companion object { val NONE = object : Observer {} }
    }

    data class Result(
        /** Final return code from the last generateAll. */
        val rc: Int,
        val toolRounds: Int,
        /** True if the loop stopped because it hit [maxToolRounds]. */
        val hitRoundLimit: Boolean,
        val records: List<ToolCallRecord>,
    )

    /**
     * @param supportsThinking the model's own capability, not the user toggle.
     * @param enableThinking the user toggle for this turn.
     * @param isActive lets the caller abandon the loop between rounds
     *        (the app passes its coroutine's isActive).
     * @param forceThinkingAfterTools turn thinking on after tool results even
     *        when [enableThinking] is off, for models that need it (below).
     */
    suspend fun run(
        callback: LlamaGenerationCallback,
        supportsThinking: Boolean,
        enableThinking: Boolean,
        isActive: () -> Boolean = { true },
        forceThinkingAfterTools: Boolean = true,
    ): Result {
        var toolRounds = 0
        val allRecords = mutableListOf<ToolCallRecord>()
        var rc: Int
        var hitLimit = false
        var reprompts = 0
        var lastReprompted = ""
        // Each call already run this reply, as name + arguments, so a repeat isn't run again.
        val made = mutableSetOf<String>()

        // See the forced-thinking note further down.
        val responseThinking = enableThinking || (supportsThinking && forceThinkingAfterTools)

        var roundText = ""
        val tracking = object : LlamaGenerationCallback by callback {
            override fun onFullResponse(response: String) {
                roundText = response
                callback.onFullResponse(response)
            }
        }

        while (true) {
            roundText = ""
            rc = session.generateAll(tracking)
            if (rc == 0 && isActive() && toolNames.isNotEmpty()) {
                // The model said what it would do but didn't call a tool: ask it
                // to make the call or answer. Up to 3 times before any tool has
                // run, once after; never twice for the same words.
                val said = answerText(roundText)
                val cap = if (allRecords.isEmpty()) MAX_REPROMPTS else 1
                if (reprompts < cap && said != lastReprompted && isIntentWithoutAction(said)) {
                    reprompts++
                    lastReprompted = said
                    session.addMessage(repromptMessage(toolNames), if (allRecords.isEmpty()) enableThinking else responseThinking)
                    observer.onRoundStarted(toolRounds, if (allRecords.isEmpty()) enableThinking else responseThinking)
                    continue
                }
            }
            if (rc != RC_TOOL_CALLS || !isActive()) break
            if (toolRounds >= maxToolRounds) {
                // Asked for tools again after being told to stop: end here.
                // The engine closes the unanswered call before the next message.
                if (hitLimit) break
                // Out of rounds: don't run these calls. Tell the model so and
                // give it one more pass to answer with what it already has,
                // rather than ending the reply on an unanswered tool call.
                hitLimit = true
                session.submitToolResults(limitReachedResults(session.getToolCallsJson()), responseThinking)
                observer.onRoundStarted(toolRounds, responseThinking)
                continue
            }
            toolRounds++

            val toolCallsJson = session.getToolCallsJson()
            val started = System.currentTimeMillis()
            // Tool execution blocks (network); run it off the calling
            // dispatcher and await it so cancellation can interrupt — on
            // cancel we abort in-flight requests, which unblocks promptly.
            // Calls repeated exactly from earlier in this reply aren't run again.
            val calls = JSONArray(toolCallsJson)
            val fresh = JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                if (made.add(callKey(call))) fresh.put(call)
            }
            val ran = if (fresh.length() == 0) JSONArray() else withContext(Dispatchers.IO) {
                val exec = async { tools.executeToolCalls(fresh.toString()) }
                try {
                    JSONArray(exec.await())
                } catch (e: CancellationException) {
                    tools.cancelInFlight()
                    throw e
                }
            }
            val toolResults = withErrorHints(calls, ran).toString()
            val durationMs = System.currentTimeMillis() - started

            val records = ToolCallPairing.pair(toolCallsJson, toolResults, durationMs)
            allRecords += records
            observer.onToolCalls(records)

            // When asked, force thinking on for the response phase if the model
            // has a thinking mode, regardless of the user toggle: Gemma 4 and
            // harmony-style models emit an empty content channel after tool
            // calls when thinking is off, which would show a blank assistant
            // bubble after every tool call. Reasoning still routes to the
            // collapsed thinking section via the always-on DEEPSEEK
            // extraction in the parser. A no-op for models without a
            // thinking mode (the flag is ignored).
            // Otherwise thinking follows the user toggle, as in round one.
            session.submitToolResults(toolResults, responseThinking)
            observer.onRoundStarted(toolRounds, responseThinking)
        }

        return Result(rc = rc, toolRounds = toolRounds, hitRoundLimit = hitLimit, records = allRecords)
    }

    /** A result for each of [toolCallsJson]'s calls saying the round limit stopped it. */
    private fun limitReachedResults(toolCallsJson: String): String {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            results.put(
                JSONObject()
                    .put("id", call.getString("id"))
                    .put("name", call.getString("name"))
                    .put("content", JSONObject().put("error", BUDGET_EXHAUSTED).toString())
            )
        }
        return results.toString()
    }

    /**
     * A result for every call, in order: what [ran] returned, a note for a
     * repeated call, and a hint to try something else after an error.
     */
    private fun withErrorHints(calls: JSONArray, ran: JSONArray): JSONArray {
        val byId = (0 until ran.length()).associate { ran.getJSONObject(it).let { r -> r.getString("id") to r } }
        val out = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            val result = byId[id] ?: JSONObject().put("id", id).put("name", call.getString("name"))
                .put("content", JSONObject().put("error", DUPLICATE_CALL).toString())
            val content = result.optString("content")
            val error = runCatching { JSONObject(content).optString("error") }.getOrDefault("")
            if (error.isNotEmpty() && error != DUPLICATE_CALL) {
                result.put("content", JSONObject(content).put("error", error + TOOL_ERROR_HINT).toString())
            }
            out.put(result)
        }
        return out
    }

    companion object {
        // The wording below follows Unsloth Studio's tool loop, which works well across models.
        private const val BUDGET_EXHAUSTED = "You have used all available tool calls. Based on everything you " +
            "have found so far, provide your final answer now. Do not call any more tools."
        private const val DUPLICATE_CALL = "You already made this exact call. Do not repeat the same tool call. " +
            "Try a different approach, or provide your final answer now."
        private const val TOOL_ERROR_HINT = " The tool call encountered an issue. Please try a different approach " +
            "or rephrase your request."
        private const val MAX_REPROMPTS = 3
        private const val REPROMPT_MAX_CHARS = 2000

        fun repromptMessage(toolNames: List<String>) =
            "You have access to enabled tools. If a tool is needed to satisfy the user's request or complete " +
                "the action you described, call ${toolNames.joinToString(" or ")} now. If no tool is needed, " +
                "provide the final answer and follow the user's requested format."

        // Forward-looking intent ("I'll…", "Let me…", "First, …", "Step 1: search…"), but not
        // offers to help ("I'll be happy to help", "let me know") or refusals ("I will not").
        private const val ACTION = "(?:search|check|look|find|fetch|get|call|use|run|query|calculate|compute|" +
            "convert|set|read|retrieve|verify|confirm|determine|identify)"
        private val INTENT = Regex(
            "(?im)\\b(?:i['\u2019](?:ll|m going to|m gonna)|i am (?:going to|gonna)|i will|i shall)\\b" +
                "(?!\\s+(?:not|never)\\b)(?!\\s+(?:do(?:ing)? my best|try my best|be (?:able|happy|glad) to|assist|help you\\b(?!\\s+$ACTION)))" +
                "|\\b(?:let me|allow me)\\b(?!\\s+(?:not|never|know)\\b)" +
                "|(?:^|[.!?]\\s+)(?:the\\s+)?first(?:\\s+step)?\\s*[,:]?\\s+(?:i|we|let['\u2019]?s|$ACTION)\\b" +
                "|\\bstep \\d+\\s*[:.)-]\\s*$ACTION\\b" +
                "|\\bhere['\u2019]?s (?:my |the |a )?(?:plan|approach)" +
                "|\\b(?:now i|next i)\\b"
        )

        /** A short reply that announces an action without taking it. */
        fun isIntentWithoutAction(text: String) = text.length in 1 until REPROMPT_MAX_CHARS && INTENT.containsMatchIn(text)

        /** The reply without its thinking. */
        private fun answerText(response: String): String =
            response.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)<think>.*"), "").trim()

        /** Name plus arguments with keys sorted, so the same call matches however it's written. */
        private fun callKey(call: JSONObject): String {
            val args = runCatching { JSONObject(call.optString("arguments")) }.getOrNull()
            val sorted = args?.keys()?.asSequence()?.sorted()?.joinToString(",") { "$it=${args.opt(it)}" }
                ?: call.optString("arguments").trim()
            return call.optString("name").lowercase() + "(" + sorted + ")"
        }
        const val RC_TOOL_CALLS = 2
        const val DEFAULT_MAX_TOOL_ROUNDS = 10
    }
}

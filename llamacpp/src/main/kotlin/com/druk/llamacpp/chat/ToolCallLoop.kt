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
        var emptyRetries = 0
        // Each tool's last result and how many times in a row it came back the same.
        val sameResult = mutableMapOf<String, Pair<String, Int>>()
        // Each call that worked this reply, as name + arguments, with its result,
        // so a repeat gets that result back instead of running again. Failed
        // calls aren't kept: a retry after a network blip should run.
        val made = mutableMapOf<String, String>()

        // Thinking for replies after tool results. Forced on (when asked and the
        // model can think) because Gemma 4 and harmony-style models write an
        // empty answer after tool calls with thinking off; the thinking still
        // goes to the collapsed section. Otherwise it follows the user toggle.
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
            // Nothing to show (the thinking used up the room, or an empty reply):
            // ask for an answer with thinking off, up to twice.
            if (rc == 0 && isActive() && answerText(roundText).isEmpty() && emptyRetries < MAX_EMPTY_RETRIES) {
                emptyRetries++
                session.addMessage(nothingProducedMessage(thinkingText(roundText)), false)
                observer.onRoundStarted(toolRounds, false)
                continue
            }
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
                val unanswered = JSONArray(session.getToolCallsJson())
                session.submitToolResults(JSONArray((0 until unanswered.length()).map {
                    resultFor(unanswered.getJSONObject(it), BUDGET_EXHAUSTED)
                }).toString(), responseThinking)
                observer.onRoundStarted(toolRounds, responseThinking)
                continue
            }
            toolRounds++

            val toolCallsJson = session.getToolCallsJson()
            val started = System.currentTimeMillis()
            // Calls repeated exactly from earlier in this reply (or this round) aren't run again.
            val calls = JSONArray(toolCallsJson)
            val seen = made.keys.toMutableSet()
            val fresh = JSONArray((0 until calls.length()).map { calls.getJSONObject(it) }.filter { seen.add(callKey(it)) })
            // Tools block (network), so they run off this dispatcher; on cancel the
            // in-flight requests are aborted, which unblocks them promptly.
            val ran = if (fresh.length() == 0) JSONArray() else withContext(Dispatchers.IO) {
                val exec = async { tools.executeToolCalls(fresh.toString()) }
                try {
                    JSONArray(exec.await())
                } catch (e: CancellationException) {
                    tools.cancelInFlight()
                    throw e
                }
            }
            val toolResults = finishedResults(calls, ran, sameResult, made).toString()
            val durationMs = System.currentTimeMillis() - started

            val records = ToolCallPairing.pair(toolCallsJson, toolResults, durationMs)
            allRecords += records
            observer.onToolCalls(records)
            session.submitToolResults(toolResults, responseThinking)
            observer.onRoundStarted(toolRounds, responseThinking)
        }

        return Result(rc = rc, toolRounds = toolRounds, hitRoundLimit = hitLimit, records = allRecords)
    }

    private fun resultFor(call: JSONObject, content: String) =
        JSONObject().put("id", call.getString("id")).put("name", call.getString("name")).put("content", content)

    /**
     * A result for every call, in order: what [ran] returned (a repeated call
     * gets a note instead), with a hint after an error and a note when a tool
     * keeps returning the same thing.
     */
    private fun finishedResults(
        calls: JSONArray,
        ran: JSONArray,
        sameResult: MutableMap<String, Pair<String, Int>>,
        made: MutableMap<String, String>,
    ): JSONArray {
        val byId = (0 until ran.length()).associate { ran.getJSONObject(it).let { r -> r.getString("id") to r } }
        // This round's fresh results first, so a duplicate within the round finds its twin.
        val fresh = (0 until calls.length()).map { calls.getJSONObject(it) }.mapNotNull { call ->
            val content = byId[call.getString("id")]?.optString("content") ?: return@mapNotNull null
            if (!content.startsWith("Error:")) made[callKey(call)] = content
            call.getString("id") to content
        }.toMap()
        return JSONArray((0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            fresh[call.getString("id")]?.let { content ->
                val times = sameResult[name]?.takeIf { it.first == content }?.second?.plus(1) ?: 1
                sameResult[name] = content to times
                return@map resultFor(call, content +
                    (if (content.startsWith("Error:")) TOOL_ERROR_HINT else "") +
                    (if (times >= SAME_RESULT_LIMIT) "\n\n[$name returned exactly this " +
                        "$times times; it will not change. Continue from what you have and finish the task.]" else ""))
            }
            // A repeat: hand back what the same call returned before, so the
            // model has something to answer from, and say not to ask again.
            val earlier = made[callKey(call)]
            resultFor(call, if (earlier == null) DUPLICATE_CALL else "$earlier\n\n$REPEATED_CALL")
        })
    }

    companion object {
        // Kept short and plain so small models follow it.
        private const val BUDGET_EXHAUSTED = "You have used all available tool calls. Based on everything you " +
            "have found so far, provide your final answer now. Do not call any more tools."
        private const val DUPLICATE_CALL = "You already made this exact call. Do not repeat the same tool call. " +
            "Try a different approach, or provide your final answer now."
        private const val REPEATED_CALL = "[You already made this exact call; that is its result again. Do not " +
            "repeat it. Use it, try something different, or give your final answer.]"
        private const val TOOL_ERROR_HINT = "\n\nThe call did not work. Fix the arguments and call again, try " +
            "another tool, or answer with what you know and tell the user the tool failed."
        private const val MAX_REPROMPTS = 3
        private const val MAX_EMPTY_RETRIES = 2
        private const val SAME_RESULT_LIMIT = 3

        /** Sent when a reply produced nothing to show, with the end of its thinking if any. */
        fun nothingProducedMessage(thinking: String) = if (thinking.isBlank()) {
            "Your last reply was empty. Reply to the user now: call a tool if one is needed, or give your answer."
        } else buildString {
            append("You ran out of room while thinking, so nothing was produced. Stop thinking and act: ")
            append("call a tool or answer now. If it will not all fit, deliver the first part and continue after.")
            append("\n\nWhere you had got to:\n").append(thinking.takeLast(600).trim())
        }

        /**
         * Sent when a reply only announced an action. It is a hidden turn, but
         * whatever the model writes next replaces the announcement on screen,
         * so it asks for the call or a complete answer, not a reply to itself.
         */
        fun repromptMessage(toolNames: List<String>) =
            "(Note from the app, not the user: you said you would do something but did not call a tool. " +
                "If a tool is needed, call it now; the tools are ${toolNames.joinToString(", ")}. If none is " +
                "needed, write your complete answer to the user instead. Do not mention this note.)"

        /** Longer than this, a reply is an answer that happens to say "let me…", not an announcement. */
        private const val REPROMPT_MAX_CHARS = 600

        // A verb for something a tool does, as a stem: "search", "searching", "looks up".
        private const val ACTION = "(?:search|check|look(?:ing|s)?\\s+(?:\\w+\\s+)?(?:up|into|for)|find|fetch|pull(?:ing)?\\s+up|" +
            "call|run|query|calculat|comput|work(?:ing)?\\s+(?:it\\s+)?out|convert|set|read|retriev|verif|confirm|" +
            "browse|open|visit|sav|remember|locat|use\\s+(?:the\\s+)?\\w+\\s+tool)\\w*"

        // "I'll", "let me", "I'm going to", "now I'll"… then optional fillers, then the action.
        private val ANNOUNCE = Regex(
            "(?i)\\b(?:i['\u2019](?:ll|m going to|m gonna|m)|i (?:will|shall|am going to|am|need to)|" +
                "let me|allow me|let['\u2019]?s)\\s+" +
                "(?:(?:quickly|now|first|just|also|go ahead and|try to|try and|then|do a|make a|about to)\\s+)*$ACTION"
        )
        private val SENTENCE_END = Regex("(?<=[.!?:\u2026])\\s+|\\n+")

        /**
         * A reply that only announces an action ("Sure! Let me look that up.")
         * without making the call. Only its last two sentences count, so a poem
         * or an answer that says "I'll…" along the way is never sent back, and a
         * reply ending in a question is the model asking the user something.
         */
        fun isIntentWithoutAction(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty() || t.length > REPROMPT_MAX_CHARS || t.endsWith("?")) return false
            val tail = t.split(SENTENCE_END).filter { it.isNotBlank() }.takeLast(2).joinToString(" ")
            return ANNOUNCE.containsMatchIn(tail)
        }

        private val THINKING = Regex("(?s)<think>(.*?)(?:</think>|$)")

        /** The reply's thinking, closed or not. */
        private fun thinkingText(response: String): String =
            THINKING.findAll(response).joinToString("\n") { it.groupValues[1] }

        /** The reply without its thinking. */
        private fun answerText(response: String): String = response.replace(THINKING, "").trim()

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

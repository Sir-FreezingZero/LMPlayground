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

        // Thinking for replies after tool results. Forced on (when asked and the
        // model can think) because Gemma 4 and harmony-style models write an
        // empty answer after tool calls with thinking off; the thinking still
        // goes to the collapsed section. Otherwise it follows the user toggle.
        val responseThinking = enableThinking || (supportsThinking && forceThinkingAfterTools)

        while (true) {
            rc = session.generateAll(callback)
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
                    val call = unanswered.getJSONObject(it)
                    JSONObject().put("id", call.getString("id")).put("name", call.getString("name"))
                        .put("content", LIMIT_MESSAGE)
                }).toString(), responseThinking)
                observer.onRoundStarted(toolRounds, responseThinking)
                continue
            }
            toolRounds++

            val toolCallsJson = session.getToolCallsJson()
            val started = System.currentTimeMillis()
            // Tools block (network), so they run off this dispatcher; on cancel the
            // in-flight requests are aborted, which unblocks them promptly.
            val toolResults = withContext(Dispatchers.IO) {
                val exec = async { tools.executeToolCalls(toolCallsJson) }
                try {
                    exec.await()
                } catch (e: CancellationException) {
                    tools.cancelInFlight()
                    throw e
                }
            }
            val durationMs = System.currentTimeMillis() - started

            val records = ToolCallPairing.pair(toolCallsJson, toolResults, durationMs)
            allRecords += records
            observer.onToolCalls(records)
            session.submitToolResults(toolResults, responseThinking)
            observer.onRoundStarted(toolRounds, responseThinking)
        }

        return Result(rc = rc, toolRounds = toolRounds, hitRoundLimit = hitLimit, records = allRecords)
    }

    companion object {
        private const val LIMIT_MESSAGE = "Error: Not run, the tool limit for this reply was reached. " +
            "Answer now with what you already have."
        const val RC_TOOL_CALLS = 2
        const val DEFAULT_MAX_TOOL_ROUNDS = 10
    }
}

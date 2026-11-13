package com.druk.agent.llm

/**
 * What the agent needs from a language model: one stateless completion.
 *
 * Deliberately stateless — each call carries its full (small) prompt, so the
 * model can be unloaded between steps, swapped, or lost to a crash without
 * the agent losing anything. The app implements this over the llama.cpp
 * engine; tests use scripted fakes.
 */
interface LlmBackend {
    suspend fun complete(request: LlmRequest): String

    /** Release the model (idle, low memory, worker stopping). Must be idempotent. */
    fun release() {}
}

data class LlmRequest(
    val system: String,
    val prompt: String,
    val maxTokens: Int = 512,
    /** Purpose tag for logging/metrics: "act", "plan", "reflect", "summarize". */
    val purpose: String = "act",
    val temperature: Float = 0.4f,
    /** Model file the project is configured to use ("" = backend default). */
    val model: String = "",
)

/** The model can't run right now (not downloaded, unloaded, OOM, engine crashed). Transient. */
class LlmUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

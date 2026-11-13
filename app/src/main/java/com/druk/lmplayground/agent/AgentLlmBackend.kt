package com.druk.lmplayground.agent

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.druk.agent.llm.LlmBackend
import com.druk.agent.llm.LlmRequest
import com.druk.agent.llm.LlmUnavailableException
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.models.ChatTemplateOverrides
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [LlmBackend] over the app's llama.cpp engine (the `:llama` AIDL service).
 *
 * - One model at a time, loaded lazily on the first request and kept warm
 *   across steps; a request for a different model swaps it.
 * - Every request runs in a **fresh session** (system prompt + one user
 *   turn) that is destroyed afterwards: the agent's memory lives in its
 *   project store, never in a KV cache, so nothing is lost when the model
 *   is unloaded or the engine process dies.
 * - Before loading, free RAM is checked so the agent backs off instead of
 *   getting the `:llama` process OOM-killed next to a chat model.
 * - Output length is capped by aborting generation once the reply exceeds
 *   the request's token budget (approximated in characters).
 */
class AgentLlmBackend(
    private val context: Context,
    private val client: InferenceClient,
    private val llamaCpp: LlamaCpp,
    private val storage: StorageRepository,
    /** Model used when a project has none configured. */
    private val defaultModel: () -> String?,
    private val contextSize: Int = 4096,
) : LlmBackend {

    private val mutex = Mutex()
    private var model: LlamaModel? = null
    private var modelFile: String? = null
    private var handle: StorageRepository.ModelFileHandle? = null

    @Volatile var loadedModelName: String? = null
        private set

    override suspend fun complete(request: LlmRequest): String = mutex.withLock {
        val wanted = request.model.ifBlank { defaultModel().orEmpty() }
        if (wanted.isBlank()) throw LlmUnavailableException("No model selected. Download a model in Models and pick it for this project.")
        ensureEngine()
        val m = ensureModel(wanted)
        val session = withContext(Dispatchers.IO) {
            try {
                m.createSession(
                    contextSize, request.temperature, 0.9f, 1.05f, 40, 0.05f, -1,
                    /* thinkingBudget = */ 256, request.system,
                )
            } catch (e: Exception) {
                dropModelHandles()
                throw LlmUnavailableException("Could not create a session: ${e.message}", e)
            }
        } ?: throw LlmUnavailableException("Engine refused to create a session (context $contextSize)")
        try {
            generate(session, request)
        } finally {
            withContext(Dispatchers.IO) { runCatching { session.destroy() } }
        }
    }

    private suspend fun generate(session: LlamaGenerationSession, request: LlmRequest): String {
        val maxChars = request.maxTokens * 4
        var last = ""
        var aborted = false
        val rc = try {
            withContext(Dispatchers.IO) {
                session.setTools("[]")
                session.addMessage(request.prompt, /* enableThinking = */ false)
            }
            session.generateAll(object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) {
                    last = response
                    if (!aborted && response.length > maxChars) {
                        aborted = true
                        runCatching { session.requestAbort() }
                    }
                }
            })
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { session.requestAbort() }
            throw e
        } catch (e: Exception) {
            dropModelHandles()
            throw LlmUnavailableException("Generation failed: ${e.message}", e)
        }
        if (rc != 0 && !aborted && last.isBlank()) throw LlmUnavailableException("Generation ended with code $rc and no output")
        return last
    }

    private fun ensureEngine() {
        when (val s = client.state.value) {
            is InferenceState.Crashed -> {
                // The :llama process died (possibly under us). Our handles are
                // gone with it; take the re-bound service and retry next step.
                Log.w(TAG, "Inference engine crashed ($s); acknowledging and backing off")
                dropModelHandles()
                client.acknowledgeCrash()
                throw LlmUnavailableException("Inference engine restarted; will retry")
            }
            else -> Unit
        }
    }

    private suspend fun ensureModel(file: String): LlamaModel = withContext(Dispatchers.IO) {
        model?.let { if (modelFile == file) return@withContext it }
        unloadLocked()
        val files = storage.getModelFiles()
        val entry = files.firstOrNull { it.name == file }
            ?: throw LlmUnavailableException("Model file '$file' is not downloaded")
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val needed = entry.sizeBytes + 350L * 1024 * 1024
        if (mem.availMem - mem.threshold < needed) {
            throw LlmUnavailableException(
                "Not enough free memory for ${entry.displayName} (need ~${needed / 1_048_576} MB, " +
                    "available ${(mem.availMem - mem.threshold) / 1_048_576} MB). Close the chat model or pick a smaller model."
            )
        }
        val h = storage.openModelFile(file) ?: throw LlmUnavailableException("Cannot open model file '$file'")
        try {
            val loaded = llamaCpp.loadModel(
                h.pfd,
                object : LlamaProgressCallback { override fun onProgress(progress: Float) {} },
                chatTemplateOverride = ChatTemplateOverrides.forModel(context, file),
            )
            model = loaded; modelFile = file; handle = h; loadedModelName = entry.displayName
            loaded
        } catch (e: Exception) {
            h.close()
            throw LlmUnavailableException("Failed to load '$file': ${e.message}", e)
        }
    }

    override fun release() {
        // Non-suspending: called from worker teardown. Take the lock if free;
        // otherwise a generation is in flight and its caller will finish first.
        if (mutex.tryLock()) {
            try { unloadLocked() } finally { mutex.unlock() }
        }
    }

    private fun unloadLocked() {
        val m = model
        dropModelHandles()
        if (m != null) runCatching { m.unloadModel() }
    }

    private fun dropModelHandles() {
        model = null
        modelFile = null
        loadedModelName = null
        handle?.close()
        handle = null
    }

    companion object { private const val TAG = "AgentLlmBackend" }
}

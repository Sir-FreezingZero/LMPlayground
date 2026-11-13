package com.druk.agent

import com.druk.agent.llm.LlmBackend
import com.druk.agent.llm.LlmRequest
import com.druk.agent.llm.LlmUnavailableException
import com.druk.agent.store.ProjectStore
import com.druk.agent.tools.*
import java.io.File
import java.nio.file.Files

fun tempDir(prefix: String = "agent"): File = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

class Clock(var now: Long = 1_700_000_000_000) {
    val fn: () -> Long = { now }
    fun advance(ms: Long) { now += ms }
}

/** Model double: a function of the request. Records every request. */
class ScriptedLlm(private val fn: (LlmRequest, Int) -> String) : LlmBackend {
    val requests = mutableListOf<LlmRequest>()
    var unavailable = false
    var released = 0
    override suspend fun complete(request: LlmRequest): String {
        if (unavailable) throw LlmUnavailableException("model unloaded")
        requests += request
        return fn(request, requests.size)
    }
    override fun release() { released++ }

    companion object {
        /** Replies from a queue; plan requests get [plan] when the queue is empty. */
        fun queue(vararg replies: String, plan: String = "STRATEGY: s\nTASK: do it | x | 3 | none"): ScriptedLlm {
            val q = ArrayDeque(replies.toList())
            return ScriptedLlm { r, _ -> if (q.isNotEmpty()) q.removeFirst() else if (r.purpose == "plan") plan else "THOUGHT: idle\nACTION: list_files" }
        }
    }
}

/**
 * Deterministic in-process "code runner". Understands a tiny language so
 * tests don't spawn processes thousands of times:
 *   print <text>            → stdout line
 *   metric <name> <value>   → "METRIC name=value"
 *   fail                    → non-zero exit
 *   hang                    → times out
 *   write <path> <text>     → sandbox-returned file
 */
class FakeRunner : CodeRunner {
    override val languages = setOf("js", "python")
    var runs = 0
    override suspend fun run(language: String, code: String, workspace: Workspace, timeoutMs: Long): CodeResult {
        runs++
        val out = StringBuilder()
        val files = LinkedHashMap<String, String>()
        for (line in code.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split(Regex("\\s+"), limit = 3)
            when (parts[0]) {
                "print" -> out.append(parts.drop(1).joinToString(" ")).append('\n')
                "metric" -> out.append("METRIC ${parts[1]}=${parts[2]}\n")
                "fail" -> return CodeResult(false, out.toString(), "Error: failed", durationMs = 1)
                "hang" -> return CodeResult(false, out.toString(), "TIMEOUT", timedOut = true, durationMs = timeoutMs)
                "write" -> files[parts[1]] = parts.getOrElse(2) { "" }
                "read" -> out.append(workspace.read(parts[1])).append('\n')
            }
        }
        return CodeResult(true, out.toString(), writtenFiles = files, durationMs = 1)
    }
}

class FakeWeb(var failing: Boolean = false) : WebAccess {
    override suspend fun search(query: String): List<SearchHit> {
        if (failing) throw java.io.IOException("network unreachable")
        return listOf(SearchHit("Result for $query", "https://example.org/${query.hashCode()}", "about $query"))
    }
    override suspend fun fetch(url: String): String {
        if (failing) throw java.io.IOException("HTTP 503")
        return "Page body of $url. Huffman coding and LZ77 are classic."
    }
}

fun newStore(clock: Clock = Clock()) = ProjectStore(tempDir(), clock.fn)

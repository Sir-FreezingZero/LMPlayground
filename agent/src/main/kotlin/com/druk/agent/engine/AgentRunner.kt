package com.druk.agent.engine

import com.druk.agent.json.Json
import com.druk.agent.json.long
import com.druk.agent.json.list
import com.druk.agent.model.ProjectStatus
import com.druk.agent.store.ProjectStore
import kotlinx.coroutines.delay
import java.io.File

/** Device conditions gate (battery, thermal, power saver). */
fun interface RunPolicy {
    fun decide(): Decision

    sealed class Decision {
        object Run : Decision()
        data class Throttle(val delayMs: Long, val reason: String) : Decision()
        data class Stop(val reason: String) : Decision()
    }

    companion object { val ALWAYS = RunPolicy { Decision.Run } }
}

/**
 * Refuses to start when the runner keeps dying before completing a step
 * (e.g. a model that OOM-kills the process on load). Without this, the
 * "restart after process death" machinery becomes a crash loop.
 */
class CrashLoopGuard(private val file: File, private val clock: () -> Long, private val maxStarts: Int = 4, private val windowMs: Long = 10 * 60_000) {
    @Suppress("UNCHECKED_CAST")
    private fun read(): List<Long> = try { Json.parseObject(file.readText()).list("starts").mapNotNull { (it as? Number)?.toLong() } } catch (_: Exception) { emptyList() }
    private fun write(starts: List<Long>) = ProjectStore.atomicWrite(file, Json.stringify(mapOf("starts" to starts)).toByteArray())

    /** Record a start; false if there were too many starts without a completed step. */
    fun onStart(): Boolean {
        val now = clock()
        val recent = read().filter { now - it < windowMs }
        if (recent.size >= maxStarts) return false
        write(recent + now)
        return true
    }

    /** A step completed: the process is healthy; forget earlier starts. */
    fun onHealthy() { if (read().isNotEmpty()) write(emptyList()) }

    fun reset() = file.delete()
}

/**
 * Drives all ACTIVE projects, one step at a time, round-robin (least
 * recently stepped first), so several projects share one small model
 * without loading more than one at once. Returns when nothing is runnable,
 * the policy says stop, or [shouldContinue] turns false. Cancelling the
 * calling coroutine aborts the step in flight without committing it.
 */
class AgentRunner(
    private val store: ProjectStore,
    private val engine: AgentEngine,
    private val policy: RunPolicy = RunPolicy.ALWAYS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxIdleWaitMs: Long = 5 * 60_000,
) {
    enum class Exit { IDLE, POLICY_STOP, REQUESTED, CRASH_LOOP, STEP_LIMIT }

    private val lastStepped = HashMap<String, Long>()
    private var tick = 0L
    private var recovered = false

    fun recoverAll() {
        store.ids().forEach { engine.recover(it) }
        recovered = true
    }

    suspend fun run(
        guard: CrashLoopGuard? = null,
        maxSteps: Long = Long.MAX_VALUE,
        shouldContinue: () -> Boolean = { true },
        onStep: (StepReport) -> Unit = {},
    ): Exit {
        if (guard != null && !guard.onStart()) return Exit.CRASH_LOOP
        if (!recovered) recoverAll()
        var steps = 0L
        var idleStreak = 0
        while (shouldContinue()) {
            if (steps >= maxSteps) return Exit.STEP_LIMIT
            when (val d = policy.decide()) {
                is RunPolicy.Decision.Stop -> return Exit.POLICY_STOP
                is RunPolicy.Decision.Throttle -> { sleep(d.delayMs); continue }
                RunPolicy.Decision.Run -> {}
            }
            val active = store.list().filter { it.status == ProjectStatus.ACTIVE }
            if (active.isEmpty()) return Exit.IDLE
            val now = clock()
            val ready = active.filter { it.notBeforeMillis <= now }
            if (ready.isEmpty()) {
                val wait = (active.minOf { it.notBeforeMillis } - now).coerceIn(1_000, maxIdleWaitMs)
                if (wait >= maxIdleWaitMs) return Exit.IDLE // long backoff: let the scheduler wake us later
                sleep(wait)
                continue
            }
            val pick = ready.minBy { lastStepped[it.id] ?: 0L }
            lastStepped[pick.id] = ++tick
            val report = engine.step(pick.id)
            if (!report.idle) {
                steps++
                idleStreak = 0
                guard?.onHealthy()
            } else if (++idleStreak > 2 * ready.size + 2) {
                // Every ready project declined to step; don't spin.
                sleep(1_000)
                idleStreak = 0
            }
            onStep(report)
        }
        return Exit.REQUESTED
    }
}

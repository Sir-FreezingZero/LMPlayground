package com.druk.agent.engine

import com.druk.agent.model.*
import com.druk.agent.scheduler.TaskScheduler
import com.druk.agent.store.ProjectStore

/**
 * User-initiated changes. These are the *only* way a paused or stopped
 * project becomes active again — the runner never resumes on its own.
 *
 * Ids created here use a "u" infix (tu…, cu…) so they can't collide with
 * ids the engine allocates concurrently from the shared counter.
 */
class ProjectControl(private val store: ProjectStore, private val clock: () -> Long = System::currentTimeMillis) {

    private fun userId(prefix: String) = prefix + "u" + java.lang.Long.toString(clock(), 36) + (0..999).random()

    fun pause(id: String) = store.setStatus(id, ProjectStatus.PAUSED)

    fun stop(id: String) = store.setStatus(id, ProjectStatus.STOPPED)

    /** Explicit resume. Clears stall blockers and watchdog counters so the agent gets a fresh run. */
    fun resume(id: String) = store.update(id) { p ->
        p.copy(
            status = ProjectStatus.ACTIVE, notBeforeMillis = 0, llmFailures = 0, finalReport = if (p.status == ProjectStatus.COMPLETED) "" else p.finalReport,
            blockers = p.blockers.map { if (it.needsUser) it.copy(resolved = true) else it },
            watchdog = p.watchdog.copy(stepsWithoutProgress = 0, strategyChangesWithoutProgress = 0, emptyPlanRounds = 0, warnings = emptyList(), reflectPending = false),
            needsPlanning = p.needsPlanning || p.status == ProjectStatus.COMPLETED,
        )
    }

    fun changeObjective(id: String, objective: String) = store.update(id) { p ->
        if (objective.isBlank() || objective == p.objective) p
        else p.copy(objective = objective.trim(), needsPlanning = true,
            memory = p.memory + MemoryEntry(userId("m"), MemoryKind.INSTRUCTION, "Objective changed from: ${p.objective.take(300)}", 4, step = p.stepCount))
    }

    fun addInstruction(id: String, text: String) = store.update(id) { p ->
        if (text.isBlank()) p else p.copy(instructions = (p.instructions + text.trim()).takeLast(20), needsPlanning = true)
    }

    fun addCriterion(id: String, text: String) = store.update(id) { p ->
        if (text.isBlank()) p else p.copy(criteria = p.criteria + CompletionCriterion(userId("c"), text.trim()))
    }

    fun retryTask(id: String, taskId: String) = store.update(id) { TaskScheduler.retry(it, taskId) }

    fun cancelTask(id: String, taskId: String) = store.update(id) { TaskScheduler.cancel(it, taskId, "cancelled by user") }

    fun resolveBlocker(id: String, blockerId: String) = store.update(id) { p ->
        p.copy(blockers = p.blockers.map { if (it.id == blockerId) it.copy(resolved = true) else it })
    }

    fun setModel(id: String, modelFile: String) = store.update(id) { it.copy(modelFile = modelFile) }

    fun rename(id: String, title: String) = store.update(id) { if (title.isBlank()) it else it.copy(title = title.trim()) }
}

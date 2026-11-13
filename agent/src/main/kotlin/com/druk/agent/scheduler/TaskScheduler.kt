package com.druk.agent.scheduler

import com.druk.agent.model.Project
import com.druk.agent.model.Task
import com.druk.agent.model.TaskStatus

/**
 * Pure task-graph logic. No I/O: the engine applies these inside
 * ProjectStore.update so each transition commits atomically.
 */
object TaskScheduler {

    /** Interruptions (process deaths mid-step) tolerated per task before it is failed. */
    const val MAX_INTERRUPTIONS = 3

    /**
     * Re-derive BLOCKED state from dependencies. A task whose dependency
     * FAILED or was CANCELLED is blocked (with the reason recorded); a
     * blocked task whose dependencies became satisfiable again (e.g. the
     * user retried the failed one) returns to PENDING. Unrelated tasks are
     * untouched — one failure never stalls independent work.
     */
    fun refreshBlocked(p: Project): Project {
        val byId = p.tasks.associateBy { it.id }
        var changed = false
        val tasks = p.tasks.map { t ->
            if (t.status != TaskStatus.PENDING && t.status != TaskStatus.BLOCKED) return@map t
            val dead = t.dependsOn.mapNotNull { byId[it] }.filter {
                it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED
            }
            val missing = t.dependsOn.filter { it !in byId }
            when {
                dead.isNotEmpty() || missing.isNotEmpty() -> {
                    val reason = buildString {
                        if (dead.isNotEmpty()) append("depends on ${dead.joinToString { "${it.id} (${it.status.name.lowercase()})" }}")
                        if (missing.isNotEmpty()) { if (isNotEmpty()) append("; "); append("unknown dependency ${missing.joinToString()}") }
                    }
                    if (t.status != TaskStatus.BLOCKED || t.blockedReason != reason) {
                        changed = true
                        t.copy(status = TaskStatus.BLOCKED, blockedReason = reason)
                    } else t
                }
                t.status == TaskStatus.BLOCKED -> { changed = true; t.copy(status = TaskStatus.PENDING, blockedReason = "") }
                else -> t
            }
        }
        return if (changed) p.copy(tasks = tasks) else p
    }

    fun isReady(t: Task, p: Project): Boolean {
        if (t.status != TaskStatus.PENDING) return false
        if (t.notBeforeStep > p.stepCount) return false
        return t.dependsOn.all { dep -> p.task(dep)?.status == TaskStatus.DONE }
    }

    /**
     * The task to work on next: a RUNNING task (resume after restart) first,
     * then the highest-priority ready task, oldest first among equals.
     */
    fun next(p: Project): Task? {
        p.tasks.firstOrNull { it.status == TaskStatus.RUNNING }?.let { return it }
        return p.tasks.filter { isReady(it, p) }
            .sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.createdStep }.thenBy { it.id.drop(1).toLongOrNull() ?: 0 })
            .firstOrNull()
    }

    /** Pending tasks only waiting on backoff — the project still has work. */
    fun hasWaiting(p: Project): Boolean = p.tasks.any {
        it.status == TaskStatus.PENDING && it.notBeforeStep > p.stepCount &&
            it.dependsOn.all { d -> p.task(d)?.status == TaskStatus.DONE }
    }

    fun hasOpenWork(p: Project): Boolean = p.tasks.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }

    fun start(p: Project, taskId: String): Project = mapTask(p, taskId) {
        if (it.status == TaskStatus.PENDING) it.copy(status = TaskStatus.RUNNING, stepsThisAttempt = 0) else it
    }

    fun complete(p: Project, taskId: String, result: String, evidence: List<String>): Project =
        refreshBlocked(mapTask(p, taskId) { it.copy(status = TaskStatus.DONE, result = result, evidence = evidence) })

    /**
     * Record a failed attempt. Retries with exponential backoff (in steps,
     * so other tasks run meanwhile) until [Task.maxAttempts], then FAILED,
     * which blocks dependents but nothing else.
     */
    fun fail(p: Project, taskId: String, reason: String): Project = refreshBlocked(mapTask(p, taskId) { t ->
        val attempts = t.attempts + 1
        if (attempts >= t.maxAttempts) {
            t.copy(status = TaskStatus.FAILED, attempts = attempts, failureReason = reason)
        } else {
            t.copy(
                status = TaskStatus.PENDING, attempts = attempts, failureReason = reason,
                notBeforeStep = p.stepCount + (1L shl attempts), stepsThisAttempt = 0,
            )
        }
    })

    /**
     * Process death interrupted a step on [taskId]. Requeue it, but a task
     * that keeps getting killed (a tool that OOMs the process, say) is
     * failed after [MAX_INTERRUPTIONS] so recovery cannot loop forever.
     */
    fun interrupted(p: Project, taskId: String): Project {
        val t = p.task(taskId) ?: return p
        val n = t.interruptions + 1
        return if (n >= MAX_INTERRUPTIONS) {
            refreshBlocked(mapTask(p, taskId) { it.copy(status = TaskStatus.FAILED, interruptions = n, failureReason = "Interrupted $n times (process killed during this task)") })
        } else {
            mapTask(p, taskId) { it.copy(status = if (it.status == TaskStatus.RUNNING) TaskStatus.PENDING else it.status, interruptions = n) }
        }
    }

    /** User-initiated retry of a failed/blocked task. */
    fun retry(p: Project, taskId: String): Project = refreshBlocked(mapTask(p, taskId) {
        it.copy(status = TaskStatus.PENDING, attempts = 0, interruptions = 0, notBeforeStep = 0, failureReason = "", blockedReason = "")
    })

    fun cancel(p: Project, taskId: String, reason: String): Project = refreshBlocked(mapTask(p, taskId) {
        if (it.isTerminal) it else it.copy(status = TaskStatus.CANCELLED, failureReason = reason)
    })

    /**
     * Add a task. Dependencies must exist and must not create a cycle;
     * returns null with the problem otherwise.
     */
    fun add(p: Project, title: String, description: String, priority: Int, dependsOn: List<String>, strategyGen: Int): Pair<Project, String>? {
        val deps = dependsOn.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (deps.any { p.task(it) == null }) return null
        val (np, id) = p.nextId("t")
        val t = Task(id = id, title = title.trim().take(120), description = description.trim().take(600),
            priority = priority.coerceIn(1, 5), dependsOn = deps, createdStep = p.stepCount, strategyGen = strategyGen)
        val withTask = np.copy(tasks = np.tasks + t)
        if (hasCycle(withTask)) return null
        return refreshBlocked(withTask) to id
    }

    fun hasCycle(p: Project): Boolean {
        val byId = p.tasks.associateBy { it.id }
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done
        fun visit(id: String): Boolean {
            when (state[id]) { 1 -> return true; 2 -> return false }
            state[id] = 1
            val cyc = byId[id]?.dependsOn?.any { visit(it) } ?: false
            state[id] = 2
            return cyc
        }
        return p.tasks.any { visit(it.id) }
    }

    fun mapTask(p: Project, taskId: String, fn: (Task) -> Task): Project =
        p.copy(tasks = p.tasks.map { if (it.id == taskId) fn(it) else it })
}

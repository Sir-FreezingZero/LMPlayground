package com.druk.agent.engine

import com.druk.agent.llm.*
import com.druk.agent.memory.MemoryConsolidator
import com.druk.agent.memory.Text
import com.druk.agent.model.*
import com.druk.agent.scheduler.TaskScheduler
import com.druk.agent.store.ProjectStore
import com.druk.agent.tools.*
import com.druk.agent.verify.CompletionVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class EngineConfig(
    val contextBudgetChars: Int = 7000,
    val actMaxTokens: Int = 700,
    val planMaxTokens: Int = 400,
    val llmTimeoutMs: Long = 10 * 60_000,
    val codeTimeoutMs: Long = 20_000,
    val checkpointEvery: Int = 20,
    val maxLlmFailures: Int = 6,
    val llmBackoffBaseMs: Long = 15_000,
    val llmBackoffMaxMs: Long = 30 * 60_000,
    /** Use the model for memory summaries (one extra call per ~20 entries); extractive otherwise. */
    val llmSummaries: Boolean = true,
    val recentStepsKept: Int = 40,
)

/** Test hook: throw from [at] to simulate process death at a named point. */
fun interface FaultInjector {
    fun at(point: String)
    companion object { val NONE = FaultInjector { } }
}

/** Thrown by fault injection; an Error so no tool-level catch-all swallows it. */
class SimulatedCrash(point: String) : Error("simulated crash at $point")

data class StepReport(
    val projectId: String,
    val kind: String,
    val step: Long = 0,
    val action: String = "",
    val progress: Progress = Progress.NONE,
    val message: String = "",
    /** Nothing was done (project not active / waiting); runner should move on. */
    val idle: Boolean = false,
)

/**
 * One step of autonomous work on one project:
 *
 *   load state → (reflect | plan | verify | act) → commit → log
 *
 * Each step makes at most one model call for its decision (plus an
 * occasional summary), executes at most one tool, and commits one atomic
 * snapshot. Commit order is snapshot → step log → clear in-flight marker,
 * so recovery can always tell whether the step in flight was committed.
 */
class AgentEngine(
    val store: ProjectStore,
    private val llm: LlmBackend,
    private val tools: ToolRegistry = ToolRegistry.standard(),
    private val runner: CodeRunner? = null,
    private val web: WebAccess? = null,
    private val config: EngineConfig = EngineConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val faults: FaultInjector = FaultInjector.NONE,
) {
    private val ctxBuilder = ContextBuilder(config.contextBudgetChars)

    private val activeTools: Set<String> = tools.names.filter { name ->
        when (name) {
            "web_search", "read_url" -> web != null
            "run_code", "run_experiment" -> runner != null && runner.languages.isNotEmpty()
            else -> true
        }
    }.toSet()

    private fun toolsView() = ToolRegistry(activeTools.mapNotNull { tools.tools[it] })

    // ---------------- recovery ----------------

    /**
     * Reconcile a project after a process start. A step whose in-flight
     * marker survives without a matching commit was interrupted: its task is
     * requeued (and failed after repeated interruptions). Committed steps
     * missing from the log are re-appended from the snapshot.
     */
    fun recover(id: String): Project? {
        val inflight = store.readInFlight(id)
        var p = store.load(id) ?: return null
        if (inflight != null) {
            if (inflight.step > p.stepCount) {
                p = store.update(id) { cur ->
                    val withInt = if (inflight.taskId.isNotEmpty()) TaskScheduler.interrupted(cur, inflight.taskId) else cur
                    val (np, mid) = withInt.nextId("m")
                    np.copy(memory = np.memory + MemoryEntry(mid, MemoryKind.FAILURE,
                        "Step ${inflight.step} (${inflight.action}) was interrupted by process death; work not committed.", 2, step = cur.stepCount))
                } ?: p
            }
            store.clearInFlight(id)
        }
        val logged = store.readSteps(id, config.recentStepsKept).lastOrNull()?.step ?: 0
        p.recentSteps.filter { it.step > logged }.forEach { store.appendStep(id, it) }
        return p
    }

    fun stepOk(id: String, p: Project): (Long) -> Boolean = { s ->
        p.recentSteps.firstOrNull { it.step == s }?.ok ?: store.readSteps(id, 100_000).firstOrNull { it.step == s }?.ok ?: false
    }

    // ---------------- step ----------------

    suspend fun step(id: String): StepReport {
        if (store.readInFlight(id) != null) recover(id)
        val loaded = store.load(id) ?: return StepReport(id, "missing", idle = true)
        if (loaded.status != ProjectStatus.ACTIVE) return StepReport(id, "inactive", idle = true, message = loaded.status.name)
        if (loaded.notBeforeMillis > clock()) return StepReport(id, "backoff", idle = true)

        val ws = Workspace(store.workspaceDir(id))
        var p = TaskScheduler.refreshBlocked(loaded)

        // Verified completion needs no model call.
        if (p.criteria.isNotEmpty() && p.criteria.all { it.satisfied }) {
            val v = CompletionVerifier.verify(p, ws, stepOk(id, p), p.summary)
            if (v.passed) return commitSimple(loaded, p, "verify", "verify", true, "All completion criteria verified.", Progress.MEANINGFUL) {
                ws.write("REPORT.md", v.report)
                it.copy(status = ProjectStatus.COMPLETED, finalReport = v.report, lastAchievement = "Project verified complete")
            }
            // A criterion's evidence went stale (file changed): un-satisfy it so work continues.
            p = p.copy(criteria = p.criteria.map { c -> if (v.problems.any { pr -> pr.contains(c.id + " ") }) c.copy(satisfied = false) else c })
        }

        if (p.watchdog.reflectPending) return reflect(loaded, p)

        val hasRunnable = TaskScheduler.next(p) != null || TaskScheduler.hasWaiting(p)
        if (p.needsPlanning || !hasRunnable) return plan(loaded, p, ws)

        val task = TaskScheduler.next(p) ?: p.tasks.filter { it.status == TaskStatus.PENDING }.minBy { it.notBeforeStep }
        return act(loaded, p, task, ws)
    }

    private suspend fun act(loaded: Project, p0: Project, task0: Task, ws: Workspace): StepReport {
        val id = p0.id
        // Persist RUNNING so a restart resumes this task instead of re-picking.
        val base = store.update(id) { cur -> TaskScheduler.start(TaskScheduler.refreshBlocked(cur), task0.id) } ?: return StepReport(id, "missing", idle = true)
        if (base.status != ProjectStatus.ACTIVE) return StepReport(id, "inactive", idle = true)
        val task = base.task(task0.id) ?: return StepReport(id, "missing-task", idle = true)

        val recentForTask = base.recentSteps.filter { it.taskId == task.id }.takeLast(5)
        val view = toolsView()
        val prompt = ctxBuilder.act(base, task, view, ws, recentForTask)
        val raw = callLlm(base, LlmRequest(ctxBuilder.systemAct, prompt, config.actMaxTokens, "act", model = base.modelFile))
            ?: return StepReport(id, "llm-unavailable", idle = true, message = "model unavailable; backing off")

        var parsed = ActionParser.parse(raw, view.names) { t -> view.tools[t]?.blockArg ?: "content" }
        if (parsed == null) {
            val fix = prompt + "\n\n## YOUR PREVIOUS REPLY WAS INVALID\n" + Text.clip(raw, 300) +
                "\nReply again with exactly:\nTHOUGHT: ...\nACTION: <one of ${view.names.joinToString()}>\n<arg>: <value>"
            val raw2 = callLlm(base, LlmRequest(ctxBuilder.systemAct, fix, config.actMaxTokens, "act-retry", model = base.modelFile))
                ?: return StepReport(id, "llm-unavailable", idle = true)
            parsed = ActionParser.parse(raw2, view.names) { t -> view.tools[t]?.blockArg ?: "content" }
        }

        val stepNo = base.stepCount + 1
        if (parsed == null) {
            return finishAct(base, base.copy(stepCount = stepNo), task, "invalid", "", false,
                "Model reply was not a valid action: " + Text.oneLine(raw, 200), Effect.ERROR, "", "", "")
        }

        val argsSummary = parsed.args.entries.joinToString(" ") { (k, v) -> "$k=" + Text.oneLine(v, 60) }
        val fingerprint = parsed.tool + ":" + Text.normalizedHash(parsed.args.toSortedMap().toString())
        store.markInFlight(id, stepNo, task.id, parsed.tool)
        faults.at("before-tool")
        val ctx = ToolContext(base.copy(stepCount = stepNo), task.id, ws, runner, web, stepOk(id, base), config.codeTimeoutMs)
        val result = tools.execute(parsed.tool, parsed.args, ctx)
        faults.at("after-tool")
        return finishAct(base, ctx.project, task, parsed.tool, argsSummary, result.ok, result.output, result.effect,
            fingerprint, result.contentKey, parsed.thought, result.achievement)
    }

    private suspend fun finishAct(
        base: Project, worked: Project, task: Task, action: String, args: String, ok: Boolean, output: String,
        effect: Effect, fingerprint: String, contentKey: String, thought: String, achievement: String = "",
    ): StepReport {
        val id = base.id
        val stepNo = worked.stepCount
        val progress = Watchdog.classify(effect, fingerprint, worked.watchdog)
        var p = worked
        // Step accounting on the task, then the per-attempt budget.
        p = TaskScheduler.mapTask(p, task.id) { it.copy(stepsSpent = it.stepsSpent + 1, stepsThisAttempt = it.stepsThisAttempt + 1) }
        val events = mutableListOf<String>()
        p.task(task.id)?.let { t ->
            if (t.status == TaskStatus.RUNNING && t.stepsThisAttempt >= Watchdog.TASK_STEP_LIMIT) {
                p = TaskScheduler.fail(p, t.id, "step budget (${Watchdog.TASK_STEP_LIMIT}) exhausted without completing")
                val (np, mid) = p.nextId("m")
                p = np.copy(memory = np.memory + MemoryEntry(mid, MemoryKind.FAILURE, "Task ${t.id} (${t.title}) ran out of steps; last action $action.", 3, taskId = t.id, step = stepNo))
                events += "task ${t.id} attempt failed: step budget"
            }
        }
        val wd = Watchdog.afterStep(p, progress, fingerprint, contentKey, action, task.id)
        p = wd.project
        events += wd.events
        if (achievement.isNotEmpty() && progress == Progress.MEANINGFUL) p = p.copy(lastAchievement = "s$stepNo: $achievement")
        p = p.copy(nextAction = thought.ifBlank { p.task(task.id)?.let { if (it.status == TaskStatus.RUNNING) "Continue ${it.id}: ${it.title}" else "" } ?: "" })
        val rec = StepRecord(stepNo, task.id, "act", action, args, ok, Text.clip(output, 2000), progress,
            (listOfNotNull(thought.takeIf { it.isNotBlank() }) + events).joinToString(" | "), fingerprint, clock())
        p = housekeeping(p, rec, progress == Progress.MEANINGFUL && (effect == Effect.TASK_DONE || effect == Effect.CRITERION || effect == Effect.EXPERIMENT))
        return commit(base, p, rec)
    }

    private suspend fun plan(loaded: Project, p0: Project, ws: Workspace): StepReport {
        val id = p0.id
        val base = store.update(id) { TaskScheduler.refreshBlocked(it) } ?: return StepReport(id, "missing", idle = true)
        val raw = callLlm(base, LlmRequest(ctxBuilder.systemPlan, ctxBuilder.plan(base, ws), config.planMaxTokens, "plan", model = base.modelFile))
            ?: return StepReport(id, "llm-unavailable", idle = true)
        val out = PlanParser.parse(raw)
        val stepNo = base.stepCount + 1
        var p = base.copy(stepCount = stepNo)
        val gen = p.strategyHistory.size
        val localIds = HashMap<String, String>()
        // Cancelled tasks (retired by a strategy change) may be re-planned; failed ones may not.
        val existingTitles = p.tasks.filter { it.status != TaskStatus.CANCELLED }.map { it.title }
        var added = 0
        out.tasks.forEachIndexed { i, t ->
            if (existingTitles.any { Text.similarity(it, t.title) > 0.7 }) return@forEachIndexed
            val deps = t.dependsOn.mapNotNull { d -> if (d.startsWith("t")) d.takeIf { p.task(d) != null } else localIds[d] }
            val r = TaskScheduler.add(p, t.title, t.description, t.priority, deps, gen) ?: TaskScheduler.add(p, t.title, t.description, t.priority, emptyList(), gen)
            if (r != null) { p = r.first; localIds[(i + 1).toString()] = r.second; added++ }
        }
        val wd = p.watchdog
        val events = mutableListOf<String>()
        if (added == 0) {
            val empty = wd.emptyPlanRounds + 1
            p = p.copy(watchdog = wd.copy(emptyPlanRounds = empty, planRounds = wd.planRounds + 1, stepsWithoutProgress = wd.stepsWithoutProgress + 2))
            if (empty >= 3) {
                p = Watchdog.pauseStalled(p, "Planner produced no new usable tasks $empty times in a row. Add instructions or adjust the objective, then resume.")
                events += "paused: planning stalled"
            }
        } else {
            p = p.copy(needsPlanning = false, strategy = out.strategy.ifBlank { p.strategy }.take(400),
                watchdog = wd.copy(emptyPlanRounds = 0, planRounds = wd.planRounds + 1, stepsWithoutProgress = wd.stepsWithoutProgress + 1),
                nextAction = TaskScheduler.next(p)?.let { "Start ${it.id}: ${it.title}" } ?: "")
        }
        val rec = StepRecord(stepNo, "", "plan", "plan", "", added > 0,
            if (added > 0) "strategy: ${p.strategy}\nadded $added task(s): " + p.tasks.takeLast(added).joinToString("; ") { "${it.id} ${it.title}" }
            else "no usable tasks in plan: " + Text.oneLine(raw, 300),
            if (added > 0) Progress.NEUTRAL else Progress.NONE, events.joinToString(" | "), "plan:" + Text.normalizedHash(raw), clock())
        p = housekeeping(p, rec, false)
        return commit(base, p, rec)
    }

    private suspend fun reflect(loaded: Project, p0: Project): StepReport {
        val id = p0.id
        val base = store.update(id) { it } ?: return StepReport(id, "missing", idle = true)
        val raw = callLlm(base, LlmRequest(ctxBuilder.systemReflect, ctxBuilder.reflect(base, base.watchdog.reflectReason, base.recentSteps), 250, "reflect", model = base.modelFile))
            ?: return StepReport(id, "llm-unavailable", idle = true)
        val r = ReflectionParser.parse(raw)?.takeIf { refl -> base.strategyHistory.none { Text.similarity(it.oldStrategy, refl.newApproach) > 0.8 } }
            ?: Reflection(base.watchdog.reflectReason, "Simplify: pick the smallest sub-problem that can be measured, implement it minimally, and record an experiment before anything else.")
        val stepNo = base.stepCount + 1
        var p = base.copy(stepCount = stepNo)
        val gen = p.strategyHistory.size
        p = p.copy(strategyHistory = p.strategyHistory + StrategyChange(stepNo, p.strategy, r.newApproach, r.whyFailed.ifBlank { p.watchdog.reflectReason }))
        val (np, mid) = p.nextId("m")
        p = np.copy(memory = np.memory + MemoryEntry(mid, MemoryKind.FAILURE, "Strategy abandoned: ${Text.oneLine(base.strategy, 200)}. Why: ${Text.oneLine(r.whyFailed, 200)}", 4, step = stepNo))
        // Retire unfinished work from the abandoned strategy; done work stays.
        for (t in p.tasks) if (!t.isTerminal && t.strategyGen <= gen) p = TaskScheduler.cancel(p, t.id, "strategy changed at step $stepNo")
        p = p.copy(strategy = r.newApproach.take(400), needsPlanning = true,
            watchdog = p.watchdog.copy(reflectPending = false, reflectReason = "", stepsWithoutProgress = 0, warnings = emptyList(),
                strategyChangesWithoutProgress = p.watchdog.strategyChangesWithoutProgress + 1))
        val rec = StepRecord(stepNo, "", "reflect", "reflect", "", true, "why failed: ${r.whyFailed}\nnew approach: ${r.newApproach}", Progress.NEUTRAL, "strategy change", "", clock())
        p = housekeeping(p, rec, true)
        return commit(base, p, rec)
    }

    private suspend fun commitSimple(loaded: Project, p: Project, kind: String, action: String, ok: Boolean, msg: String, progress: Progress, fn: (Project) -> Project): StepReport {
        val base = store.update(p.id) { it } ?: return StepReport(p.id, "missing", idle = true)
        val stepNo = base.stepCount + 1
        val next = fn(p.copy(stepCount = stepNo))
        val rec = StepRecord(stepNo, "", kind, action, "", ok, msg, progress, "", "", clock())
        return commit(base, housekeeping(next, rec, true), rec)
    }

    // ---------------- commit & housekeeping ----------------

    private fun commit(base: Project, computed: Project, rec: StepRecord): StepReport {
        faults.at("before-commit")
        val committed = store.update(base.id) { latest -> merge(base, latest, computed) } ?: return StepReport(base.id, "missing", idle = true)
        if (committed.stepCount % config.checkpointEvery == 0L || computed.checkpoints.size != base.checkpoints.size) store.writeCheckpoint(committed)
        faults.at("after-commit")
        store.appendStep(base.id, rec)
        store.clearInFlight(base.id)
        return StepReport(base.id, rec.kind, rec.step, rec.action, rec.progress, rec.output.take(300))
    }

    /**
     * Apply a step's result on top of the latest committed state. The user
     * may have paused, edited the objective, added instructions/criteria or
     * retried a task while the model was thinking: those edits win.
     */
    fun merge(base: Project, latest: Project, computed: Project): Project {
        if (latest.rev == base.rev) return computed
        var r = computed.copy(
            title = latest.title, objective = latest.objective, instructions = latest.instructions, modelFile = latest.modelFile,
            idCounter = maxOf(computed.idCounter, latest.idCounter),
        )
        if (latest.status != base.status) r = r.copy(status = latest.status, notBeforeMillis = latest.notBeforeMillis)
        if (latest.objective != base.objective) r = r.copy(needsPlanning = true)
        if (latest.criteria != base.criteria) {
            val baseC = base.criteria.associateBy { it.id }
            val compC = computed.criteria.associateBy { it.id }
            r = r.copy(criteria = latest.criteria.map { c -> if (baseC[c.id] == c) compC[c.id] ?: c else c })
        }
        if (latest.tasks != base.tasks) {
            val baseT = base.tasks.associateBy { it.id }
            val latT = latest.tasks.associateBy { it.id }
            val compIds = computed.tasks.map { it.id }.toSet()
            r = r.copy(tasks = computed.tasks.map { t -> latT[t.id]?.takeIf { it != baseT[t.id] } ?: t } + latest.tasks.filter { it.id !in compIds })
            r = TaskScheduler.refreshBlocked(r)
        }
        if (latest.blockers != base.blockers) {
            val latB = latest.blockers.associateBy { it.id }
            val compIds = computed.blockers.map { it.id }.toSet()
            r = r.copy(blockers = computed.blockers.map { b -> if (latB[b.id]?.resolved == true) b.copy(resolved = true) else b } + latest.blockers.filter { it.id !in compIds })
        }
        if (latest.watchdog != base.watchdog && latest.watchdog.stepsWithoutProgress == 0 && latest.watchdog.strategyChangesWithoutProgress == 0) {
            // User resumed/reset the watchdog during the step.
            r = r.copy(watchdog = r.watchdog.copy(strategyChangesWithoutProgress = 0))
        }
        return r
    }

    private suspend fun housekeeping(p0: Project, rec: StepRecord, milestone: Boolean): Project {
        var p = p0.copy(recentSteps = (p0.recentSteps + rec).takeLast(config.recentStepsKept))
        if (MemoryConsolidator.needsConsolidation(p)) {
            val batch = MemoryConsolidator.batch(p)
            val text = if (config.llmSummaries) {
                try {
                    withTimeout(config.llmTimeoutMs) {
                        ActionParser.stripThinking(llm.complete(LlmRequest(ctxBuilder.systemSummarize, ctxBuilder.summarize(batch), 220, "summarize", model = p.modelFile))).trim()
                    }.takeIf { it.length > 40 }
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) null else throw e
                } catch (_: Exception) { null }
            } else null
            p = MemoryConsolidator.apply(p, batch, text ?: MemoryConsolidator.extractiveSummary(batch))
        }
        if (milestone || p.stepCount % config.checkpointEvery == 0L) {
            val summary = deterministicSummary(p)
            val (np, cid) = p.nextId("k")
            p = np.copy(summary = summary, checkpoints = (np.checkpoints + Checkpoint(cid, p.stepCount, if (milestone) "milestone" else "periodic", Text.clip(summary, 500), clock())).takeLast(30))
        }
        return p
    }

    fun deterministicSummary(p: Project): String = buildString {
        if (p.strategy.isNotBlank()) append("Strategy: ").append(Text.oneLine(p.strategy, 200)).append('\n')
        val done = p.tasks.filter { it.status == TaskStatus.DONE }
        if (done.isNotEmpty()) append("Done (${done.size}): ").append(done.takeLast(6).joinToString("; ") { "${it.id} ${Text.oneLine(it.title, 50)}" }).append('\n')
        val ok = p.experiments.filter { it.succeeded }
        if (ok.isNotEmpty()) append("Experiments: ${ok.size} ok / ${p.experiments.size}; latest ").append(ok.takeLast(3).joinToString("; ") { "${it.id} ${it.name} ${it.metrics.entries.take(2).joinToString { e -> "${e.key}=${e.value}" }}" }).append('\n')
        val disc = p.memory.filter { it.kind == MemoryKind.DISCOVERY }
        if (disc.isNotEmpty()) append("Discoveries: ").append(disc.takeLast(3).joinToString("; ") { Text.oneLine(it.text, 90) }).append('\n')
        val failedT = p.tasks.count { it.status == TaskStatus.FAILED }
        if (failedT > 0) append("Failed tasks: $failedT\n")
        val crit = p.criteria.count { it.satisfied }
        append("Criteria met: $crit/${p.criteria.size}")
    }

    /** Model call with timeout; on failure records backoff (or pauses after repeated failures) and returns null. */
    private suspend fun callLlm(p: Project, req: LlmRequest): String? {
        val error: String = try {
            val out = withTimeout(config.llmTimeoutMs) { llm.complete(req) }
            if (p.llmFailures > 0) store.update(p.id) { it.copy(llmFailures = 0) }
            return out
        } catch (e: TimeoutCancellationException) {
            "model call timed out after ${config.llmTimeoutMs / 1000}s"
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmUnavailableException) {
            e.message ?: "model unavailable"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        store.update(p.id) { cur ->
            val n = cur.llmFailures + 1
            val delay = minOf(config.llmBackoffMaxMs, config.llmBackoffBaseMs shl minOf(n - 1, 16))
            var next = cur.copy(llmFailures = n, notBeforeMillis = clock() + delay)
            if (n >= config.maxLlmFailures && cur.status == ProjectStatus.ACTIVE) {
                val (np, bid) = next.nextId("b")
                next = np.copy(status = ProjectStatus.PAUSED, blockers = np.blockers + Blocker(bid, "Model failed $n times in a row ($error). Check the model in Models, then resume.", cur.stepCount, needsUser = true))
            }
            next
        }
        return null
    }
}

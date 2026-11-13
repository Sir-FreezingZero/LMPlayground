package com.druk.agent.engine

import com.druk.agent.memory.MemoryRetriever
import com.druk.agent.memory.Text
import com.druk.agent.model.*
import com.druk.agent.tools.ToolRegistry
import com.druk.agent.tools.Workspace

/**
 * Builds the per-step prompt from durable state, within a hard character
 * budget. Sections are ordered by importance; when over budget, the least
 * important are shrunk first, then dropped. Prompt size is therefore
 * independent of project age — step 10 and step 10,000 cost the same.
 *
 * Budget defaults target a 4k-token context on a 1–4B model: ~2k tokens of
 * prompt leaves room for the reply.
 */
class ContextBuilder(private val budgetChars: Int = 7000, private val codeLanguages: Set<String> = setOf("js", "python")) {

    private data class Section(val priority: Int, val title: String, val body: String, val minChars: Int = 0)

    val systemAct = """
You are a persistent research and engineering agent running on a phone. You work on ONE task step at a time; your memory is the project state shown to you.
Rules: take exactly one concrete action per reply. Prefer actions that produce evidence (run code, run experiments, write files). Never claim results you have not measured. If an approach keeps failing, use fail_task with the reason.
Reply format (nothing else):
THOUGHT: <one short sentence>
ACTION: <tool name>
<arg>: <value>
<arg>:
```
<multi-line value>
```
""".trim()

    val systemPlan = "You are the planner of a long-running autonomous project. Break work into small concrete tasks that each produce a file, a measurement or a verified fact. Reply only in the requested format."

    val systemReflect = "You analyse why an approach failed and propose a genuinely different one. Be concrete and brief."

    val systemSummarize = "You compress project notes into a dense factual summary. Keep numbers, file names and ids. No speculation."

    fun act(p: Project, task: Task, tools: ToolRegistry, workspace: Workspace, recentForTask: List<StepRecord>): String {
        val s = mutableListOf<Section>()
        s += Section(100, "OBJECTIVE", Text.clip(p.objective, 700), 200)
        if (p.instructions.isNotEmpty()) s += Section(95, "USER INSTRUCTIONS (highest priority)", p.instructions.takeLast(5).joinToString("\n") { "- " + Text.clip(it, 300) }, 150)
        s += Section(90, "CURRENT TASK ${task.id} (attempt ${task.attempts + 1}/${task.maxAttempts}, step ${task.stepsThisAttempt + 1}/${Watchdog.TASK_STEP_LIMIT})",
            buildString {
                append(task.title)
                if (task.description.isNotBlank()) append("\n").append(Text.clip(task.description, 500))
                if (task.failureReason.isNotBlank()) append("\nPrevious attempt failed: ").append(Text.clip(task.failureReason, 250))
                append("\nWhen done: complete_task with evidence ids. If impossible: fail_task.")
            }, 120)
        if (p.watchdog.warnings.isNotEmpty()) s += Section(88, "WATCHDOG", p.watchdog.warnings.joinToString("\n") { "! $it" }, 80)
        if (recentForTask.isNotEmpty()) {
            val last = recentForTask.last()
            val earlier = recentForTask.dropLast(1).takeLast(4)
            s += Section(85, "RECENT STEPS ON THIS TASK", buildString {
                earlier.forEach { append("s${it.step} ${it.action} ${if (it.ok) "ok" else "FAILED"}: ${Text.oneLine(it.output, 160)}\n") }
                append("s${last.step} ${last.action} ${Text.oneLine(last.args, 120)} → ${if (last.ok) "ok" else "FAILED"}:\n${Text.clip(last.output, 1300)}")
            }, 200)
        }
        if (p.strategy.isNotBlank()) s += Section(80, "STRATEGY", Text.clip(p.strategy, 400), 60)
        s += Section(75, "COMPLETION CRITERIA", p.criteria.joinToString("\n") { "${it.id} [${if (it.satisfied) "x" else " "}] ${Text.oneLine(it.text, 200)}" }, 80)
        val failures = p.memory.filter { it.kind == MemoryKind.FAILURE && !it.archived }.takeLast(4) +
            p.strategyHistory.takeLast(2).map { MemoryEntry("", MemoryKind.FAILURE, "Abandoned strategy: ${it.oldStrategy} — ${it.reason}") }
        if (failures.isNotEmpty()) s += Section(70, "FAILED BEFORE (do not repeat)", failures.joinToString("\n") { "- " + Text.oneLine(it.text, 180) })
        val mem = MemoryRetriever.search(p, task.title + " " + task.description + " " + p.objective, 6)
            .filter { it.kind != MemoryKind.FAILURE }
        if (mem.isNotEmpty()) s += Section(65, "RELEVANT MEMORY", mem.joinToString("\n") { "[${it.id} ${it.kind.name.lowercase()}] ${Text.oneLine(it.text, 220)}" })
        val exps = bestAndRecentExperiments(p)
        if (exps.isNotEmpty()) s += Section(60, "EXPERIMENTS", exps.joinToString("\n") { e ->
            "${e.id} ${e.name}: ${if (e.succeeded) e.metrics.entries.take(4).joinToString { "${it.key}=${fmt(it.value)}" } else "failed"}"
        })
        val done = p.tasks.filter { it.status == TaskStatus.DONE }.takeLast(5)
        if (done.isNotEmpty()) s += Section(55, "DONE TASKS", done.joinToString("\n") { "${it.id} ${Text.oneLine(it.title, 60)} → ${Text.oneLine(it.result, 100)} [${it.evidence.joinToString()}]" })
        val open = p.tasks.filter { !it.isTerminal && it.id != task.id }.take(8)
        if (open.isNotEmpty()) s += Section(50, "OTHER OPEN TASKS", open.joinToString("\n") { "${it.id} [${it.status.name.lowercase()}] ${Text.oneLine(it.title, 70)}" })
        val files = workspace.list().filterNot { it.first.startsWith("research/") }.takeLast(15)
        s += Section(45, "FILES", if (files.isEmpty()) "(none yet)" else files.joinToString(", ") { "${it.first}(${it.second}B)" })
        if (p.summary.isNotBlank()) s += Section(40, "PROJECT SUMMARY", Text.clip(p.summary, 600))
        s += Section(98, "TOOLS", tools.usage(), 400)
        s += Section(99, "REPLY", "One action in the format from the instructions. " + codeHint() + " For measurements print lines like: METRIC ratio=0.41", 100)
        return fit(s)
    }

    private fun codeHint(): String {
        val parts = mutableListOf<String>()
        if ("js" in codeLanguages) parts += "language js: use print(x), readFile(name), writeFile(name, text)"
        if ("python" in codeLanguages) parts += "language python: use print() and open()"
        return if (parts.isEmpty()) "No code execution is available on this device." else "Code runs in a sandbox (" + parts.joinToString("; ") + ")."
    }

    fun plan(p: Project, workspace: Workspace): String {
        val s = mutableListOf<Section>()
        s += Section(100, "OBJECTIVE", Text.clip(p.objective, 900), 200)
        if (p.instructions.isNotEmpty()) s += Section(95, "USER INSTRUCTIONS", p.instructions.takeLast(5).joinToString("\n") { "- " + Text.clip(it, 300) })
        s += Section(90, "COMPLETION CRITERIA", p.criteria.joinToString("\n") { "${it.id} [${if (it.satisfied) "x" else " "}] ${it.text}" })
        if (p.strategy.isNotBlank()) s += Section(85, "CURRENT STRATEGY", p.strategy)
        if (p.strategyHistory.isNotEmpty()) s += Section(84, "ABANDONED STRATEGIES (do not reuse)", p.strategyHistory.takeLast(4).joinToString("\n") { "- ${Text.oneLine(it.oldStrategy, 150)} — ${Text.oneLine(it.reason, 150)}" })
        val tasks = p.tasks.filter { it.status != TaskStatus.CANCELLED }.takeLast(14)
        if (tasks.isNotEmpty()) s += Section(80, "EXISTING TASKS", tasks.joinToString("\n") { "${it.id} [${it.status.name.lowercase()}] ${Text.oneLine(it.title, 80)}${if (it.failureReason.isNotBlank()) " — failed: " + Text.oneLine(it.failureReason, 80) else ""}" })
        val mem = MemoryRetriever.search(p, p.objective + " " + p.strategy, 8)
        if (mem.isNotEmpty()) s += Section(70, "KNOWN SO FAR", mem.joinToString("\n") { "[${it.id}] ${Text.oneLine(it.text, 200)}" })
        val exps = bestAndRecentExperiments(p)
        if (exps.isNotEmpty()) s += Section(65, "EXPERIMENT RESULTS", exps.joinToString("\n") { "${it.id} ${it.name}: ${it.metrics.entries.take(4).joinToString { e -> "${e.key}=${fmt(e.value)}" }}" })
        s += Section(60, "FILES", workspace.list().takeLast(15).joinToString(", ") { it.first }.ifEmpty { "(none)" })
        s += Section(99, "REPLY FORMAT", """
STRATEGY: <one sentence: the overall approach from here>
TASK: <short title> | <what to do and what file/measurement it produces> | <priority 1-5> | <depends on: numbers of earlier TASK lines or existing ids, or none>
(2 to 6 TASK lines. Do not repeat done tasks. Each task must be doable in a few tool calls.)
""".trim(), 200)
        return fit(s)
    }

    fun reflect(p: Project, reason: String, recent: List<StepRecord>): String {
        val s = mutableListOf<Section>()
        s += Section(100, "OBJECTIVE", Text.clip(p.objective, 600))
        s += Section(95, "CURRENT STRATEGY", p.strategy.ifBlank { "(none stated)" })
        s += Section(90, "PROBLEM", reason)
        s += Section(85, "RECENT STEPS", recent.takeLast(10).joinToString("\n") { "s${it.step} ${it.action} ${if (it.ok) "ok" else "FAILED"} [${it.progress.name.lowercase()}]: ${Text.oneLine(it.output, 140)}" })
        val failed = p.memory.filter { it.kind == MemoryKind.FAILURE }.takeLast(5)
        if (failed.isNotEmpty()) s += Section(80, "EARLIER FAILURES", failed.joinToString("\n") { "- " + Text.oneLine(it.text, 160) })
        if (p.strategyHistory.isNotEmpty()) s += Section(75, "STRATEGIES ALREADY ABANDONED", p.strategyHistory.joinToString("\n") { "- " + Text.oneLine(it.oldStrategy, 160) })
        s += Section(99, "REPLY FORMAT", "WHY_FAILED: <one sentence root cause>\nNEW_APPROACH: <one or two sentences, clearly different from the above>")
        return fit(s)
    }

    fun summarize(entries: List<MemoryEntry>): String =
        "Summarize these project notes in under 120 words:\n" + entries.joinToString("\n") { "- [${it.id}] ${Text.oneLine(it.text, 200)}" }.take(budgetChars - 200)

    private fun bestAndRecentExperiments(p: Project): List<Experiment> {
        val ok = p.experiments.filter { it.succeeded }
        val recent = p.experiments.takeLast(4)
        // "Best" per first metric name, both directions are ambiguous so keep extremes.
        val metric = ok.flatMap { it.metrics.keys }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val extremes = if (metric == null) emptyList() else ok.filter { metric in it.metrics }.let { l ->
            listOfNotNull(l.minByOrNull { it.metrics[metric]!! }, l.maxByOrNull { it.metrics[metric]!! })
        }
        return (extremes + recent).distinctBy { it.id }.take(6)
    }

    private fun fmt(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e12) d.toLong().toString() else "%.4g".format(d)

    /**
     * Render sections in the order they were added (the reply format comes
     * last, where small models attend most) and, when over budget, shrink
     * the lowest-priority sections first — down to their minimum on the
     * first pass, to nothing on the second.
     */
    private fun fit(sections: List<Section>): String {
        val secs = sections.toMutableList()
        fun render() = secs.filter { it.body.isNotEmpty() }.joinToString("\n\n") { "## ${it.title}\n${it.body}" }
        var out = render()
        for (pass in 0..1) {
            for (i in secs.indices.sortedBy { secs[it].priority }) {
                val over = out.length - budgetChars
                if (over <= 0) return out
                val sec = secs[i]
                if (sec.body.isEmpty()) continue
                val min = if (pass == 0) sec.minChars else 0
                val target = (sec.body.length - over).coerceAtLeast(min)
                if (target >= sec.body.length) continue
                secs[i] = if (target < 40) sec.copy(body = "") else sec.copy(body = Text.clip(sec.body, target))
                out = render()
            }
        }
        return if (out.length <= budgetChars) out else out.take(budgetChars)
    }
}

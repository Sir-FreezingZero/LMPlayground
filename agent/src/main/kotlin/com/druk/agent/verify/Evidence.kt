package com.druk.agent.verify

import com.druk.agent.model.Project
import com.druk.agent.model.ProjectStatus
import com.druk.agent.model.TaskStatus
import com.druk.agent.tools.Workspace

/**
 * Checks evidence references against recorded facts.
 *
 * Accepted references:
 * - `e12` — an experiment that succeeded (ran cleanly and produced metrics);
 * - `a3` or a workspace path — an artifact whose file still exists with the
 *   recorded hash (so a later overwrite can't silently back an old claim);
 * - `m5` — a memory entry that itself carries valid evidence (no laundering
 *   unsupported notes into evidence);
 * - `s104` — a step that executed successfully, per [stepOk].
 */
class EvidenceChecker(
    private val project: Project,
    private val workspace: Workspace,
    private val stepOk: (Long) -> Boolean,
) {
    data class Result(val valid: List<String>, val invalid: List<Pair<String, String>>)

    fun parseRefs(raw: String): List<String> =
        raw.split(Regex("[,;\\s]+")).map { it.trim().trim('[', ']', '(', ')', '"', '\'', '`', '.') }.filter { it.isNotEmpty() }.distinct()

    fun check(refs: List<String>, depth: Int = 0): Result {
        val valid = mutableListOf<String>()
        val invalid = mutableListOf<Pair<String, String>>()
        for (r in refs) {
            val why = problem(r, depth)
            if (why == null) valid += r else invalid += r to why
        }
        return Result(valid, invalid)
    }

    private fun problem(ref: String, depth: Int): String? {
        val m = Regex("^([eamsEAMS])(\\d+)$").find(ref)
        if (m != null) {
            val kind = m.groupValues[1].lowercase()
            val id = kind + m.groupValues[2]
            return when (kind) {
                "e" -> project.experiments.firstOrNull { it.id == id }?.let { if (it.succeeded) null else "experiment $id did not succeed" } ?: "no experiment $id"
                "a" -> project.artifacts.firstOrNull { it.id == id }?.let { artifactProblem(it.path, it.sha256) } ?: "no artifact $id"
                "m" -> {
                    val e = project.memory.firstOrNull { it.id == id } ?: return "no memory entry $id"
                    if (depth > 2) return "evidence chain too deep at $id"
                    if (e.evidence.isEmpty()) return "memory $id has no evidence of its own"
                    if (check(e.evidence, depth + 1).valid.isEmpty()) "memory $id is not backed by valid evidence" else null
                }
                "s" -> if (stepOk(m.groupValues[2].toLong())) null else "step ${m.groupValues[2]} did not succeed"
                else -> "unknown reference"
            }
        }
        // Treat as a workspace path.
        val art = project.artifacts.lastOrNull { it.path == ref.removePrefix("./") }
            ?: return if (workspace.exists(ref)) "file $ref exists but was never recorded as an artifact" else "unknown evidence '$ref'"
        return artifactProblem(art.path, art.sha256)
    }

    private fun artifactProblem(path: String, sha: String): String? {
        val h = workspace.hash(path) ?: return "artifact file $path is missing"
        return if (h != sha) "artifact $path changed since it was recorded" else null
    }
}

/**
 * Gate for declaring a project complete. Success requires every completion
 * criterion to be satisfied with evidence that is *still* valid now. Open
 * blockers and unfinished tasks don't veto completion, but they are always
 * disclosed in the final report.
 */
object CompletionVerifier {

    data class Verdict(val passed: Boolean, val problems: List<String>, val report: String)

    fun verify(p: Project, workspace: Workspace, stepOk: (Long) -> Boolean, summary: String): Verdict {
        val checker = EvidenceChecker(p, workspace, stepOk)
        val problems = mutableListOf<String>()
        for (c in p.criteria) {
            if (!c.satisfied) { problems += "criterion ${c.id} not satisfied: ${c.text}"; continue }
            val r = checker.check(c.evidence)
            if (r.valid.isEmpty()) problems += "criterion ${c.id} evidence no longer valid: " + r.invalid.joinToString { "${it.first} (${it.second})" }
        }
        if (p.experiments.none { it.succeeded } && p.artifacts.isEmpty()) {
            problems += "no successful experiment or artifact recorded"
        }
        val report = report(p, summary, problems, checker)
        return Verdict(problems.isEmpty(), problems, report)
    }

    fun report(p: Project, summary: String, problems: List<String>, checker: EvidenceChecker? = null): String = buildString {
        appendLine("# ${p.title}")
        appendLine()
        appendLine("**Objective:** ${p.objective}")
        appendLine()
        appendLine("**Status:** ${if (problems.isEmpty()) "verified complete" else "not complete (" + p.status.name.lowercase() + ")"} after ${p.stepCount} steps")
        if (summary.isNotBlank()) { appendLine(); appendLine("## Summary"); appendLine(summary) }
        appendLine(); appendLine("## Completion criteria")
        for (c in p.criteria) {
            val ev = if (c.evidence.isEmpty()) "" else " — evidence: " + c.evidence.joinToString()
            appendLine("- [${if (c.satisfied) "x" else " "}] ${c.text}$ev")
        }
        if (problems.isNotEmpty()) { appendLine(); appendLine("## Verification problems"); problems.forEach { appendLine("- $it") } }
        val exps = p.experiments.filter { it.succeeded }
        if (exps.isNotEmpty()) {
            appendLine(); appendLine("## Experiments with results")
            exps.takeLast(30).forEach { e -> appendLine("- ${e.id} ${e.name}: " + e.metrics.entries.joinToString { "${it.key}=${fmt(it.value)}" }) }
        }
        val disc = p.memory.filter { it.kind == com.druk.agent.model.MemoryKind.DISCOVERY }
        if (disc.isNotEmpty()) { appendLine(); appendLine("## Discoveries"); disc.forEach { appendLine("- ${it.text} (evidence: ${it.evidence.joinToString()})") } }
        if (p.artifacts.isNotEmpty()) { appendLine(); appendLine("## Files"); p.artifacts.distinctBy { it.path }.forEach { appendLine("- ${it.path} (${it.size} bytes) ${it.description}") } }
        val open = p.openBlockers()
        if (open.isNotEmpty()) { appendLine(); appendLine("## Unresolved blockers"); open.forEach { appendLine("- ${it.text}") } }
        val unfinished = p.tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING || it.status == TaskStatus.BLOCKED || it.status == TaskStatus.FAILED }
        if (unfinished.isNotEmpty()) { appendLine(); appendLine("## Unfinished tasks"); unfinished.forEach { appendLine("- ${it.id} [${it.status.name.lowercase()}] ${it.title}${if (it.failureReason.isNotEmpty()) " — ${it.failureReason}" else ""}${if (it.blockedReason.isNotEmpty()) " — ${it.blockedReason}" else ""}") } }
        if (p.strategyHistory.isNotEmpty()) { appendLine(); appendLine("## Strategy changes"); p.strategyHistory.forEach { appendLine("- step ${it.step}: ${it.reason} → ${it.newStrategy}") } }
    }

    private fun fmt(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e12) d.toLong().toString() else "%.4g".format(d)
}

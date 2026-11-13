package com.druk.agent.tools

import com.druk.agent.memory.MemoryRetriever
import com.druk.agent.memory.Text
import com.druk.agent.model.*
import com.druk.agent.scheduler.TaskScheduler
import com.druk.agent.verify.CompletionVerifier
import com.druk.agent.verify.EvidenceChecker

/** What a tool call did, for progress classification. */
enum class Effect {
    NONE, READ, SEARCH, WRITE_NEW, WRITE_DUP, CODE_RUN, CODE_REPEAT, CODE_FAIL, EXPERIMENT, EXPERIMENT_REPEAT,
    FINDING, FINDING_DUP, NOTE, TASK_ADDED, TASK_DONE, TASK_FAILED, CRITERION, PROJECT_DONE, REJECTED, ERROR,
}

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val effect: Effect,
    /** Stable key of the content produced/consumed, for repeat detection. */
    val contentKey: String = "",
    val achievement: String = "",
)

/**
 * Mutable view a tool works against during one step. Tools edit [project]
 * (a working copy) and the workspace; the engine commits the working copy
 * atomically after the step, merged with any concurrent user changes.
 */
class ToolContext(
    var project: Project,
    val taskId: String,
    val workspace: Workspace,
    val runner: CodeRunner?,
    val web: WebAccess?,
    val stepOk: (Long) -> Boolean,
    val codeTimeoutMs: Long = 20_000,
) {
    val step get() = project.stepCount
    fun evidence() = EvidenceChecker(project, workspace, stepOk)

    fun addMemory(kind: MemoryKind, text: String, importance: Int, evidence: List<String> = emptyList()): String {
        val (p, id) = project.nextId("m")
        project = p.copy(memory = p.memory + MemoryEntry(id, kind, text.take(1500), importance.coerceIn(1, 5), evidence, taskId, p.stepCount))
        return id
    }

    /** Register (or re-register) a workspace file as an artifact; returns (id, isNew-content). */
    fun registerArtifact(path: String, description: String): Pair<String, Boolean> {
        val f = workspace.resolve(path)
        val bytes = f.readBytes()
        val sha = Text.sha256(bytes)
        val rel = workspace.relative(f)
        val existing = project.artifacts.lastOrNull { it.path == rel }
        if (existing != null && existing.sha256 == sha) return existing.id to false
        val dupElsewhere = project.artifacts.any { it.sha256 == sha }
        val (p, id) = project.nextId("a")
        project = p.copy(artifacts = p.artifacts.filterNot { it.path == rel } + Artifact(id, rel, sha, bytes.size.toLong(), description.take(200).ifBlank { existing?.description.orEmpty() }, p.stepCount))
        return id to !dupElsewhere
    }
}

abstract class AgentTool(val name: String, val usage: String, val blockArg: String = "content") {
    abstract suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult
    protected fun err(msg: String, effect: Effect = Effect.ERROR) = ToolResult(false, "ERROR: $msg", effect)
    protected fun Map<String, String>.need(k: String, vararg alt: String): String =
        (listOf(k) + alt).firstNotNullOfOrNull { this[it]?.takeIf { v -> v.isNotBlank() } } ?: throw MissingArg(k)

    class MissingArg(val arg: String) : Exception("missing argument '$arg'")
}

class ToolRegistry(tools: List<AgentTool>) {
    val tools: Map<String, AgentTool> = tools.associateBy { it.name }
    val names get() = tools.keys

    fun usage(names: Collection<String> = tools.keys) = names.mapNotNull { tools[it] }.joinToString("\n") { "- ${it.name}: ${it.usage}" }

    suspend fun execute(name: String, args: Map<String, String>, ctx: ToolContext): ToolResult {
        val tool = tools[name] ?: return ToolResult(false, "ERROR: unknown tool '$name'. Tools: ${names.joinToString()}", Effect.ERROR)
        return try {
            tool.run(args, ctx)
        } catch (e: AgentTool.MissingArg) {
            ToolResult(false, "ERROR: ${e.message}. Usage: ${tool.usage}", Effect.ERROR)
        } catch (e: Workspace.AccessDenied) {
            ToolResult(false, "ERROR: ${e.message}", Effect.ERROR)
        } catch (e: java.io.FileNotFoundException) {
            ToolResult(false, "ERROR: ${e.message}", Effect.ERROR)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "ERROR: ${e.javaClass.simpleName}: ${e.message}", Effect.ERROR)
        }
    }

    companion object {
        fun standard(): ToolRegistry = ToolRegistry(listOf(
            WriteFile(), EditFile(), ReadFile(), ListFiles(), RunCode(), RunExperiment(), CompareExperiments(),
            WebSearch(), ReadUrl(), RecordFinding(), Note(), Recall(), AddTask(), CompleteTask(), FailTask(),
            MarkCriterion(), FinishProject(),
        ))
    }
}

private fun metricsFrom(output: String): Map<String, Double> {
    val m = LinkedHashMap<String, Double>()
    Regex("""(?m)^\s*METRIC\s+([A-Za-z0-9_.\-]+)\s*[=:]\s*(-?[0-9][0-9.eE+\-]*)""").findAll(output).forEach {
        it.groupValues[2].toDoubleOrNull()?.takeIf { d -> d.isFinite() }?.let { d -> m[it.groupValues[1]] = d }
    }
    return m
}

private fun formatRun(r: CodeResult, maxChars: Int = 1800): String = buildString {
    append(if (r.timedOut) "TIMEOUT" else if (r.ok) "OK" else "FAILED")
    append(" (${r.durationMs} ms)\n")
    if (r.stdout.isNotBlank()) append("stdout:\n").append(Text.clip(r.stdout.trim(), maxChars)).append('\n')
    if (r.stderr.isNotBlank()) append("stderr:\n").append(Text.clip(r.stderr.trim().lines().takeLast(25).joinToString("\n"), 1200)).append('\n')
    if (r.writtenFiles.isNotEmpty()) append("wrote: ").append(r.writtenFiles.keys.joinToString()).append('\n')
}

private suspend fun execCode(args: Map<String, String>, ctx: ToolContext): Triple<CodeResult, String, String>? {
    val runner = ctx.runner ?: return null
    val file = args["file"]?.takeIf { it.isNotBlank() }
    val code = args["code"]?.takeIf { it.isNotBlank() } ?: file?.let { ctx.workspace.read(it) } ?: throw AgentTool.MissingArg("code")
    val lang = (args["language"] ?: args["lang"] ?: file?.substringAfterLast('.', "") ?: "")
        .lowercase().let { when (it) { "py", "python3" -> "python"; "javascript", "node", "mjs" -> "js"; "" -> runner.languages.first(); else -> it } }
    val timeout = (args["timeout"]?.toLongOrNull()?.times(1000) ?: ctx.codeTimeoutMs).coerceIn(1000, 120_000)
    val r = runner.run(lang, code, ctx.workspace, timeout)
    for ((path, content) in r.writtenFiles) {
        runCatching { ctx.workspace.write(path, content); ctx.registerArtifact(path, "written by code") }
    }
    return Triple(r, code, lang)
}

class WriteFile : AgentTool("write_file", "path, content(block) — create/overwrite a workspace file; description optional") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val path = args.need("path", "file", "filename")
        val content = args["content"] ?: args["code"] ?: throw MissingArg("content")
        if (content.isBlank()) return err("content is empty")
        val prev = runCatching { ctx.workspace.read(path) }.getOrNull()
        ctx.workspace.write(path, content)
        val (id, novel) = ctx.registerArtifact(path, args["description"].orEmpty())
        val nearDup = prev != null && (prev == content || Text.similarity(prev, content) > 0.92)
        val effect = if (!novel || nearDup) Effect.WRITE_DUP else Effect.WRITE_NEW
        val note = if (effect == Effect.WRITE_DUP) " (nearly identical to existing content — not progress)" else ""
        return ToolResult(true, "wrote $path (${content.length} chars) as artifact $id$note", effect, Text.normalizedHash(content), "wrote $path")
    }
}

class EditFile : AgentTool("edit_file", "path, find, replace — replace exact text in a file") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val path = args.need("path", "file")
        val find = args.need("find", "old")
        val replace = args["replace"] ?: args["new"] ?: ""
        val text = ctx.workspace.read(path)
        val n = text.split(find).size - 1
        if (n == 0) return err("text to find not present in $path")
        val updated = text.replace(find, replace)
        if (updated == text) return err("edit changes nothing", Effect.WRITE_DUP)
        ctx.workspace.write(path, updated)
        val (id, _) = ctx.registerArtifact(path, "")
        return ToolResult(true, "edited $path ($n replacement${if (n > 1) "s" else ""}), artifact $id", Effect.WRITE_NEW, Text.normalizedHash(updated), "edited $path")
    }
}

class ReadFile : AgentTool("read_file", "path, offset(optional chars) — read a workspace file") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val path = args.need("path", "file")
        val text = ctx.workspace.read(path)
        val off = args["offset"]?.toIntOrNull()?.coerceIn(0, text.length) ?: 0
        val chunk = text.substring(off).take(2500)
        val more = if (off + chunk.length < text.length) "\n…[${text.length - off - chunk.length} more chars; use offset=${off + chunk.length}]" else ""
        return ToolResult(true, "$path:\n$chunk$more", Effect.READ, "read:$path:$off:${Text.normalizedHash(text)}")
    }
}

class ListFiles : AgentTool("list_files", "(no args) — list workspace files") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val files = ctx.workspace.list()
        val out = if (files.isEmpty()) "(workspace is empty)" else files.joinToString("\n") { "${it.first} (${it.second} B)" }
        return ToolResult(true, Text.clip(out, 2000), Effect.READ, "ls:" + Text.normalizedHash(out))
    }
}

class RunCode : AgentTool("run_code", "language(js|python), code(block) or file — run code in the sandbox; print results. Lines 'METRIC name=value' are captured", "code") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val (r, code, lang) = execCode(args, ctx) ?: return err("no code runtime available on this device")
        val key = "run:" + Text.normalizedHash(lang + code) + ":" + Text.normalizedHash(r.stdout)
        val repeat = ctx.project.watchdog.recentContentHashes.contains(key)
        val effect = when { !r.ok -> Effect.CODE_FAIL; repeat -> Effect.CODE_REPEAT; else -> Effect.CODE_RUN }
        return ToolResult(r.ok, formatRun(r), effect, key, if (r.ok) "ran code successfully" else "")
    }
}

class RunExperiment : AgentTool(
    "run_experiment",
    "name, hypothesis, language, code(block) or file — run and RECORD an experiment; code must print 'METRIC name=value' lines; becomes evidence id eN",
    "code",
) {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val name = args["name"]?.takeIf { it.isNotBlank() } ?: "experiment"
        val (r, code, lang) = execCode(args, ctx) ?: return err("no code runtime available on this device")
        val metrics = metricsFrom(r.stdout)
        val codeHash = Text.normalizedHash(lang + code)
        val same = ctx.project.experiments.firstOrNull { it.codeHash == codeHash && it.metrics == metrics && it.succeeded }
        val succeeded = r.ok && metrics.isNotEmpty()
        val codePath = args["file"]?.takeIf { it.isNotBlank() } ?: run {
            val p = "experiments/${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(40)}_${ctx.step}.$lang".replace(".python", ".py")
            runCatching { ctx.workspace.write(p, code); ctx.registerArtifact(p, "experiment code: $name") }
            p
        }
        val (p2, id) = ctx.project.nextId("e")
        ctx.project = p2.copy(experiments = p2.experiments + Experiment(id, ctx.taskId, name.take(80), args["hypothesis"].orEmpty().take(300),
            codeHash, codePath, metrics, Text.clip(r.stdout.trim(), 600), succeeded, p2.stepCount))
        val effect = when {
            !r.ok -> Effect.CODE_FAIL
            same != null -> Effect.EXPERIMENT_REPEAT
            !succeeded -> Effect.CODE_FAIL
            else -> Effect.EXPERIMENT
        }
        val msg = buildString {
            append("experiment $id ").append(if (succeeded) "recorded" else "FAILED")
            if (metrics.isNotEmpty()) append(": ").append(metrics.entries.joinToString { "${it.key}=${it.value}" })
            if (r.ok && metrics.isEmpty()) append(" (no METRIC lines printed — print e.g. 'METRIC ratio=0.42')")
            if (same != null) append(" — identical to ${same.id}, not new evidence")
            append('\n').append(formatRun(r, 1200))
        }
        return ToolResult(succeeded, msg, effect, "exp:$codeHash:${metrics.hashCode()}", if (effect == Effect.EXPERIMENT) "experiment $id: ${metrics.entries.take(3).joinToString { "${it.key}=${it.value}" }}" else "")
    }
}

class CompareExperiments : AgentTool("compare_experiments", "metric, goal(min|max), ids(optional, comma-separated) — rank recorded experiments by a metric") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val metric = args.need("metric")
        val goal = args["goal"]?.lowercase()?.let { if (it.startsWith("max") || it == "higher") "max" else "min" } ?: "min"
        val ids = args["ids"]?.split(Regex("[,\\s]+"))?.filter { it.isNotBlank() }?.toSet()
        val rows = ctx.project.experiments.filter { it.succeeded && metric in it.metrics && (ids == null || it.id in ids) }
        if (rows.isEmpty()) return err("no successful experiments with metric '$metric'", Effect.READ)
        val sorted = rows.sortedBy { if (goal == "min") it.metrics[metric]!! else -it.metrics[metric]!! }
        val best = sorted.first()
        val out = buildString {
            append("ranking by $metric ($goal is better):\n")
            sorted.take(12).forEachIndexed { i, e -> append("${i + 1}. ${e.id} ${e.name}: ${e.metrics[metric]}\n") }
            append("best: ${best.id} ${best.name}")
            if (sorted.size > 1) {
                val second = sorted[1].metrics[metric]!!; val b = best.metrics[metric]!!
                if (second != 0.0) append(" (${"%.2f".format(kotlin.math.abs(b - second) / kotlin.math.abs(second) * 100)}% vs runner-up)")
            }
        }
        return ToolResult(true, out, Effect.READ, "cmp:$metric:${sorted.joinToString { it.id }}")
    }
}

class WebSearch : AgentTool("web_search", "query — search the web") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val web = ctx.web ?: return err("web access is disabled")
        val q = args.need("query", "q")
        val hits = web.search(q)
        if (hits.isEmpty()) return ToolResult(true, "no results for '$q'", Effect.SEARCH, "search:" + q.lowercase().trim())
        val out = hits.take(6).joinToString("\n") { "- ${it.title} <${it.url}> ${Text.oneLine(it.snippet, 160)}" }
        return ToolResult(true, out, Effect.SEARCH, "search:" + q.lowercase().trim())
    }
}

class ReadUrl : AgentTool("read_url", "url — fetch a web page as text (saved under research/)") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val web = ctx.web ?: return err("web access is disabled")
        val url = args.need("url")
        val cachedPath = "research/" + Text.sha256(url).take(12) + ".md"
        val text = if (ctx.workspace.exists(cachedPath)) ctx.workspace.read(cachedPath) else {
            val t = web.fetch(url)
            ctx.workspace.write(cachedPath, "<!-- $url -->\n" + t.take(200_000))
            ctx.registerArtifact(cachedPath, "web page: $url")
            ctx.addMemory(MemoryKind.NOTE, "Read $url (saved as $cachedPath): ${Text.oneLine(t, 200)}", 2)
            t
        }
        return ToolResult(true, "saved to $cachedPath\n" + Text.clip(text, 2500), Effect.READ, "url:$url")
    }
}

class RecordFinding : AgentTool("record_finding", "text, evidence(ids like e3,a2,s40), importance(1-5), discovery(yes|no) — save a verified finding to long-term memory") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val text = args.need("text", "finding", "content")
        val checker = ctx.evidence()
        val refs = checker.parseRefs(args["evidence"].orEmpty())
        val ev = checker.check(refs)
        val discovery = args["discovery"]?.lowercase()?.startsWith("y") == true || args["discovery"] == "true"
        if (discovery && ev.valid.isEmpty()) {
            return err("a discovery needs valid evidence. " + ev.invalid.joinToString { "${it.first}: ${it.second}" }.ifEmpty { "Provide evidence ids." }, Effect.REJECTED)
        }
        val dup = ctx.project.memory.firstOrNull { (it.kind == MemoryKind.FINDING || it.kind == MemoryKind.DISCOVERY) && Text.similarity(it.text, text) > 0.75 }
        if (dup != null) return ToolResult(true, "already recorded as ${dup.id}; not saved again", Effect.FINDING_DUP, "finding:" + Text.normalizedHash(text))
        val importance = args["importance"]?.toIntOrNull() ?: if (discovery) 5 else 3
        val kind = if (discovery) MemoryKind.DISCOVERY else MemoryKind.FINDING
        val id = ctx.addMemory(kind, text, if (ev.valid.isEmpty()) minOf(importance, 2) else importance, ev.valid)
        val warn = if (ev.invalid.isNotEmpty()) " (ignored invalid evidence: ${ev.invalid.joinToString { it.first }})" else ""
        val effect = if (ev.valid.isNotEmpty()) Effect.FINDING else Effect.NOTE
        return ToolResult(true, "saved ${kind.name.lowercase()} $id$warn", effect, "finding:" + Text.normalizedHash(text), if (effect == Effect.FINDING) Text.oneLine(text, 100) else "")
    }
}

class Note : AgentTool("note", "text — scratch note to memory (not evidence)") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val text = args.need("text", "content", "note")
        val id = ctx.addMemory(MemoryKind.NOTE, text, 1)
        return ToolResult(true, "noted $id", Effect.NOTE, "note:" + Text.normalizedHash(text))
    }
}

class Recall : AgentTool("recall", "query — search this project's long-term memory, including archived entries") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val q = args.need("query", "q", "text")
        val hits = MemoryRetriever.search(ctx.project, q, 8, includeArchived = true)
        val exps = ctx.project.experiments.filter { e -> Text.tokens(q).any { t -> e.name.lowercase().contains(t) || e.hypothesis.lowercase().contains(t) } }.takeLast(5)
        val out = buildString {
            if (hits.isEmpty() && exps.isEmpty()) append("nothing found")
            hits.forEach { append("[${it.id} ${it.kind.name.lowercase()}] ${Text.oneLine(it.text, 220)}\n") }
            exps.forEach { e -> append("[${e.id} experiment] ${e.name} ${if (e.succeeded) "ok" else "failed"} ${e.metrics}\n") }
        }
        return ToolResult(true, out.trim(), Effect.READ, "recall:" + q.lowercase().trim())
    }
}

class AddTask : AgentTool("add_task", "title, description, priority(1-5), depends_on(task ids) — add a subtask") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val title = args.need("title", "task")
        val open = ctx.project.tasks.filter { !it.isTerminal }
        if (open.size >= 40) return err("too many open tasks (${open.size}); finish or fail some first", Effect.REJECTED)
        open.firstOrNull { Text.similarity(it.title, title) > 0.7 }?.let { return err("similar task already open: ${it.id} ${it.title}", Effect.REJECTED) }
        val deps = args["depends_on"]?.split(Regex("[,\\s]+"))?.filter { it.isNotBlank() } ?: emptyList()
        val gen = ctx.project.strategyHistory.size
        val (p, id) = TaskScheduler.add(ctx.project, title, args["description"].orEmpty(), args["priority"]?.toIntOrNull() ?: 3, deps, gen)
            ?: return err("invalid dependencies (unknown task or cycle): ${deps.joinToString()}", Effect.REJECTED)
        ctx.project = p
        return ToolResult(true, "added task $id", Effect.TASK_ADDED, "task:" + Text.normalizedHash(title))
    }
}

class CompleteTask : AgentTool("complete_task", "summary, evidence(ids) — finish the CURRENT task; evidence is required and checked") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val summary = args.need("summary", "result", "text")
        val checker = ctx.evidence()
        val r = checker.check(checker.parseRefs(args["evidence"].orEmpty()))
        if (r.valid.isEmpty()) {
            val why = r.invalid.joinToString { "${it.first}: ${it.second}" }.ifEmpty { "no evidence given" }
            return err("cannot complete ${ctx.taskId} without valid evidence ($why). Produce a file, experiment or successful run first and cite it.", Effect.REJECTED)
        }
        ctx.project = TaskScheduler.complete(ctx.project, ctx.taskId, summary.take(500), r.valid)
        ctx.addMemory(MemoryKind.FINDING, "Task ${ctx.taskId} done: ${summary.take(300)}", 3, r.valid)
        return ToolResult(true, "task ${ctx.taskId} completed with evidence ${r.valid.joinToString()}", Effect.TASK_DONE, "done:${ctx.taskId}", "completed ${ctx.taskId}: ${Text.oneLine(summary, 80)}")
    }
}

class FailTask : AgentTool("fail_task", "reason — give up on the CURRENT approach for this task (it may be retried differently)") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val reason = args.need("reason", "text")
        val t = ctx.project.task(ctx.taskId)
        ctx.addMemory(MemoryKind.FAILURE, "Task ${ctx.taskId} (${t?.title}) failed: ${reason.take(300)}", 3)
        ctx.project = TaskScheduler.fail(ctx.project, ctx.taskId, reason.take(300))
        return ToolResult(true, "recorded failure of ${ctx.taskId}", Effect.TASK_FAILED, "fail:${ctx.taskId}:${Text.normalizedHash(reason)}")
    }
}

class MarkCriterion : AgentTool("mark_criterion", "id(c1..), evidence(ids) — mark a completion criterion as met") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val id = args.need("id", "criterion")
        val c = ctx.project.criteria.firstOrNull { it.id == id } ?: return err("no criterion $id; criteria: ${ctx.project.criteria.joinToString { it.id }}", Effect.REJECTED)
        val checker = ctx.evidence()
        val r = checker.check(checker.parseRefs(args["evidence"].orEmpty()))
        if (r.valid.isEmpty()) return err("criterion $id needs valid evidence (${r.invalid.joinToString { "${it.first}: ${it.second}" }.ifEmpty { "none given" }})", Effect.REJECTED)
        if (c.satisfied && c.evidence.toSet() == r.valid.toSet()) return ToolResult(true, "criterion $id already satisfied", Effect.FINDING_DUP, "crit:$id")
        ctx.project = ctx.project.copy(criteria = ctx.project.criteria.map { if (it.id == id) it.copy(satisfied = true, evidence = r.valid, note = args["note"].orEmpty()) else it })
        return ToolResult(true, "criterion $id satisfied", Effect.CRITERION, "crit:$id:${r.valid.joinToString()}", "criterion met: ${Text.oneLine(c.text, 80)}")
    }
}

class FinishProject : AgentTool("finish_project", "summary — declare the whole project done; verified against all criteria") {
    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        val summary = args.need("summary", "text")
        val v = CompletionVerifier.verify(ctx.project, ctx.workspace, ctx.stepOk, summary)
        if (!v.passed) return err("not complete:\n" + v.problems.joinToString("\n") { "- $it" }, Effect.REJECTED)
        ctx.project = ctx.project.copy(status = ProjectStatus.COMPLETED, finalReport = v.report)
        runCatching { ctx.workspace.write("REPORT.md", v.report) }
        return ToolResult(true, "project verified complete; report saved to REPORT.md", Effect.PROJECT_DONE, "finish", "project completed")
    }
}

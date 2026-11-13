package com.druk.agent

import com.druk.agent.engine.*
import com.druk.agent.llm.LlmBackend
import com.druk.agent.llm.LlmRequest
import com.druk.agent.llm.LlmUnavailableException
import com.druk.agent.memory.MemoryConsolidator
import com.druk.agent.model.*
import com.druk.agent.store.ProjectStore
import com.druk.agent.tools.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LongHorizonTest {

    /**
     * Honest behaviour for a genuinely long project: the codec only reaches
     * the target after >100 measured improvement iterations, each its own
     * task (plan → experiment → complete), so a project needs hundreds of
     * steps. State the model "remembers" lives in the prompt, as on device.
     */
    class IterativeHonest {
        private val iterations = HashMap<String, Int>()
        private var planSeq = 0
        fun reply(r: LlmRequest): String {
            val p = r.prompt
            val proj = Regex("""\[(\w+)]""").find(p)?.groupValues?.get(1) ?: "?"
            return when (r.purpose) {
                "plan" -> "STRATEGY: iterate on the codec, one measured change per task\n" + (1..3).joinToString("\n") {
                    "TASK: Iteration ${++planSeq} tune the model | one change, measure ratio | 3 | none"
                }
                "reflect" -> "WHY_FAILED: wasted steps\nNEW_APPROACH: one change per iteration, measured, attempt ${planSeq}"
                else -> {
                    val title = p.substringAfter("## CURRENT TASK").lines().getOrElse(1) { "" }
                    val iter = Regex("""Iteration (\d+)""").find(title)?.groupValues?.get(1) ?: return "ACTION: list_files"
                    val exp = Regex("""(?m)^(e\d+) iter-$iter: ratio=([0-9.]+)""").find(p.substringAfter("## EXPERIMENTS", ""))
                    val crit = Regex("""(?m)^(c\w+) \[ ]""").find(p)?.groupValues?.get(1)
                    when {
                        exp == null -> {
                            val n = (iterations[proj] ?: 0) + 1
                            iterations[proj] = n
                            "THOUGHT: iteration $iter\nACTION: run_experiment\nname: iter-$iter\ncode:\n```\nmetric ratio ${"%.4f".format(0.9 - 0.003 * n)}\nprint variant $iter\n```"
                        }
                        exp.groupValues[2].toDouble() < 0.5 && crit != null -> "ACTION: mark_criterion\nid: $crit\nevidence: ${exp.groupValues[1]}"
                        else -> "ACTION: complete_task\nsummary: ratio ${exp.groupValues[2]}\nevidence: ${exp.groupValues[1]}"
                    }
                }
            }
        }
    }

    /**
     * A small model on a bad day: honest only part of the time; otherwise
     * garbage, busywork, repeated actions, failing code, premature success
     * claims and unsupported "discoveries". Shared across projects, like the
     * single on-device model.
     */
    class ChaoticModel(private val rnd: Random, private val honestShare: Double) : LlmBackend {
        val requests = mutableListOf<LlmRequest>()
        var unavailable = false
        private val honest = IterativeHonest()
        override suspend fun complete(request: LlmRequest): String {
            if (unavailable) throw LlmUnavailableException("model unloaded (simulated)")
            requests += request
            if (request.purpose == "summarize") return if (rnd.nextBoolean()) "Consolidated: several experiments with ratios; failures were format errors and repeats." else "?"
            if (rnd.nextDouble() < honestShare) return honest.reply(request)
            return when (rnd.nextInt(8)) {
                0 -> "I believe the best approach is to think carefully about entropy coding."
                1 -> "THOUGHT: check\nACTION: list_files"
                2 -> "ACTION: run_code\ncode:\n```\nfail\n```"
                3 -> "ACTION: finish_project\nsummary: Breakthrough achieved!"
                4 -> "ACTION: record_finding\ntext: My algorithm beats zstd by 90%\ndiscovery: yes"
                5 -> "ACTION: complete_task\nsummary: done\nevidence: e99999"
                6 -> "ACTION: note\ntext: note number ${rnd.nextInt(1_000_000)} about step ${requests.size}"
                else -> if (request.purpose == "plan") "STRATEGY: random ${rnd.nextInt(1000)}\nTASK: random idea ${rnd.nextInt(1_000_000)} | x | ${rnd.nextInt(1, 6)} | none" else "ACTION: read_file\npath: nope_${rnd.nextInt(3)}.txt"
            }
        }
    }

    private data class Snapshot(val stepCount: Long, val done: Set<String>)

    @Test fun thousandsOfTransitionsWithCrashesUnloadsAndInvariants() {
        val rnd = Random(20261112)
        val dir = tempDir("long")
        val clock = Clock()
        val model = ChaoticModel(rnd, honestShare = 0.6)
        val budget = 7000
        val cfg = EngineConfig(contextBudgetChars = budget, maxLlmFailures = 1000)
        val faultPoints = listOf("before-tool", "after-tool", "before-commit", "after-commit")
        var crashArmed: String? = null
        val faults = FaultInjector { p -> if (p == crashArmed) { crashArmed = null; throw SimulatedCrash(p) } }

        fun build(): Pair<ProjectStore, AgentEngine> {
            val s = ProjectStore(dir, clock.fn)
            return s to AgentEngine(s, model, runner = FakeRunner(), web = FakeWeb(), config = cfg, clock = clock.fn, faults = faults)
        }

        var (store, engine) = build()
        var control = ProjectControl(store, clock.fn)
        val ids = listOf("ALPHA", "BRAVO", "CHARLIE").map { store.create(it, "Project $it: build a codec with ratio below 0.5 [$it]", listOf("codec with ratio < 0.5")).id }
        val tag = ids.zip(listOf("ALPHA", "BRAVO", "CHARLIE")).toMap()
        // One project is paused by the user for the first half and must not move.
        control.pause(ids[2])

        val seen = HashMap<String, Snapshot>()
        var crashes = 0; var unloads = 0; var transitions = 0
        val totalAttempts = 3000
        for (attempt in 0 until totalAttempts) {
            if (attempt == totalAttempts / 2) control.resume(ids[2])
            // Model unload windows.
            if (!model.unavailable && rnd.nextDouble() < 0.01) { model.unavailable = true; unloads++ }
            else if (model.unavailable && rnd.nextDouble() < 0.3) model.unavailable = false
            if (rnd.nextDouble() < 0.02) crashArmed = faultPoints[rnd.nextInt(faultPoints.size)]

            // Simulated user: resumes projects the watchdog paused for review, after a while.
            if (attempt % 97 == 0) for (pid in ids) {
                val p = store.load(pid)!!
                if (p.status == ProjectStatus.PAUSED && p.openBlockers().any { it.needsUser } && !(pid == ids[2] && attempt < totalAttempts / 2)) control.resume(pid)
            }
            val id = ids[attempt % ids.size]
            try {
                val r = runBlocking { engine.step(id) }
                if (!r.idle) transitions++
                if (r.kind == "backoff" || r.kind == "llm-unavailable") clock.advance(60 * 60_000)
            } catch (e: SimulatedCrash) {
                crashes++
                crashArmed = null
                val fresh = build(); store = fresh.first; engine = fresh.second; control = ProjectControl(store, clock.fn)
                ids.forEach { engine.recover(it) }
            }
            clock.advance(1000)

            // ---- invariants after every attempt ----
            for (pid in ids) {
                val p = store.load(pid)!!
                val prev = seen[pid]
                if (prev != null) {
                    assertTrue("step count never goes back ($pid)", p.stepCount >= prev.stepCount)
                    val doneNow = p.tasks.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
                    assertTrue("done tasks never revert ($pid)", doneNow.containsAll(prev.done))
                }
                if (pid == ids[2] && attempt < totalAttempts / 2) assertEquals("paused project must not step", 0L, p.stepCount)
                for (list in listOf(p.tasks.map { it.id }, p.experiments.map { it.id }, p.memory.map { it.id }, p.artifacts.map { it.id }, p.blockers.map { it.id })) {
                    assertEquals("ids unique ($pid)", list.size, list.toSet().size)
                }
                assertTrue("recent steps bounded", p.recentSteps.size <= cfg.recentStepsKept)
                assertTrue("active memory bounded: ${MemoryConsolidator.candidates(p).size}", MemoryConsolidator.candidates(p).size <= MemoryConsolidator.ACTIVE_LIMIT + 2)
                assertTrue("no unsupported discoveries", p.memory.filter { it.kind == MemoryKind.DISCOVERY }.all { it.evidence.isNotEmpty() })
                assertTrue("done tasks have evidence", p.tasks.filter { it.status == TaskStatus.DONE }.all { it.evidence.isNotEmpty() })
                if (p.status == ProjectStatus.COMPLETED) {
                    assertTrue("completion only with all criteria", p.criteria.all { it.satisfied })
                    assertTrue(p.finalReport.contains("verified complete"))
                }
                seen[pid] = Snapshot(p.stepCount, p.tasks.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet())
            }
        }

        // Prompts stayed within budget the whole time, and never mixed projects.
        for (req in model.requests) {
            val limit = if (req.purpose == "act-retry") budget + 900 else budget
            assertTrue("prompt ${req.purpose} ${req.prompt.length} > $limit", req.prompt.length <= limit)
            val mentioned = tag.values.count { req.prompt.contains("[$it]") }
            assertTrue("prompt mixes projects", mentioned <= 1)
        }
        // Every project's log is contiguous and duplicate-free despite the kills.
        for (pid in ids) {
            val p = store.load(pid)!!
            assertEquals(pid, (1..p.stepCount).toList(), store.readSteps(pid, 1_000_000).map { it.step })
            assertNull(store.readInFlight(pid))
            assertNotEquals("project ended up in a sane state", ProjectStatus.STOPPED, p.status)
            // A completed project's evidence is still valid on disk.
            if (p.status == ProjectStatus.COMPLETED) {
                val ws = Workspace(store.workspaceDir(pid))
                assertTrue(ws.exists("REPORT.md"))
            }
        }
        val states = ids.map { store.load(it)!! }
        val summary = states.joinToString("\n") { p ->
            "${p.title}: ${p.status} steps=${p.stepCount} tasks=${p.tasks.size} done=${p.tasks.count { it.status == TaskStatus.DONE }} " +
                "exps=${p.experiments.size} mem=${p.memory.size} (archived ${p.memory.count { it.archived }}) strategies=${p.strategyHistory.size} " +
                "blockers=${p.openBlockers().size} stateKB=${File(store.dir(p.id), "state.json").length() / 1024}"
        }
        println("long-horizon: attempts=$totalAttempts transitions=$transitions crashes=$crashes unloads=$unloads llmCalls=${model.requests.size}\n$summary")
        assertTrue("simulated many crashes", crashes >= 20)
        assertTrue("over a thousand committed transitions", transitions >= 1000)
        // With a 60%-honest model, at least one project should still get verified done.
        assertTrue(summary, states.any { it.status == ProjectStatus.COMPLETED })
    }

    @Test fun contextStaysBoundedForHugeProjects() {
        val store = newStore()
        var p = store.create("big", "O".repeat(20_000), (1..40).map { "criterion $it ".repeat(20) })
        p = p.copy(
            instructions = (1..20).map { "instruction $it ".repeat(100) },
            tasks = (1..300).map { Task("t$it", "task $it ".repeat(30), "desc ".repeat(200), status = if (it % 3 == 0) TaskStatus.DONE else TaskStatus.PENDING, result = "r".repeat(500)) },
            experiments = (1..3000).map { Experiment("e$it", "t1", "exp $it", metrics = mapOf("ratio" to it / 3000.0, "ms" to it.toDouble()), succeeded = true) },
            memory = (1..5000).map { MemoryEntry("m$it", MemoryKind.values()[it % 6], "memory entry $it about compression ".repeat(10), it % 5 + 1) },
            recentSteps = (1..40).map { StepRecord(it.toLong(), "t1", "act", "run_code", "x".repeat(500), it % 2 == 0, "output ".repeat(1000), Progress.NONE) },
            strategyHistory = (1..10).map { StrategyChange(it.toLong(), "old ".repeat(100), "new", "why ".repeat(100)) },
            summary = "S".repeat(10_000),
            watchdog = WatchdogState(warnings = listOf("w1", "w2", "w3")),
        )
        val ws = Workspace(store.workspaceDir(p.id))
        repeat(200) { ws.write("f$it.txt", "x") }
        val cb = ContextBuilder(7000)
        val prompt = cb.act(p, p.tasks.first { it.status == TaskStatus.PENDING }, com.druk.agent.tools.ToolRegistry.standard(), ws, p.recentSteps)
        assertTrue(prompt.length.toString(), prompt.length <= 7000)
        for (must in listOf("## OBJECTIVE", "## CURRENT TASK", "## TOOLS", "## REPLY", "## USER INSTRUCTIONS")) assertTrue(must, prompt.contains(must))
        assertTrue(cb.plan(p, ws).length <= 7000)
        assertTrue(cb.reflect(p, "why", p.recentSteps).length <= 7000)
        // Small budgets still keep the essentials.
        val tiny = ContextBuilder(2500).act(p, p.tasks[0], com.druk.agent.tools.ToolRegistry.standard(), ws, p.recentSteps)
        assertTrue(tiny.length <= 2500)
        assertTrue(tiny.contains("## CURRENT TASK"))
    }
}

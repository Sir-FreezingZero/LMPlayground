package com.druk.agent

import com.druk.agent.engine.*
import com.druk.agent.llm.LlmRequest
import com.druk.agent.model.*
import com.druk.agent.store.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A competent-but-small simulated model for a compression-style project. */
fun honestModel(): ScriptedLlm = ScriptedLlm { r, _ -> honestReply(r) }

fun honestReply(r: LlmRequest): String {
    val p = r.prompt
    fun ids(prefix: String, sectionTitle: String): List<String> {
        val sec = p.substringAfter("## $sectionTitle", "").substringBefore("\n## ")
        return Regex("""(?m)^$prefix\d+""").findAll(sec).map { it.value }.toList()
    }
    return when (r.purpose) {
        "plan" -> "STRATEGY: measure a baseline, then improve on it\nTASK: Baseline codec | implement RLE and measure ratio | 5 | none\nTASK: Improved codec | try delta+RLE and measure | 4 | 1"
        "reflect" -> "WHY_FAILED: repeated the same thing\nNEW_APPROACH: write a smaller, different experiment"
        "summarize" -> "Summary: experiments ran; ratios recorded for baseline and improved codecs. Files in experiments/."
        else -> {
            val task = Regex("""## CURRENT TASK (t\d+)""").find(p)!!.groupValues[1]
            val title = p.substringAfter("## CURRENT TASK").lines()[1]
            val exps = Regex("""(?m)^(e\d+) (\S+): ratio=([0-9.]+)""").findAll(p.substringAfter("## EXPERIMENTS", "")).map { it.groupValues }.toList()
            val mine = exps.firstOrNull { it[2] == (if (title.startsWith("Baseline")) "baseline" else "improved") }
            val crit = Regex("""(?m)^(c\w+) \[ \]""").find(p)?.groupValues?.get(1)
            when {
                mine == null && title.startsWith("Baseline") -> "THOUGHT: measure baseline\nACTION: run_experiment\nname: baseline\ncode:\n```\nmetric ratio 0.61\n```"
                mine == null -> "THOUGHT: measure improved\nACTION: run_experiment\nname: improved\ncode:\n```\nmetric ratio 0.47\n```"
                title.startsWith("Improved") && crit != null -> "THOUGHT: criterion met\nACTION: mark_criterion\nid: $crit\nevidence: ${mine[1]}"
                else -> "THOUGHT: done\nACTION: complete_task\nsummary: ratio ${mine[3]}\nevidence: ${mine[1]}"
            }
        }
    }
}

class EngineTest {

    private fun engine(store: ProjectStore, llm: ScriptedLlm, clock: Clock = Clock(), faults: FaultInjector = FaultInjector.NONE, cfg: EngineConfig = EngineConfig()) =
        AgentEngine(store, llm, runner = FakeRunner(), web = FakeWeb(), config = cfg, clock = clock.fn, faults = faults)

    @Test fun endToEndProjectReachesVerifiedCompletion() = runBlocking {
        val clock = Clock()
        val store = ProjectStore(tempDir(), clock.fn)
        val p = store.create("codec", "Find a codec with ratio below 0.5 on the corpus", listOf("A codec with measured ratio < 0.5"))
        val eng = engine(store, honestModel(), clock)
        var final: Project? = null
        for (i in 0 until 30) {
            val r = eng.step(p.id)
            final = store.load(p.id)
            if (final!!.status == ProjectStatus.COMPLETED) break
            assertFalse("unexpected idle: $r", r.idle)
        }
        final!!
        assertEquals(ProjectStatus.COMPLETED, final.status)
        assertTrue(final.finalReport.contains("verified complete"))
        assertTrue(store.workspaceDir(p.id).resolve("REPORT.md").isFile)
        assertEquals(2, final.experiments.count { it.succeeded })
        assertTrue(final.criteria.all { it.satisfied })
        // Every step is in the log exactly once, in order.
        val steps = store.readSteps(p.id).map { it.step }
        assertEquals((1..final.stepCount).toList(), steps)
        // Completed projects are not stepped again.
        assertTrue(eng.step(p.id).idle)
    }

    @Test fun pauseDuringInferenceWinsOverStepCommit() = runBlocking {
        val store = newStore()
        val p = store.create("x", "objective", emptyList())
        val control = ProjectControl(store)
        val llm = ScriptedLlm { r, _ ->
            if (r.purpose == "act") control.pause(p.id) // user taps Pause while the model is generating
            honestReply(r)
        }
        val eng = engine(store, llm)
        eng.step(p.id) // plan
        val rep = eng.step(p.id) // act; paused mid-step
        assertFalse(rep.idle)
        val after = store.load(p.id)!!
        assertEquals(ProjectStatus.PAUSED, after.status)
        assertEquals(1, after.experiments.size) // the step's result was still kept
        assertTrue(eng.step(p.id).idle) // and nothing more happens until resume
        control.resume(p.id)
        assertFalse(eng.step(p.id).idle)
    }

    @Test fun userInstructionsAndObjectiveChangesSurviveConcurrentStep() = runBlocking {
        val store = newStore()
        val p = store.create("x", "old objective", emptyList())
        val control = ProjectControl(store)
        val llm = ScriptedLlm { r, _ ->
            if (r.purpose == "act") { control.addInstruction(p.id, "use python only"); control.changeObjective(p.id, "new objective") }
            honestReply(r)
        }
        val eng = engine(store, llm)
        eng.step(p.id); eng.step(p.id)
        val after = store.load(p.id)!!
        assertEquals("new objective", after.objective)
        assertEquals(listOf("use python only"), after.instructions)
        assertTrue(after.needsPlanning)
        // The next prompt shows the instruction.
        eng.step(p.id)
        assertTrue(llm.requests.last().prompt.contains("use python only"))
    }

    @Test fun modelUnavailableBacksOffThenPausesWithoutLooping() = runBlocking {
        val clock = Clock()
        val store = ProjectStore(tempDir(), clock.fn)
        val p = store.create("x", "o", emptyList())
        val llm = honestModel().apply { unavailable = true }
        val eng = engine(store, llm, clock, cfg = EngineConfig(maxLlmFailures = 4))
        var attempts = 0
        while (store.load(p.id)!!.status == ProjectStatus.ACTIVE && attempts < 100) {
            val r = eng.step(p.id)
            assertTrue(r.idle)
            if (r.kind == "backoff") clock.advance(60 * 60_000) else attempts++
        }
        assertEquals(4, attempts)
        val after = store.load(p.id)!!
        assertEquals(ProjectStatus.PAUSED, after.status)
        assertTrue(after.openBlockers().single().text.contains("Model failed"))
        assertEquals(0L, after.stepCount) // nothing was fabricated while the model was down
        // Model comes back; explicit resume works.
        llm.unavailable = false
        ProjectControl(store, clock.fn).resume(p.id)
        assertFalse(eng.step(p.id).idle)
    }

    @Test fun invalidRepliesAreRetriedOnceThenCountedAsNoProgress() = runBlocking {
        val store = newStore()
        val p = store.create("x", "o", emptyList())
        val llm = ScriptedLlm { r, _ -> if (r.purpose == "plan") "STRATEGY: s\nTASK: thing | do | 3 | none" else "I will now think about compression in general terms." }
        val eng = engine(store, llm)
        eng.step(p.id)
        val before = llm.requests.size
        val rep = eng.step(p.id)
        assertEquals(2, llm.requests.size - before) // original + one correction attempt
        assertEquals("invalid", rep.action)
        assertEquals(Progress.NONE, rep.progress)
        assertEquals(2, store.load(p.id)!!.watchdog.stepsWithoutProgress - 1) // plan(+1) then invalid(+2)
    }

    @Test fun watchdogEscalatesWarnReflectThenPauses() = runBlocking {
        val store = newStore()
        val p = store.create("x", "o", emptyList())
        var plans = 0
        val llm = ScriptedLlm { r, _ ->
            when (r.purpose) {
                "plan" -> { plans++; "STRATEGY: approach $plans\nTASK: attempt number $plans unique${plans * 7919} | do | 3 | none" }
                "reflect" -> "WHY_FAILED: loops\nNEW_APPROACH: totally different approach number ${plans * 31}"
                else -> "THOUGHT: look again\nACTION: list_files" // looks busy, achieves nothing
            }
        }
        val eng = engine(store, llm)
        var sawWarning = false
        var steps = 0
        while (store.load(p.id)!!.status == ProjectStatus.ACTIVE && steps < 400) {
            eng.step(p.id); steps++
            if (llm.requests.lastOrNull()?.prompt?.contains("## WATCHDOG") == true) sawWarning = true
        }
        val after = store.load(p.id)!!
        assertTrue("watchdog warning shown to model", sawWarning)
        assertEquals(ProjectStatus.PAUSED, after.status)
        assertEquals(Watchdog.MAX_STRATEGY_CHANGES, after.strategyHistory.size)
        assertTrue(after.openBlockers().any { it.needsUser && it.text.contains("Stalled") })
        assertTrue("bounded: took $steps steps", steps < 200)
        assertTrue(after.memory.count { it.kind == MemoryKind.FAILURE && it.text.startsWith("Strategy abandoned") } >= 3)
    }

    @Test fun taskStepBudgetFailsAttemptAndLetsOtherTasksRun() = runBlocking {
        val store = newStore()
        val p = store.create("x", "o", emptyList())
        var n = 0
        val llm = ScriptedLlm { r, _ ->
            when (r.purpose) {
                "plan" -> "STRATEGY: s\nTASK: stubborn task | x | 5 | none\nTASK: easy task | y | 1 | none"
                else -> {
                    val title = r.prompt.substringAfter("## CURRENT TASK").lines()[1]
                    n++
                    if (title.startsWith("easy")) "ACTION: run_experiment\nname: easy\ncode:\n```\nmetric v 1\n```"
                    else "ACTION: run_code\ncode:\n```\nprint $n\n```" // new output each time: looks like progress, never completes
                }
            }
        }
        val eng = engine(store, llm)
        repeat(40) { eng.step(p.id) }
        val after = store.load(p.id)!!
        val stubborn = after.tasks.first { it.title.startsWith("stubborn") }
        assertTrue(after.tasks.joinToString("\n"), stubborn.attempts >= 1)
        assertTrue(stubborn.failureReason, stubborn.failureReason.contains("step budget"))
        assertTrue("other task got a turn", after.experiments.any { it.name == "easy" })
    }

    @Test fun prematureFinishIsRejectedAndRecordedAsNoProgress() = runBlocking {
        val store = newStore()
        val p = store.create("x", "o", listOf("ratio < 0.5 measured"))
        val llm = ScriptedLlm { r, _ -> if (r.purpose == "plan") "STRATEGY: s\nTASK: measure ratio | x | 3 | none" else "ACTION: finish_project\nsummary: I invented a revolutionary algorithm!" }
        val eng = engine(store, llm)
        eng.step(p.id)
        val rep = eng.step(p.id)
        assertEquals(Progress.NONE, rep.progress)
        val after = store.load(p.id)!!
        assertEquals(ProjectStatus.ACTIVE, after.status)
        assertTrue(after.recentSteps.last().output.contains("not satisfied"))
    }

    @Test fun pausedProjectsAreNeverRunByTheRunner() = runBlocking {
        val store = newStore()
        val a = store.create("a", "alpha", emptyList())
        val b = store.create("b", "beta", emptyList())
        ProjectControl(store).pause(b.id)
        val llm = honestModel()
        val runner = AgentRunner(store, engine(store, llm))
        runner.run(maxSteps = 50)
        assertEquals(0L, store.load(b.id)!!.stepCount)
        assertTrue(llm.requests.none { it.prompt.contains("beta") })
        assertEquals(ProjectStatus.COMPLETED, store.load(a.id)!!.status)
    }

    @Test fun runnerInterleavesMultipleProjectsWithoutCrossContamination() = runBlocking {
        val store = newStore()
        val ids = (1..3).map { store.create("p$it", "objective number $it", emptyList()).id }
        val llm = honestModel()
        val runner = AgentRunner(store, engine(store, llm))
        val order = mutableListOf<String>()
        val exit = runner.run(maxSteps = 200) { order += it.projectId }
        assertEquals(AgentRunner.Exit.IDLE, exit)
        ids.forEach { assertEquals(ProjectStatus.COMPLETED, store.load(it)!!.status) }
        // Round-robin: the first three steps touch three different projects.
        assertEquals(3, order.take(3).toSet().size)
        // Each prompt mentions exactly one project's objective.
        for (req in llm.requests.filter { it.purpose == "act" || it.purpose == "plan" }) {
            assertEquals(req.prompt, 1, (1..3).count { req.prompt.contains("objective number $it") })
        }
    }

    @Test fun runnerHonoursPolicyStopAndThrottle() = runBlocking {
        val store = newStore()
        store.create("a", "o", emptyList())
        var calls = 0
        val policy = RunPolicy { calls++; if (calls == 1) RunPolicy.Decision.Throttle(10, "hot") else RunPolicy.Decision.Stop("battery low") }
        val slept = mutableListOf<Long>()
        val exit = AgentRunner(store, engine(store, honestModel()), policy, sleep = { slept += it }).run()
        assertEquals(AgentRunner.Exit.POLICY_STOP, exit)
        assertEquals(listOf(10L), slept)
    }
}

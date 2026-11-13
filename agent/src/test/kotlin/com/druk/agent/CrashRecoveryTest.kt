package com.druk.agent

import com.druk.agent.engine.*
import com.druk.agent.model.*
import com.druk.agent.store.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Process death at every interesting point of a step. "Death" is a thrown
 * [SimulatedCrash]; afterwards a brand-new store/engine (a fresh process)
 * is built over the same directory, exactly as after a real kill.
 */
class CrashRecoveryTest {

    private fun freshEngine(dir: File, clock: Clock, faults: FaultInjector = FaultInjector.NONE): Pair<ProjectStore, AgentEngine> {
        val store = ProjectStore(dir, clock.fn)
        return store to AgentEngine(store, honestModel(), runner = FakeRunner(), web = FakeWeb(), clock = clock.fn, faults = faults)
    }

    private fun crashOnce(point: String, onOccurrence: Int): FaultInjector {
        var n = 0
        var fired = false
        return FaultInjector { p -> if (!fired && p == point && ++n == onOccurrence) { fired = true; throw SimulatedCrash(p) } }
    }

    private fun runToEnd(store: ProjectStore, eng: AgentEngine, id: String, max: Int = 60) = runBlocking {
        eng.recover(id)
        repeat(max) { if (store.load(id)!!.status == ProjectStatus.ACTIVE) eng.step(id) }
        store.load(id)!!
    }

    @Test fun killAtEveryPointResumesWithoutDuplicatingWork() {
        for (point in listOf("before-tool", "after-tool", "before-commit", "after-commit")) {
            for (occurrence in 1..3) {
                val dir = tempDir("crash")
                val clock = Clock()
                val (s1, e1) = freshEngine(dir, clock, crashOnce(point, occurrence))
                val id = s1.create("c", "objective", listOf("ratio < 0.5")).id
                var crashed = false
                try { runBlocking { repeat(30) { if (s1.load(id)!!.status == ProjectStatus.ACTIVE) e1.step(id) } } } catch (e: SimulatedCrash) { crashed = true }
                assertTrue("$point/$occurrence should have crashed", crashed)
                val before = ProjectStore(dir, clock.fn).load(id)!!

                val (s2, e2) = freshEngine(dir, clock)
                val after = runToEnd(s2, e2, id)
                val tag = "$point/$occurrence"
                assertEquals(tag, ProjectStatus.COMPLETED, after.status)
                assertTrue(tag, after.stepCount >= before.stepCount)
                // No completed work duplicated: one record per distinct experiment, each task done once.
                assertEquals(tag + " " + after.experiments.map { it.name }, 1, after.experiments.count { it.name == "baseline" && it.succeeded })
                assertEquals(tag, 1, after.experiments.count { it.name == "improved" && it.succeeded })
                assertEquals(tag, after.tasks.map { it.id }.distinct().size, after.tasks.size)
                assertTrue(tag, after.tasks.filter { it.status == TaskStatus.DONE }.all { it.evidence.isNotEmpty() })
                // Step log is contiguous and duplicate-free even when the kill hit between commit and log append.
                assertEquals(tag, (1..after.stepCount).toList(), s2.readSteps(id, 10_000).map { it.step })
                assertNull(tag, s2.readInFlight(id))
                // Plan steps have no side effects and no in-flight marker (the first commit is a plan's);
                // a kill during an act step's side effects must be recorded.
                if (point == "after-tool" || point == "before-tool" || (point == "before-commit" && occurrence > 1)) {
                    assertTrue(tag, after.memory.any { it.text.contains("interrupted by process death") })
                }
            }
        }
    }

    @Test fun taskThatKeepsKillingTheProcessIsFailedNotRetriedForever() = runBlocking {
        val dir = tempDir("crashloop")
        val clock = Clock()
        val id = ProjectStore(dir, clock.fn).create("c", "o", emptyList()).id
        var restarts = 0
        while (restarts < 20) {
            val store = ProjectStore(dir, clock.fn)
            // Every tool execution on the poisoned task kills the process.
            val faults = FaultInjector { p -> if (p == "before-tool" && store.load(id)!!.tasks.any { it.status == TaskStatus.RUNNING && it.title.startsWith("Baseline") }) throw SimulatedCrash(p) }
            val eng = AgentEngine(store, honestModel(), runner = FakeRunner(), clock = clock.fn, faults = faults)
            try {
                eng.recover(id)
                repeat(10) { if (store.load(id)!!.status == ProjectStatus.ACTIVE) eng.step(id) }
                break
            } catch (e: SimulatedCrash) { restarts++ }
        }
        val p = ProjectStore(dir, clock.fn).load(id)!!
        val poisoned = p.tasks.first { it.title.startsWith("Baseline") }
        assertEquals(TaskStatus.FAILED, poisoned.status)
        assertTrue(poisoned.failureReason.contains("Interrupted"))
        assertEquals(3, restarts)
        // Its dependent is blocked with a reason, not silently stuck.
        assertEquals(TaskStatus.BLOCKED, p.tasks.first { it.title.startsWith("Improved") }.status)
    }

    @Test fun crashLoopGuardStopsRepeatedStartsWithoutProgress() {
        val clock = Clock()
        val g = CrashLoopGuard(File(tempDir(), "runner.json"), clock.fn, maxStarts = 4)
        repeat(4) { assertTrue(g.onStart()) }
        assertFalse(g.onStart())
        clock.advance(11 * 60_000)
        assertTrue("window expired", g.onStart())
        g.onHealthy()
        repeat(3) { assertTrue(g.onStart()) }
    }

    @Test fun runnerRecoversAllProjectsOnStartAndKeepsPausedOnesPaused() = runBlocking {
        val dir = tempDir()
        val clock = Clock()
        val s1 = ProjectStore(dir, clock.fn)
        val a = s1.create("a", "alpha", emptyList()).id
        val b = s1.create("b", "beta", emptyList()).id
        ProjectControl(s1).pause(b)
        // Process dies with a step of `a` in flight.
        s1.update(a) { it.copy(tasks = listOf(Task("t9", "half done", status = TaskStatus.RUNNING)), needsPlanning = false) }
        s1.markInFlight(a, 1, "t9", "run_code")

        val s2 = ProjectStore(dir, clock.fn)
        val eng = AgentEngine(s2, honestModel(), runner = FakeRunner(), clock = clock.fn)
        val runner = AgentRunner(s2, eng, clock = clock.fn)
        runner.run(guard = CrashLoopGuard(File(dir, "runner.json"), clock.fn), maxSteps = 3)
        val pa = s2.load(a)!!
        assertEquals(1, pa.task("t9")!!.interruptions)
        assertNull(s2.readInFlight(a))
        assertEquals(ProjectStatus.PAUSED, s2.load(b)!!.status)
        assertEquals(0L, s2.load(b)!!.stepCount)
    }

    @Test fun stepCancelledMidwayCommitsNothing() = runBlocking {
        val store = newStore()
        val id = store.create("a", "o", emptyList()).id
        val llm = ScriptedLlm { r, _ -> if (r.purpose == "act") throw kotlinx.coroutines.CancellationException("user stopped") else honestReply(r) }
        val eng = AgentEngine(store, llm, runner = FakeRunner())
        eng.step(id)
        val before = store.load(id)!!
        try { eng.step(id); fail("expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) {}
        val after = store.load(id)!!
        assertEquals(before.stepCount, after.stepCount)
        assertEquals(before.experiments, after.experiments)
        assertEquals(0, after.llmFailures) // cancellation is not a model failure
    }
}

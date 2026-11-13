package com.druk.agent

import com.druk.agent.model.*
import com.druk.agent.scheduler.TaskScheduler
import org.junit.Assert.*
import org.junit.Test

class SchedulerTest {

    private fun proj(vararg tasks: Task) = Project("p", "t", "o", tasks = tasks.toList(), idCounter = 100)

    @Test fun picksHighestPriorityReadyTaskThenOldest() {
        val p = proj(Task("t1", "low", priority = 1), Task("t2", "high", priority = 5, createdStep = 2), Task("t3", "high-old", priority = 5, createdStep = 1))
        assertEquals("t3", TaskScheduler.next(p)!!.id)
    }

    @Test fun dependenciesGateReadiness() {
        val p = proj(Task("t1", "a", priority = 1), Task("t2", "b", priority = 5, dependsOn = listOf("t1")))
        assertEquals("t1", TaskScheduler.next(p)!!.id)
        val done = TaskScheduler.complete(p, "t1", "ok", listOf("e1"))
        assertEquals("t2", TaskScheduler.next(done)!!.id)
    }

    @Test fun runningTaskIsResumedFirst() {
        val p = proj(Task("t1", "a", priority = 5), Task("t2", "b", priority = 1, status = TaskStatus.RUNNING))
        assertEquals("t2", TaskScheduler.next(p)!!.id)
    }

    @Test fun failureRetriesWithBackoffThenFails() {
        var p = proj(Task("t1", "a", maxAttempts = 3), Task("t2", "other"))
        p = TaskScheduler.fail(p, "t1", "boom")
        val t = p.task("t1")!!
        assertEquals(TaskStatus.PENDING, t.status)
        assertTrue(t.notBeforeStep > p.stepCount)
        // Backoff lets unrelated work run.
        assertEquals("t2", TaskScheduler.next(p)!!.id)
        p = TaskScheduler.fail(p, "t1", "boom2")
        p = TaskScheduler.fail(p, "t1", "boom3")
        assertEquals(TaskStatus.FAILED, p.task("t1")!!.status)
        assertEquals(3, p.task("t1")!!.attempts)
    }

    @Test fun failedDependencyBlocksDependentsButNotUnrelatedTasks() {
        var p = proj(Task("t1", "a", maxAttempts = 1), Task("t2", "dep", dependsOn = listOf("t1")), Task("t3", "unrelated"))
        p = TaskScheduler.fail(p, "t1", "x")
        assertEquals(TaskStatus.BLOCKED, p.task("t2")!!.status)
        assertTrue(p.task("t2")!!.blockedReason.contains("t1"))
        assertEquals("t3", TaskScheduler.next(p)!!.id)
        // User retry unblocks.
        p = TaskScheduler.retry(p, "t1")
        assertEquals(TaskStatus.PENDING, p.task("t2")!!.status)
    }

    @Test fun cyclesAndUnknownDependenciesAreRejected() {
        var p = proj(Task("t1", "a"))
        assertNull(TaskScheduler.add(p, "b", "", 3, listOf("t99"), 0))
        val (p2, id) = TaskScheduler.add(p, "b", "", 3, listOf("t1"), 0)!!
        p = TaskScheduler.mapTask(p2, "t1") { it.copy(dependsOn = listOf(id)) }
        assertTrue(TaskScheduler.hasCycle(p))
    }

    @Test fun repeatedInterruptionsFailTheTaskInsteadOfLooping() {
        var p = proj(Task("t1", "a", status = TaskStatus.RUNNING))
        p = TaskScheduler.interrupted(p, "t1")
        assertEquals(TaskStatus.PENDING, p.task("t1")!!.status)
        p = TaskScheduler.interrupted(p, "t1")
        p = TaskScheduler.interrupted(p, "t1")
        assertEquals(TaskStatus.FAILED, p.task("t1")!!.status)
    }

    @Test fun multipleDependentChainsCompleteInOrder() {
        var p = proj()
        val ids = mutableListOf<String>()
        for (i in 0 until 20) {
            val deps = if (i >= 2) listOf(ids[i - 2]) else emptyList()
            val (np, id) = TaskScheduler.add(p, "task $i", "", (i % 5) + 1, deps, 0)!!
            p = np; ids += id
        }
        val order = mutableListOf<String>()
        while (true) {
            val t = TaskScheduler.next(p) ?: break
            p = TaskScheduler.complete(TaskScheduler.start(p, t.id), t.id, "ok", listOf("x"))
            order += t.id
        }
        assertEquals(20, order.size)
        for (i in 2 until 20) assertTrue(order.indexOf(ids[i]) > order.indexOf(ids[i - 2]))
    }
}

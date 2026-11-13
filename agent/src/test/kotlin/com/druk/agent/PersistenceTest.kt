package com.druk.agent

import com.druk.agent.json.Json
import com.druk.agent.model.*
import com.druk.agent.store.ProjectStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PersistenceTest {

    @Test fun jsonRoundTripsEscapesAndNumbers() {
        val v = mapOf("s" to "q\"\\\n\t\u0001é😀", "i" to 42L, "d" to 0.5, "neg" to -3L, "b" to true, "n" to null, "l" to listOf(1L, "x", listOf<Any>()))
        assertEquals(v, Json.parse(Json.stringify(v)))
        assertEquals(1e300, Json.parse("1e300"))
        assertEquals(mapOf("d" to 2.0), Json.parse(Json.stringify(mapOf("d" to 2.0))))
    }

    @Test fun projectRoundTripsThroughJson() {
        val store = newStore()
        var p = store.create("t", "objective", listOf("c one", "c two"))
        p = p.copy(
            tasks = listOf(Task("t1", "a", dependsOn = listOf("t0"), status = TaskStatus.BLOCKED, evidence = listOf("e1"))),
            experiments = listOf(Experiment("e1", "t1", "x", metrics = mapOf("ratio" to 0.42), succeeded = true)),
            memory = listOf(MemoryEntry("m1", MemoryKind.DISCOVERY, "found", 5, listOf("e1"))),
            watchdog = WatchdogState(3, 1, listOf("f"), listOf("h"), listOf("w"), 2, 1, true, "why"),
        )
        val back = Project.fromMap(Json.parseObject(Json.stringify(p.toMap())))
        assertEquals(p, back)
    }

    @Test fun corruptCurrentSnapshotFallsBackToPrevious() {
        val store = newStore()
        val p = store.create("t", "o", emptyList())
        store.update(p.id) { it.copy(summary = "v1") }
        store.update(p.id) { it.copy(summary = "v2") }
        // Simulate a torn write (the atomic rename makes this impossible in practice, but disks lie).
        File(store.dir(p.id), "state.json").writeText("{\"id\":\"${p.id}\",\"tit")
        val loaded = store.load(p.id)!!
        assertEquals("v1", loaded.summary)
        // Self-healed.
        assertEquals("v1", Project.fromMap(Json.parseObject(File(store.dir(p.id), "state.json").readText())).summary)
    }

    @Test fun bothSnapshotsCorruptFallsBackToCheckpoint() {
        val store = newStore()
        val p = store.create("t", "o", emptyList())
        val cp = store.update(p.id) { it.copy(summary = "checkpointed", stepCount = 20) }!!
        store.writeCheckpoint(cp)
        store.update(p.id) { it.copy(summary = "later") }
        File(store.dir(p.id), "state.json").writeText("garbage")
        File(store.dir(p.id), "state.prev.json").writeText("")
        assertEquals("checkpointed", store.load(p.id)!!.summary)
    }

    @Test fun checkpointsAreRotated() {
        val store = ProjectStore(tempDir(), keepCheckpoints = 3)
        val p = store.create("t", "o", emptyList())
        for (i in 1..7) store.writeCheckpoint(p.copy(stepCount = i.toLong()))
        assertEquals(3, File(store.dir(p.id), "checkpoints").listFiles()!!.size)
    }

    @Test fun tornStepLogLineIsSkipped() {
        val store = newStore()
        val p = store.create("t", "o", emptyList())
        store.appendStep(p.id, StepRecord(1, "t1", "act", "a", "", true, "x", Progress.NEUTRAL))
        File(store.dir(p.id), "steps.jsonl").appendText("{\"step\":2,\"task")
        val steps = store.readSteps(p.id)
        assertEquals(listOf(1L), steps.map { it.step })
    }

    @Test fun projectsAreIsolated() {
        val store = newStore()
        val a = store.create("A", "alpha objective", emptyList())
        val b = store.create("B", "beta objective", emptyList())
        store.update(a.id) { it.copy(memory = it.memory + MemoryEntry("m1", MemoryKind.FINDING, "secret alpha fact")) }
        store.workspaceDir(a.id).resolve("x.txt").writeText("alpha")
        val bb = store.load(b.id)!!
        assertTrue(bb.memory.none { it.text.contains("alpha") })
        assertFalse(store.workspaceDir(b.id).resolve("x.txt").exists())
        assertNotEquals(store.workspaceDir(a.id).canonicalPath, store.workspaceDir(b.id).canonicalPath)
    }

    @Test fun inFlightMarkerRoundTrip() {
        val store = newStore()
        val p = store.create("t", "o", emptyList())
        assertNull(store.readInFlight(p.id))
        store.markInFlight(p.id, 5, "t2", "run_code")
        assertEquals(ProjectStore.InFlight(5, "t2", "run_code"), store.readInFlight(p.id))
        store.clearInFlight(p.id)
        assertNull(store.readInFlight(p.id))
    }

    @Test fun defaultCriterionIsCreatedWhenNoneGiven() {
        val p = newStore().create("", "make thing", emptyList())
        assertEquals(1, p.criteria.size)
        assertEquals("make thing", p.title)
    }
}

package com.druk.agent.store

import com.druk.agent.json.Json
import com.druk.agent.json.long
import com.druk.agent.json.str
import com.druk.agent.model.Project
import com.druk.agent.model.ProjectStatus
import com.druk.agent.model.StepRecord
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Crash-safe, per-project persistence.
 *
 * Layout under [root]:
 * ```
 * projects/<id>/state.json        current snapshot (atomic replace)
 * projects/<id>/state.prev.json   previous snapshot (fallback if current is corrupt)
 * projects/<id>/checkpoints/      rolling full snapshots (last [keepCheckpoints])
 * projects/<id>/steps.jsonl       append-only step log (full history for the UI/export)
 * projects/<id>/inflight.json     marker written before a step's side effects, removed on commit
 * projects/<id>/workspace/        the project's sandboxed files
 * ```
 *
 * Every write goes temp file → fsync → atomic rename, so a kill at any point
 * leaves either the old or the new snapshot, never a torn one. Projects never
 * share files, which is what keeps their memories from contaminating each other.
 *
 * All mutations go through [update], which applies a function to the *latest*
 * committed state under a per-project lock. The engine never blindly
 * overwrites: a pause the user pressed while the model was thinking survives
 * the step's commit.
 */
class ProjectStore(val root: File, private val clock: () -> Long = System::currentTimeMillis, private val keepCheckpoints: Int = 5) {

    private val projectsDir = File(root, "projects").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<(Project) -> Unit>()

    fun addListener(l: (Project) -> Unit) { listeners += l }
    fun removeListener(l: (Project) -> Unit) { listeners -= l }

    private fun lock(id: String) = locks.getOrPut(id) { Any() }
    fun dir(id: String) = File(projectsDir, id)
    fun workspaceDir(id: String) = File(dir(id), "workspace").apply { mkdirs() }

    fun create(title: String, objective: String, criteria: List<String>, modelFile: String = ""): Project {
        val id = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = clock()
        var p = Project(id = id, title = title.ifBlank { objective.take(48) }, objective = objective, createdAt = now, updatedAt = now, modelFile = modelFile)
        val crits = criteria.filter { it.isNotBlank() }.ifEmpty {
            listOf("The objective is achieved and supported by recorded evidence (experiments, artifacts or verified findings).")
        }
        val list = crits.map { text ->
            val (np, cid) = p.nextId("c"); p = np
            com.druk.agent.model.CompletionCriterion(cid, text.trim())
        }
        p = p.copy(criteria = list)
        dir(id).mkdirs()
        workspaceDir(id)
        synchronized(lock(id)) { writeSnapshot(p) }
        notify(p)
        return p
    }

    fun ids(): List<String> = projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun list(): List<Project> = ids().mapNotNull { load(it) }

    /**
     * Load the newest readable snapshot: current, then previous, then the
     * newest checkpoint. Returns null only when nothing is recoverable.
     */
    fun load(id: String): Project? = synchronized(lock(id)) { loadUnlocked(id) }

    private fun loadUnlocked(id: String): Project? {
        val d = dir(id)
        if (!d.isDirectory) return null
        val candidates = buildList {
            add(File(d, "state.json"))
            add(File(d, "state.prev.json"))
            File(d, "checkpoints").listFiles()?.sortedByDescending { it.name }?.let { addAll(it) }
        }
        for (f in candidates) {
            val p = readSnapshot(f) ?: continue
            if (f.name != "state.json") {
                // Self-heal so the next load doesn't fall back again.
                writeSnapshot(p)
            }
            return p
        }
        return null
    }

    private fun readSnapshot(f: File): Project? = try {
        if (!f.isFile || f.length() == 0L) null else Project.fromMap(Json.parseObject(f.readText()))
    } catch (_: Exception) {
        null
    }

    /** Apply [fn] to the latest committed state and commit the result atomically. */
    fun update(id: String, fn: (Project) -> Project): Project? {
        val result = synchronized(lock(id)) {
            val cur = loadUnlocked(id) ?: return null
            val next = fn(cur)
            if (next === cur) return cur
            val stamped = next.copy(updatedAt = clock(), rev = cur.rev + 1)
            writeSnapshot(stamped)
            stamped
        }
        notify(result)
        return result
    }

    fun setStatus(id: String, status: ProjectStatus): Project? = update(id) { p ->
        if (p.status == ProjectStatus.COMPLETED && status == ProjectStatus.ACTIVE) p.copy(status = status, finalReport = "")
        else p.copy(status = status, notBeforeMillis = 0, llmFailures = if (status == ProjectStatus.ACTIVE) 0 else p.llmFailures)
    }

    private fun writeSnapshot(p: Project) {
        val d = dir(p.id).apply { mkdirs() }
        val cur = File(d, "state.json")
        val prev = File(d, "state.prev.json")
        val bytes = Json.stringify(p.toMap()).toByteArray()
        if (cur.isFile && readSnapshot(cur) != null) {
            Files.copy(cur.toPath(), prev.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        atomicWrite(cur, bytes)
    }

    fun writeCheckpoint(p: Project) {
        val cpDir = File(dir(p.id), "checkpoints").apply { mkdirs() }
        atomicWrite(File(cpDir, "cp-%012d.json".format(p.stepCount)), Json.stringify(p.toMap()).toByteArray())
        cpDir.listFiles()?.sortedByDescending { it.name }?.drop(keepCheckpoints)?.forEach { it.delete() }
    }

    // ---- step log ----

    fun appendStep(id: String, rec: StepRecord) {
        val f = File(dir(id), "steps.jsonl")
        FileOutputStream(f, true).use { out ->
            out.write((Json.stringify(rec.toMap()) + "\n").toByteArray())
            out.fd.sync()
        }
    }

    /** Most recent [limit] steps; a torn final line (kill mid-append) is skipped. */
    fun readSteps(id: String, limit: Int = 200): List<StepRecord> {
        val f = File(dir(id), "steps.jsonl")
        if (!f.isFile) return emptyList()
        return f.readLines().takeLast(limit).mapNotNull { line ->
            try { StepRecord.fromMap(Json.parseObject(line)) } catch (_: Exception) { null }
        }
    }

    // ---- in-flight marker (crash detection) ----

    data class InFlight(val step: Long, val taskId: String, val action: String)

    fun markInFlight(id: String, step: Long, taskId: String, action: String) {
        atomicWrite(File(dir(id), "inflight.json"), Json.stringify(mapOf("step" to step, "taskId" to taskId, "action" to action)).toByteArray())
    }

    fun clearInFlight(id: String) { File(dir(id), "inflight.json").delete() }

    fun readInFlight(id: String): InFlight? = try {
        val f = File(dir(id), "inflight.json")
        if (!f.isFile) null else Json.parseObject(f.readText()).let { InFlight(it.long("step"), it.str("taskId"), it.str("action")) }
    } catch (_: Exception) {
        null
    }

    fun delete(id: String) = synchronized(lock(id)) { dir(id).deleteRecursively() }

    private fun notify(p: Project) { listeners.forEach { runCatching { it(p) } } }

    companion object {
        fun atomicWrite(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

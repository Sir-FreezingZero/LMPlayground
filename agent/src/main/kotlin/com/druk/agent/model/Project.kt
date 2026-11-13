package com.druk.agent.model

import com.druk.agent.json.*

/**
 * Durable state of one autonomous project.
 *
 * Everything the agent needs to continue lives here (plus the project's
 * workspace directory and step log), never in a model context. The engine
 * rebuilds a small prompt from this on every step, so a model unload,
 * process death or phone restart loses at most the step in flight.
 *
 * Immutable: every change is a copy, committed atomically by ProjectStore.
 */
data class Project(
    val id: String,
    val title: String,
    val objective: String,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val strategy: String = "",
    val strategyHistory: List<StrategyChange> = emptyList(),
    val subgoals: List<String> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val criteria: List<CompletionCriterion> = emptyList(),
    val experiments: List<Experiment> = emptyList(),
    val memory: List<MemoryEntry> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val checkpoints: List<Checkpoint> = emptyList(),
    val blockers: List<Blocker> = emptyList(),
    /** User-added guidance, newest last. Always shown to the model. */
    val instructions: List<String> = emptyList(),
    /** Compact rolling summary of the whole project, refreshed at checkpoints. */
    val summary: String = "",
    val nextAction: String = "",
    val lastAchievement: String = "",
    /** Monotonic step counter; also the clock for task backoff. */
    val stepCount: Long = 0,
    val lastProgressStep: Long = 0,
    val idCounter: Long = 0,
    /** Set when the plan must be rebuilt (new project, objective change, strategy change). */
    val needsPlanning: Boolean = true,
    val watchdog: WatchdogState = WatchdogState(),
    val recentSteps: List<StepRecord> = emptyList(),
    val llmFailures: Int = 0,
    /** Wall-clock time before which the runner must not step this project (backoff). */
    val notBeforeMillis: Long = 0,
    val finalReport: String = "",
    val modelFile: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Incremented on every committed write; detects concurrent edits during a step. */
    val rev: Long = 0,
) {
    fun task(id: String): Task? = tasks.firstOrNull { it.id == id }
    fun openBlockers(): List<Blocker> = blockers.filter { !it.resolved }

    /** Allocate a fresh id with a kind prefix ("t", "e", "m", "a", "c", "b"). */
    fun nextId(prefix: String): Pair<Project, String> {
        val n = idCounter + 1
        return copy(idCounter = n) to "$prefix$n"
    }

    fun progressFraction(): Float {
        val relevant = tasks.filter { it.status != TaskStatus.CANCELLED }
        val critDone = criteria.count { it.satisfied }
        val parts = relevant.size + criteria.size
        if (parts == 0) return 0f
        return (relevant.count { it.status == TaskStatus.DONE } + critDone).toFloat() / parts
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "v" to 1,
        "id" to id, "title" to title, "objective" to objective, "status" to status.name,
        "strategy" to strategy,
        "strategyHistory" to strategyHistory.map { it.toMap() },
        "subgoals" to subgoals,
        "tasks" to tasks.map { it.toMap() },
        "criteria" to criteria.map { it.toMap() },
        "experiments" to experiments.map { it.toMap() },
        "memory" to memory.map { it.toMap() },
        "artifacts" to artifacts.map { it.toMap() },
        "checkpoints" to checkpoints.map { it.toMap() },
        "blockers" to blockers.map { it.toMap() },
        "instructions" to instructions,
        "summary" to summary, "nextAction" to nextAction, "lastAchievement" to lastAchievement,
        "stepCount" to stepCount, "lastProgressStep" to lastProgressStep, "idCounter" to idCounter,
        "needsPlanning" to needsPlanning,
        "watchdog" to watchdog.toMap(),
        "recentSteps" to recentSteps.map { it.toMap() },
        "llmFailures" to llmFailures, "notBeforeMillis" to notBeforeMillis,
        "finalReport" to finalReport, "modelFile" to modelFile,
        "createdAt" to createdAt, "updatedAt" to updatedAt, "rev" to rev,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = Project(
            id = m.str("id"), title = m.str("title"), objective = m.str("objective"),
            status = m.enum("status", ProjectStatus.PAUSED),
            strategy = m.str("strategy"),
            strategyHistory = m.objList("strategyHistory").map(StrategyChange::fromMap),
            subgoals = m.strings("subgoals"),
            tasks = m.objList("tasks").map(Task::fromMap),
            criteria = m.objList("criteria").map(CompletionCriterion::fromMap),
            experiments = m.objList("experiments").map(Experiment::fromMap),
            memory = m.objList("memory").map(MemoryEntry::fromMap),
            artifacts = m.objList("artifacts").map(Artifact::fromMap),
            checkpoints = m.objList("checkpoints").map(Checkpoint::fromMap),
            blockers = m.objList("blockers").map(Blocker::fromMap),
            instructions = m.strings("instructions"),
            summary = m.str("summary"), nextAction = m.str("nextAction"),
            lastAchievement = m.str("lastAchievement"),
            stepCount = m.long("stepCount"), lastProgressStep = m.long("lastProgressStep"),
            idCounter = m.long("idCounter"),
            needsPlanning = m.bool("needsPlanning"),
            watchdog = m.obj("watchdog")?.let(WatchdogState::fromMap) ?: WatchdogState(),
            recentSteps = m.objList("recentSteps").map(StepRecord::fromMap),
            llmFailures = m.int("llmFailures"), notBeforeMillis = m.long("notBeforeMillis"),
            finalReport = m.str("finalReport"), modelFile = m.str("modelFile"),
            createdAt = m.long("createdAt"), updatedAt = m.long("updatedAt"), rev = m.long("rev"),
        )
    }
}

enum class ProjectStatus { ACTIVE, PAUSED, STOPPED, COMPLETED }

enum class TaskStatus { PENDING, RUNNING, DONE, FAILED, BLOCKED, CANCELLED }

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    /** 1 (low) .. 5 (high). */
    val priority: Int = 3,
    val dependsOn: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    /** Times a step on this task was cut off by process death. */
    val interruptions: Int = 0,
    val stepsSpent: Int = 0,
    val stepsThisAttempt: Int = 0,
    val notBeforeStep: Long = 0,
    val result: String = "",
    val failureReason: String = "",
    val blockedReason: String = "",
    val evidence: List<String> = emptyList(),
    val strategyGen: Int = 0,
    val createdStep: Long = 0,
) {
    val isTerminal get() = status == TaskStatus.DONE || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "title" to title, "description" to description, "priority" to priority,
        "dependsOn" to dependsOn, "status" to status.name, "attempts" to attempts,
        "maxAttempts" to maxAttempts, "interruptions" to interruptions, "stepsSpent" to stepsSpent,
        "stepsThisAttempt" to stepsThisAttempt, "notBeforeStep" to notBeforeStep,
        "result" to result, "failureReason" to failureReason, "blockedReason" to blockedReason,
        "evidence" to evidence, "strategyGen" to strategyGen, "createdStep" to createdStep,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = Task(
            id = m.str("id"), title = m.str("title"), description = m.str("description"),
            priority = m.int("priority", 3), dependsOn = m.strings("dependsOn"),
            status = m.enum("status", TaskStatus.PENDING), attempts = m.int("attempts"),
            maxAttempts = m.int("maxAttempts", 3), interruptions = m.int("interruptions"),
            stepsSpent = m.int("stepsSpent"), stepsThisAttempt = m.int("stepsThisAttempt"),
            notBeforeStep = m.long("notBeforeStep"), result = m.str("result"),
            failureReason = m.str("failureReason"), blockedReason = m.str("blockedReason"),
            evidence = m.strings("evidence"), strategyGen = m.int("strategyGen"),
            createdStep = m.long("createdStep"),
        )
    }
}

data class CompletionCriterion(
    val id: String,
    val text: String,
    val satisfied: Boolean = false,
    val evidence: List<String> = emptyList(),
    val note: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "text" to text, "satisfied" to satisfied, "evidence" to evidence, "note" to note,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = CompletionCriterion(
            m.str("id"), m.str("text"), m.bool("satisfied"), m.strings("evidence"), m.str("note"),
        )
    }
}

/** A recorded run with measured results — the unit of evidence for "it works". */
data class Experiment(
    val id: String,
    val taskId: String,
    val name: String,
    val hypothesis: String = "",
    val codeHash: String = "",
    val codePath: String = "",
    val metrics: Map<String, Double> = emptyMap(),
    val outputExcerpt: String = "",
    val succeeded: Boolean = false,
    val step: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "taskId" to taskId, "name" to name, "hypothesis" to hypothesis,
        "codeHash" to codeHash, "codePath" to codePath, "metrics" to metrics,
        "outputExcerpt" to outputExcerpt, "succeeded" to succeeded, "step" to step,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = Experiment(
            m.str("id"), m.str("taskId"), m.str("name"), m.str("hypothesis"), m.str("codeHash"),
            m.str("codePath"), m.doubleMap("metrics"), m.str("outputExcerpt"), m.bool("succeeded"),
            m.long("step"),
        )
    }
}

enum class MemoryKind { FINDING, DISCOVERY, FAILURE, NOTE, SUMMARY, INSTRUCTION }

data class MemoryEntry(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    /** 1..5; discoveries default high. */
    val importance: Int = 2,
    val evidence: List<String> = emptyList(),
    val taskId: String = "",
    val step: Long = 0,
    /** Archived entries were folded into a SUMMARY; still searchable via recall. */
    val archived: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "kind" to kind.name, "text" to text, "importance" to importance,
        "evidence" to evidence, "taskId" to taskId, "step" to step, "archived" to archived,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = MemoryEntry(
            m.str("id"), m.enum("kind", MemoryKind.NOTE), m.str("text"), m.int("importance", 2),
            m.strings("evidence"), m.str("taskId"), m.long("step"), m.bool("archived"),
        )
    }
}

/** A file the agent produced, pinned by content hash so claims about it can be checked. */
data class Artifact(
    val id: String,
    val path: String,
    val sha256: String,
    val size: Long,
    val description: String = "",
    val step: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "path" to path, "sha256" to sha256, "size" to size,
        "description" to description, "step" to step,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = Artifact(
            m.str("id"), m.str("path"), m.str("sha256"), m.long("size"), m.str("description"), m.long("step"),
        )
    }
}

data class Checkpoint(val id: String, val step: Long, val label: String, val summary: String, val time: Long) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "step" to step, "label" to label, "summary" to summary, "time" to time,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) =
            Checkpoint(m.str("id"), m.long("step"), m.str("label"), m.str("summary"), m.long("time"))
    }
}

data class Blocker(val id: String, val text: String, val step: Long, val resolved: Boolean = false, val needsUser: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "text" to text, "step" to step, "resolved" to resolved, "needsUser" to needsUser,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) =
            Blocker(m.str("id"), m.str("text"), m.long("step"), m.bool("resolved"), m.bool("needsUser"))
    }
}

data class StrategyChange(val step: Long, val oldStrategy: String, val newStrategy: String, val reason: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "step" to step, "old" to oldStrategy, "new" to newStrategy, "reason" to reason,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = StrategyChange(m.long("step"), m.str("old"), m.str("new"), m.str("reason"))
    }
}

enum class Progress { MEANINGFUL, NEUTRAL, NONE }

/** One executed step, as shown in the UI history and fed back (compactly) to the model. */
data class StepRecord(
    val step: Long,
    val taskId: String,
    val kind: String,
    val action: String,
    val args: String,
    val ok: Boolean,
    val output: String,
    val progress: Progress,
    val note: String = "",
    val fingerprint: String = "",
    val time: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "step" to step, "taskId" to taskId, "kind" to kind, "action" to action, "args" to args,
        "ok" to ok, "output" to output, "progress" to progress.name, "note" to note,
        "fingerprint" to fingerprint, "time" to time,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = StepRecord(
            m.long("step"), m.str("taskId"), m.str("kind"), m.str("action"), m.str("args"), m.bool("ok"),
            m.str("output"), m.enum("progress", Progress.NONE), m.str("note"), m.str("fingerprint"),
            m.long("time"),
        )
    }
}

/** Watchdog counters; persisted so stagnation detection survives restarts. */
data class WatchdogState(
    val stepsWithoutProgress: Int = 0,
    val strategyChangesWithoutProgress: Int = 0,
    val recentFingerprints: List<String> = emptyList(),
    val recentContentHashes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val planRounds: Int = 0,
    val emptyPlanRounds: Int = 0,
    /** Set by the watchdog: the next step must be a REFLECT (why did this fail, what instead). */
    val reflectPending: Boolean = false,
    val reflectReason: String = "",
    /** Bodies of the last few executed programs, for near-duplicate detection. */
    val recentCode: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "stepsWithoutProgress" to stepsWithoutProgress,
        "strategyChangesWithoutProgress" to strategyChangesWithoutProgress,
        "recentFingerprints" to recentFingerprints, "recentContentHashes" to recentContentHashes,
        "warnings" to warnings, "planRounds" to planRounds, "emptyPlanRounds" to emptyPlanRounds,
        "reflectPending" to reflectPending, "reflectReason" to reflectReason, "recentCode" to recentCode,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = WatchdogState(
            m.int("stepsWithoutProgress"), m.int("strategyChangesWithoutProgress"),
            m.strings("recentFingerprints"), m.strings("recentContentHashes"), m.strings("warnings"),
            m.int("planRounds"), m.int("emptyPlanRounds"), m.bool("reflectPending"), m.str("reflectReason"), m.strings("recentCode"),
        )
    }
}

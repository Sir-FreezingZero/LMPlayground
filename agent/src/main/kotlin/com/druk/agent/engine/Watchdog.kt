package com.druk.agent.engine

import com.druk.agent.model.*
import com.druk.agent.tools.Effect

/**
 * Separates real progress from activity that only looks productive.
 *
 * Every step is classified from what the tool *actually did* (its [Effect]),
 * never from the model's own description of it:
 *
 * - MEANINGFUL — new evidence: a recorded experiment with metrics, a
 *   successful code run with new output, an evidence-backed finding, a task
 *   completed with checked evidence, a criterion met, verified completion.
 * - NEUTRAL — groundwork: new file content, first-time reads/searches,
 *   new subtasks, notes.
 * - NONE — repeats (same action+args, same code+output, near-identical
 *   file), failures, rejected claims, unparseable replies.
 *
 * Stagnation accumulates as points (NONE = 2, NEUTRAL = 1, MEANINGFUL
 * resets) and drives escalating interventions:
 *   warn → reflect + change strategy → pause for the user.
 * Every rung is bounded, so a stuck model can never loop forever.
 */
object Watchdog {
    const val WARN_AT = 8
    const val REFLECT_AT = 20
    const val MAX_STRATEGY_CHANGES = 3
    const val TASK_STEP_LIMIT = 14
    const val FINGERPRINT_WINDOW = 30

    fun classify(effect: Effect, fingerprint: String, wd: WatchdogState): Progress {
        val repeated = fingerprint.isNotEmpty() && fingerprint in wd.recentFingerprints
        return when (effect) {
            Effect.EXPERIMENT, Effect.FINDING, Effect.TASK_DONE, Effect.CRITERION, Effect.PROJECT_DONE ->
                if (repeated) Progress.NONE else Progress.MEANINGFUL
            Effect.CODE_RUN -> if (repeated) Progress.NONE else Progress.MEANINGFUL
            Effect.WRITE_NEW, Effect.READ, Effect.SEARCH, Effect.TASK_ADDED, Effect.NOTE ->
                if (repeated) Progress.NONE else Progress.NEUTRAL
            Effect.TASK_FAILED -> Progress.NEUTRAL
            else -> Progress.NONE
        }
    }

    data class Outcome(val project: Project, val events: List<String>)

    /** Update counters after a step and apply any intervention. */
    fun afterStep(p: Project, progress: Progress, fingerprint: String, contentKey: String, action: String, taskId: String): Outcome {
        val events = mutableListOf<String>()
        val wd0 = p.watchdog
        val points = when (progress) {
            Progress.MEANINGFUL -> 0
            Progress.NEUTRAL -> wd0.stepsWithoutProgress + 1
            Progress.NONE -> wd0.stepsWithoutProgress + 2
        }
        var wd = wd0.copy(
            stepsWithoutProgress = points,
            strategyChangesWithoutProgress = if (progress == Progress.MEANINGFUL) 0 else wd0.strategyChangesWithoutProgress,
            recentFingerprints = (wd0.recentFingerprints + fingerprint).filter { it.isNotEmpty() }.takeLast(FINGERPRINT_WINDOW),
            recentContentHashes = (wd0.recentContentHashes + contentKey).filter { it.isNotEmpty() }.takeLast(FINGERPRINT_WINDOW),
            warnings = if (progress == Progress.MEANINGFUL) emptyList() else wd0.warnings,
        )
        var np = p.copy(watchdog = wd)
        if (progress == Progress.MEANINGFUL) np = np.copy(lastProgressStep = p.stepCount)

        if (progress == Progress.NONE && fingerprint in wd0.recentFingerprints) {
            wd = wd.copy(warnings = (wd.warnings + "You already did exactly this ($action). Do something different.").takeLast(3))
            np = np.copy(watchdog = wd)
        }
        if (points >= WARN_AT && wd0.stepsWithoutProgress < WARN_AT) {
            wd = wd.copy(warnings = (wd.warnings + "No real progress for several steps. Stop planning or re-reading; run code, record an experiment, or fail_task with a reason.").takeLast(3))
            np = np.copy(watchdog = wd)
            events += "watchdog: warned (stagnation $points)"
        }
        if (points >= REFLECT_AT && !wd.reflectPending) {
            val changes = wd.strategyChangesWithoutProgress
            if (changes >= MAX_STRATEGY_CHANGES) {
                np = pauseStalled(np, "Stalled: $changes strategy changes produced no meaningful progress. Review the project, add instructions, then resume.")
                events += "watchdog: paused project (stalled)"
            } else {
                np = np.copy(watchdog = wd.copy(reflectPending = true, reflectReason = "no meaningful progress for many steps on task $taskId (last action $action)"))
                events += "watchdog: requested strategy change"
            }
        }
        return Outcome(np, events)
    }

    fun pauseStalled(p: Project, text: String): Project {
        val (np, bid) = p.nextId("b")
        return np.copy(status = ProjectStatus.PAUSED, blockers = np.blockers + Blocker(bid, text, p.stepCount, needsUser = true))
    }
}

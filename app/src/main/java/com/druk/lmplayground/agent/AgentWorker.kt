package com.druk.lmplayground.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.druk.agent.engine.AgentRunner
import com.druk.agent.engine.CrashLoopGuard
import com.druk.agent.model.ProjectStatus
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import java.io.File

/**
 * Runs autonomous projects in the background as a long-running WorkManager
 * worker promoted to a foreground service (type dataSync — the same type
 * the app already declares for downloads).
 *
 * Realistic about Android: the OS may stop this at any time (Doze, memory
 * pressure, the Android 15+ daily limit on dataSync foreground services,
 * the user swiping the app away). Nothing is lost when that happens — each
 * step commits atomically — and [AgentResumeWorker] restarts the runner
 * later if projects are still ACTIVE. A crash-loop guard prevents a model
 * that kills the process on load from turning that into a restart loop.
 */
class AgentWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val manager = AgentManager.get(appContext)
    private var lastLine = "Starting…"

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(lastLine), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    override suspend fun doWork(): Result {
        val foreground = try {
            setForeground(getForegroundInfo()); true
        } catch (e: Exception) {
            // Background-start restrictions (Android 12+) can refuse promotion
            // when started from the periodic check. Run a bounded slice
            // anyway; the next periodic check continues.
            Log.w(TAG, "Running without foreground: ${e.message}"); false
        }
        val guard = CrashLoopGuard(File(applicationContext.filesDir, "agent/runner-guard.json"), System::currentTimeMillis)
        val backend = manager.backend()
        val engine = manager.engine(backend)
        val runner = AgentRunner(manager.store, engine, DeviceRunPolicy(applicationContext, manager.settings))
        val deadline = if (foreground) Long.MAX_VALUE else System.currentTimeMillis() + 8 * 60_000
        return try {
            val exit = runner.run(
                guard = guard,
                shouldContinue = { !isStopped && System.currentTimeMillis() < deadline },
                onStep = { r ->
                    val p = manager.store.load(r.projectId)
                    val line = when {
                        r.idle && r.kind == "llm-unavailable" -> "Waiting for model: ${r.message}"
                        r.idle -> lastLine
                        else -> "${p?.title?.take(30) ?: ""} · step ${r.step}: ${r.action}"
                    }
                    if (line != lastLine) {
                        lastLine = line
                        manager.runnerStatus.value = line
                        if (foreground) runCatching { setForegroundAsync(ForegroundInfo(NOTIFICATION_ID, notification(line), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
                    }
                },
            )
            manager.runnerStatus.value = when (exit) {
                AgentRunner.Exit.IDLE -> if (manager.hasActive()) "Waiting (backoff)" else "Idle"
                AgentRunner.Exit.POLICY_STOP -> "Paused by device conditions (battery/heat); will retry"
                AgentRunner.Exit.CRASH_LOOP -> "Stopped: the runner kept crashing. Open a project to review, then resume."
                else -> "Stopped"
            }
            if (exit == AgentRunner.Exit.CRASH_LOOP) {
                // Don't auto-restart into the same crash; require a user action.
                manager.projects.value.filter { it.status == ProjectStatus.ACTIVE }.forEach { manager.control.pause(it.id) }
                guard.reset()
            }
            Result.success()
        } finally {
            backend.release()
        }
    }

    private fun notification(text: String): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.penrose_triangle_monochrome)
            .setContentTitle(applicationContext.getString(R.string.agent_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, applicationContext.getString(R.string.agent_stop_runner), stop)
            .build()
    }

    companion object {
        const val WORK_NAME = "agent-runner"
        private const val CHANNEL = "agent"
        private const val NOTIFICATION_ID = 0xA6E7
        private const val TAG = "AgentWorker"
    }
}

/** Periodic safety net: restart the runner if ACTIVE projects exist and it isn't running. */
class AgentResumeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val m = AgentManager.get(applicationContext)
        if (m.hasActive() && !m.isRunnerRunning()) m.startRunner()
        return Result.success()
    }

    companion object { const val WORK_NAME = "agent-resume" }
}

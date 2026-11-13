package com.druk.lmplayground.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.druk.agent.engine.AgentEngine
import com.druk.agent.engine.EngineConfig
import com.druk.agent.engine.ProjectControl
import com.druk.agent.model.Project
import com.druk.agent.model.ProjectStatus
import com.druk.agent.store.ProjectStore
import com.druk.lmplayground.App
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.ModelFile
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Process-wide entry point to the agent: the project store (app-private
 * `files/agent/`), user controls, and scheduling of the background runner.
 *
 * The UI observes [projects]; every committed change (from the runner or
 * from the user) is pushed there by the store listener.
 */
class AgentManager private constructor(private val app: App) {

    val settings = AgentSettings(app)
    val store = ProjectStore(File(app.filesDir, "agent"))
    val control = ProjectControl(store)
    val storage = StorageRepository(app, StoragePreferences(app))

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    /** Live one-line status of the runner ("Thinking on t4…", "Waiting: battery 18%"). */
    val runnerStatus = MutableStateFlow("")

    init {
        store.addListener { refresh() }
        refresh()
    }

    fun refresh() {
        _projects.value = store.list().sortedByDescending { it.updatedAt }
    }

    fun backend(): AgentLlmBackend = AgentLlmBackend(
        context = app, client = app.inferenceClient, llamaCpp = app.llamaCpp, storage = storage,
        defaultModel = { settings.defaultModel ?: chatModels().firstOrNull()?.name },
        contextSize = settings.contextSize,
    )

    fun engine(backend: AgentLlmBackend) = AgentEngine(
        store = store, llm = backend,
        runner = SandboxJsRunner(app),
        web = if (settings.allowWeb) AgentWebAccess() else null,
        config = EngineConfig(
            // ~3.5 chars/token: leave room for the system prompt and reply.
            contextBudgetChars = ((settings.contextSize - 1100) * 3.2).toInt().coerceIn(2500, 24_000),
            codeTimeoutMs = 30_000,
        ),
    )

    /** Downloaded models that can chat (not embedding/ASR/vision projector files). */
    fun chatModels(): List<ModelFile> {
        val exclude = setOf(ModelInfoProvider.embeddingModel.filename, ModelInfoProvider.dictationModel.filename) +
            ModelInfoProvider.allModels.mapNotNull { it.mmprojFilename }
        return storage.getModelFiles().filter { f ->
            f.name.endsWith(".gguf", true) && f.name !in exclude && !f.name.contains("mmproj", true)
        }.sortedBy { it.sizeBytes }
    }

    // ---- user actions (all explicit) ----

    fun create(title: String, objective: String, criteria: List<String>, modelFile: String): Project {
        val p = store.create(title, objective, criteria, modelFile)
        startRunner()
        return p
    }

    fun pause(id: String) { control.pause(id); refresh() }
    fun stop(id: String) { control.stop(id); refresh() }
    fun resume(id: String) { control.resume(id); startRunner() }
    fun delete(id: String) { store.delete(id); refresh() }

    fun hasActive() = store.list().any { it.status == ProjectStatus.ACTIVE }

    /** Start (or keep) the single runner. Safe to call repeatedly. */
    fun startRunner() {
        val wm = WorkManager.getInstance(app)
        wm.enqueueUniqueWork(AgentWorker.WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AgentWorker>().build())
        schedulePeriodicResume()
    }

    /**
     * Every ~15 min WorkManager checks for ACTIVE projects and restarts the
     * runner if it isn't running — this is how work continues after process
     * death, reboots, OS time limits on foreground work, or a battery stop.
     * Paused/stopped projects are never touched.
     */
    fun schedulePeriodicResume() {
        val req = PeriodicWorkRequestBuilder<AgentResumeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(AgentResumeWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun isRunnerRunning(): Boolean = runCatching {
        WorkManager.getInstance(app).getWorkInfosForUniqueWork(AgentWorker.WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }.getOrDefault(false)

    /** Cancel the in-flight step immediately (it is not committed) and stop the runner. */
    fun stopRunner() = WorkManager.getInstance(app).cancelUniqueWork(AgentWorker.WORK_NAME)

    fun exportReport(p: Project): File {
        val ws = store.workspaceDir(p.id)
        val report = File(ws, "REPORT.md")
        if (p.finalReport.isNotBlank()) report.writeText(p.finalReport)
        else report.writeText(com.druk.agent.verify.CompletionVerifier.report(p, p.summary, listOf("project not finished — interim report")))
        return report
    }

    companion object {
        @Volatile private var instance: AgentManager? = null
        fun get(context: Context): AgentManager = instance ?: synchronized(this) {
            instance ?: AgentManager(context.applicationContext as App).also { instance = it }
        }
    }
}

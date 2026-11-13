package com.druk.lmplayground.agent

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.druk.agent.model.Project
import com.druk.agent.model.StepRecord
import com.druk.agent.tools.Workspace
import com.druk.lmplayground.storage.ModelFile
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    val manager = AgentManager.get(app)
    val projects: StateFlow<List<Project>> = manager.projects
    val runnerStatus: StateFlow<String> = manager.runnerStatus

    private val _models = MutableStateFlow<List<ModelFile>>(emptyList())
    val models: StateFlow<List<ModelFile>> = _models

    private val _steps = MutableStateFlow<List<StepRecord>>(emptyList())
    val steps: StateFlow<List<StepRecord>> = _steps

    private val _files = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val files: StateFlow<List<Pair<String, Long>>> = _files

    init {
        viewModelScope.launch(Dispatchers.IO) {
            manager.refresh()
            _models.value = runCatching { manager.chatModels() }.getOrDefault(emptyList())
            // Opening the screen is a good moment to make sure ACTIVE projects have a runner.
            if (manager.hasActive()) manager.startRunner()
        }
    }

    fun loadDetail(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _steps.value = manager.store.readSteps(id, 300).asReversed()
        _files.value = Workspace(manager.store.workspaceDir(id)).list()
    }

    suspend fun readFile(id: String, path: String): String = withContext(Dispatchers.IO) {
        runCatching { Workspace(manager.store.workspaceDir(id)).read(path).take(200_000) }.getOrElse { "Cannot read: ${it.message}" }
    }

    private fun io(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { block() } }

    fun create(title: String, objective: String, criteria: List<String>, model: String) = io { manager.create(title, objective, criteria, model) }
    fun pause(id: String) = io { manager.pause(id) }
    fun resume(id: String) = io { manager.resume(id) }
    fun stop(id: String) = io { manager.stop(id) }
    fun delete(id: String) = io { manager.delete(id) }
    fun addInstruction(id: String, text: String) = io { manager.control.addInstruction(id, text) }
    fun changeObjective(id: String, text: String) = io { manager.control.changeObjective(id, text) }
    fun addCriterion(id: String, text: String) = io { manager.control.addCriterion(id, text) }
    fun retryTask(id: String, taskId: String) = io { manager.control.retryTask(id, taskId) }
    fun cancelTask(id: String, taskId: String) = io { manager.control.cancelTask(id, taskId) }
    fun resolveBlocker(id: String, bid: String) = io { manager.control.resolveBlocker(id, bid) }
    fun setModel(id: String, model: String) = io { manager.control.setModel(id, model) }
    suspend fun report(p: Project): String = withContext(Dispatchers.IO) { manager.exportReport(p).readText() }
}

/** Hosts the Projects / agent section (list ⇄ detail is handled inside Compose). */
class ProjectsFragment : Fragment() {

    private val viewModel: ProjectsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                val projects by viewModel.projects.collectAsState()
                val status by viewModel.runnerStatus.collectAsState()
                val models by viewModel.models.collectAsState()
                val steps by viewModel.steps.collectAsState()
                val files by viewModel.files.collectAsState()
                PlaygroundTheme {
                    ProjectsRoot(
                        projects = projects,
                        runnerStatus = status,
                        models = models,
                        steps = steps,
                        files = files,
                        settings = viewModel.manager.settings,
                        actions = ProjectActions(
                            onBack = { findNavController().popBackStack() },
                            onOpen = { viewModel.loadDetail(it) },
                            onCreate = viewModel::create,
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = viewModel::stop,
                            onDelete = viewModel::delete,
                            onAddInstruction = viewModel::addInstruction,
                            onChangeObjective = viewModel::changeObjective,
                            onAddCriterion = viewModel::addCriterion,
                            onRetryTask = viewModel::retryTask,
                            onCancelTask = viewModel::cancelTask,
                            onResolveBlocker = viewModel::resolveBlocker,
                            onSetModel = viewModel::setModel,
                            readFile = viewModel::readFile,
                            report = viewModel::report,
                            onShare = { title, text ->
                                val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
                                    .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
                                startActivity(Intent.createChooser(send, title))
                            },
                        ),
                    )
                }
            }
        }
}

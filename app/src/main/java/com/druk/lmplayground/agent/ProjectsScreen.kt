@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.agent.model.MemoryKind
import com.druk.agent.model.Progress
import com.druk.agent.model.Project
import com.druk.agent.model.ProjectStatus
import com.druk.agent.model.StepRecord
import com.druk.agent.model.Task
import com.druk.agent.model.TaskStatus
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.ModelFile
import kotlinx.coroutines.launch

/** Callbacks from the Projects UI; kept in one holder so composables stay readable. */
class ProjectActions(
    val onBack: () -> Unit,
    val onOpen: (String) -> Unit,
    val onCreate: (title: String, objective: String, criteria: List<String>, model: String) -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onStop: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onAddInstruction: (String, String) -> Unit,
    val onChangeObjective: (String, String) -> Unit,
    val onAddCriterion: (String, String) -> Unit,
    val onRetryTask: (String, String) -> Unit,
    val onCancelTask: (String, String) -> Unit,
    val onResolveBlocker: (String, String) -> Unit,
    val onSetModel: (String, String) -> Unit,
    val readFile: suspend (String, String) -> String,
    val report: suspend (Project) -> String,
    val onShare: (String, String) -> Unit,
)

@Composable
fun ProjectsRoot(
    projects: List<Project>,
    runnerStatus: String,
    models: List<ModelFile>,
    steps: List<StepRecord>,
    files: List<Pair<String, Long>>,
    settings: AgentSettings,
    actions: ProjectActions,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val project = projects.firstOrNull { it.id == selected }
    if (selected != null && project != null) {
        BackHandler { selected = null }
        LaunchedEffect(project.id, project.stepCount) { actions.onOpen(project.id) }
        ProjectDetail(project, steps, files, models, actions, onBack = { selected = null })
    } else {
        ProjectList(projects, runnerStatus, models, settings, actions, onSelect = { selected = it })
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun ProjectList(
    projects: List<Project>,
    runnerStatus: String,
    models: List<ModelFile>,
    settings: AgentSettings,
    actions: ProjectActions,
    onSelect: (String) -> Unit,
) {
    var showNew by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.agent_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_project)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (runnerStatus.isNotBlank()) item {
                Text(runnerStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (projects.isEmpty()) item {
                Text(stringResource(R.string.projects_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            val groups = listOf(
                R.string.projects_active to projects.filter { it.status == ProjectStatus.ACTIVE },
                R.string.projects_paused to projects.filter { it.status == ProjectStatus.PAUSED || it.status == ProjectStatus.STOPPED },
                R.string.projects_completed to projects.filter { it.status == ProjectStatus.COMPLETED },
            )
            for ((label, list) in groups) {
                if (list.isEmpty()) continue
                item(key = "h$label") {
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(list, key = { it.id }) { p -> ProjectCard(p) { onSelect(p.id) } }
            }
        }
    }
    if (showNew) NewProjectDialog(models, settings.defaultModel, onDismiss = { showNew = false }) { t, o, c, m ->
        actions.onCreate(t, o, c, m); showNew = false
    }
    if (showSettings) AgentSettingsDialog(settings, models) { showSettings = false }
}

@Composable
private fun ProjectCard(p: Project, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusLabel(p.status)
            }
            LinearProgressIndicator(progress = { p.progressFraction() }, modifier = Modifier.fillMaxWidth())
            val current = p.tasks.firstOrNull { it.status == TaskStatus.RUNNING }
            Meta(stringResource(R.string.project_current), current?.let { "${it.id} ${it.title}" } ?: p.nextAction.ifBlank { "—" })
            if (p.lastAchievement.isNotBlank()) Meta(stringResource(R.string.project_last_achievement), p.lastAchievement)
            p.checkpoints.lastOrNull()?.let { Meta(stringResource(R.string.project_checkpoint), "step ${it.step}") }
            val blockers = p.openBlockers()
            if (blockers.isNotEmpty()) Text(
                stringResource(R.string.project_blockers, blockers.size) + ": " + blockers.last().text,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusLabel(s: ProjectStatus) {
    val (text, color) = when (s) {
        ProjectStatus.ACTIVE -> stringResource(R.string.status_running) to MaterialTheme.colorScheme.primary
        ProjectStatus.PAUSED -> stringResource(R.string.status_paused) to MaterialTheme.colorScheme.tertiary
        ProjectStatus.STOPPED -> stringResource(R.string.status_stopped) to MaterialTheme.colorScheme.onSurfaceVariant
        ProjectStatus.COMPLETED -> stringResource(R.string.status_completed) to MaterialTheme.colorScheme.secondary
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

// ---------------------------------------------------------------- detail

@Composable
private fun ProjectDetail(
    p: Project,
    steps: List<StepRecord>,
    files: List<Pair<String, Long>>,
    models: List<ModelFile>,
    actions: ProjectActions,
    onBack: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    if (p.status == ProjectStatus.ACTIVE) {
                        IconButton(onClick = { actions.onPause(p.id) }) { Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause)) }
                    } else {
                        IconButton(onClick = { actions.onResume(p.id) }) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.resume)) }
                    }
                    if (p.status == ProjectStatus.ACTIVE || p.status == ProjectStatus.PAUSED) {
                        IconButton(onClick = { actions.onStop(p.id) }) { Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop)) }
                    }
                    IconButton(onClick = {
                        scope.launch { actions.onShare(p.title, actions.report(p)) }
                    }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.export_report)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_project)) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs = listOf(R.string.tab_overview, R.string.tab_tasks, R.string.tab_activity, R.string.tab_findings, R.string.tab_files)
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, res -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(res)) }) }
            }
            when (tab) {
                0 -> OverviewTab(p, models, actions)
                1 -> TasksTab(p, actions)
                2 -> ActivityTab(steps)
                3 -> FindingsTab(p)
                else -> FilesTab(files) { openFile = it }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_project)) },
        text = { Text(stringResource(R.string.delete_project_confirm)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete(p.id); onBack() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    openFile?.let { path ->
        var content by remember(path) { mutableStateOf("…") }
        LaunchedEffect(path) { content = actions.readFile(p.id, path) }
        AlertDialog(
            onDismissRequest = { openFile = null },
            title = { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                SelectionContainer {
                    Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { actions.onShare(path, content) }) { Text(stringResource(R.string.share)) } },
            dismissButton = { TextButton(onClick = { openFile = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun OverviewTab(p: Project, models: List<ModelFile>, actions: ProjectActions) {
    var instruction by remember(p.id) { mutableStateOf("") }
    var criterion by remember(p.id) { mutableStateOf("") }
    var editObjective by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLabel(p.status)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.project_steps, p.stepCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { p.progressFraction() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Section(stringResource(R.string.project_objective)) {
            Text(p.objective, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { editObjective = true }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.change_objective)) }
        }
        val blockers = p.openBlockers()
        if (blockers.isNotEmpty()) Section(stringResource(R.string.project_blockers_title)) {
            blockers.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.onResolveBlocker(p.id, b.id) }) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }
        if (p.strategy.isNotBlank()) Section(stringResource(R.string.project_strategy)) { Text(p.strategy, style = MaterialTheme.typography.bodyMedium) }
        if (p.nextAction.isNotBlank()) Section(stringResource(R.string.project_next)) { Text(p.nextAction, style = MaterialTheme.typography.bodyMedium) }
        if (p.lastAchievement.isNotBlank()) Section(stringResource(R.string.project_last_achievement)) { Text(p.lastAchievement, style = MaterialTheme.typography.bodyMedium) }
        Section(stringResource(R.string.project_criteria)) {
            p.criteria.forEach { c ->
                Text((if (c.satisfied) "✓ " else "○ ") + c.text + if (c.evidence.isNotEmpty()) "  [${c.evidence.joinToString()}]" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(criterion, { criterion = it }, modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.add_criterion)) })
                TextButton(onClick = { actions.onAddCriterion(p.id, criterion); criterion = "" }, enabled = criterion.isNotBlank()) { Text(stringResource(R.string.add)) }
            }
        }
        Section(stringResource(R.string.project_instructions)) {
            p.instructions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(instruction, { instruction = it }, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.add_instruction)) }, maxLines = 4)
                TextButton(onClick = { actions.onAddInstruction(p.id, instruction); instruction = "" }, enabled = instruction.isNotBlank()) { Text(stringResource(R.string.add)) }
            }
        }
        Section(stringResource(R.string.project_model)) {
            ModelPicker(models, p.modelFile) { actions.onSetModel(p.id, it) }
        }
        if (p.summary.isNotBlank()) Section(stringResource(R.string.project_summary)) { Text(p.summary, style = MaterialTheme.typography.bodySmall) }
        if (p.finalReport.isNotBlank()) Section(stringResource(R.string.project_report)) {
            SelectionContainer { Text(p.finalReport, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (editObjective) {
        var text by remember { mutableStateOf(p.objective) }
        AlertDialog(
            onDismissRequest = { editObjective = false },
            title = { Text(stringResource(R.string.change_objective)) },
            text = { OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { actions.onChangeObjective(p.id, text); editObjective = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editObjective = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun TasksTab(p: Project, actions: ProjectActions) {
    val order = listOf(TaskStatus.RUNNING, TaskStatus.PENDING, TaskStatus.BLOCKED, TaskStatus.FAILED, TaskStatus.DONE, TaskStatus.CANCELLED)
    val tasks = p.tasks.sortedWith(compareBy<Task> { order.indexOf(it.status) }.thenByDescending { it.priority })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tasks.isEmpty()) item { Text(stringResource(R.string.no_tasks_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { t ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.id} · ${t.status.name.lowercase()} · p${t.priority}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (t.status == TaskStatus.FAILED || t.status == TaskStatus.BLOCKED || t.status == TaskStatus.CANCELLED) {
                        TextButton(onClick = { actions.onRetryTask(p.id, t.id) }) { Text(stringResource(R.string.retry)) }
                    }
                    if (t.status == TaskStatus.PENDING || t.status == TaskStatus.BLOCKED) {
                        TextButton(onClick = { actions.onCancelTask(p.id, t.id) }) { Text(stringResource(R.string.cancel_task)) }
                    }
                }
                Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (t.status == TaskStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal)
                if (t.dependsOn.isNotEmpty()) Text("after ${t.dependsOn.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.result.isNotBlank()) Text("→ ${t.result}" + if (t.evidence.isNotEmpty()) " [${t.evidence.joinToString()}]" else "", style = MaterialTheme.typography.bodySmall)
                val why = listOf(t.failureReason, t.blockedReason).filter { it.isNotBlank() }.joinToString("; ")
                if (why.isNotBlank()) Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (t.attempts > 0 || t.stepsSpent > 0) Text("attempts ${t.attempts}/${t.maxAttempts} · ${t.stepsSpent} steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ActivityTab(steps: List<StepRecord>) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (steps.isEmpty()) item { Text(stringResource(R.string.no_activity_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(steps, key = { it.step }) { s ->
            Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == s.step) null else s.step }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${s.step} ${s.action}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val (label, color) = when (s.progress) {
                        Progress.MEANINGFUL -> stringResource(R.string.progress_meaningful) to MaterialTheme.colorScheme.primary
                        Progress.NEUTRAL -> stringResource(R.string.progress_groundwork) to MaterialTheme.colorScheme.onSurfaceVariant
                        Progress.NONE -> stringResource(R.string.progress_none) to MaterialTheme.colorScheme.error
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded == s.step) 20 else 2, overflow = TextOverflow.Ellipsis)
                if (s.args.isNotBlank() && expanded == s.step) Text(s.args, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(s.output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded == s.step) 200 else 3, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun FindingsTab(p: Project) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val discoveries = p.memory.filter { it.kind == MemoryKind.DISCOVERY }
        val findings = p.memory.filter { it.kind == MemoryKind.FINDING && !it.archived }
        val failures = p.memory.filter { it.kind == MemoryKind.FAILURE && !it.archived }
        val summaries = p.memory.filter { it.kind == MemoryKind.SUMMARY && !it.archived }
        if (discoveries.isNotEmpty()) {
            item { Text(stringResource(R.string.discoveries), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(discoveries, key = { it.id }) { Text("★ ${it.text}  [${it.evidence.joinToString()}]", style = MaterialTheme.typography.bodySmall) }
        }
        val exps = p.experiments.asReversed()
        if (exps.isNotEmpty()) {
            item { Text(stringResource(R.string.experiments), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(exps.take(100), key = { it.id }) { e ->
                Text("${e.id} ${e.name}: " + if (e.succeeded) e.metrics.entries.joinToString { "${it.key}=${"%.4g".format(it.value)}" } else "failed",
                    style = MaterialTheme.typography.bodySmall, color = if (e.succeeded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
        }
        if (findings.isNotEmpty()) {
            item { Text(stringResource(R.string.findings), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(findings.asReversed(), key = { it.id }) { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
        }
        if (p.strategyHistory.isNotEmpty() || failures.isNotEmpty()) {
            item { Text(stringResource(R.string.failed_approaches), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(p.strategyHistory.asReversed(), key = { "s${it.step}" }) { Text("step ${it.step}: ${it.reason} → ${it.newStrategy}", style = MaterialTheme.typography.bodySmall) }
            items(failures.asReversed(), key = { it.id }) { Text("✗ ${it.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (summaries.isNotEmpty()) {
            item { Text(stringResource(R.string.memory_summaries), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(summaries.asReversed(), key = { it.id }) { Text(it.text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FilesTab(files: List<Pair<String, Long>>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (files.isEmpty()) item { Text(stringResource(R.string.no_files_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(files, key = { it.first }) { (path, size) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 10.dp)) {
                Text(path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (size < 1024) "$size B" else "${size / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun ModelPicker(models: List<ModelFile>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(models.firstOrNull { it.name == selected }?.displayName ?: selected.ifBlank { stringResource(R.string.model_default) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.model_default)) }, onClick = { onPick(""); open = false })
            models.forEach { m ->
                DropdownMenuItem(text = { Text("${m.displayName} · ${m.sizeBytes / 1_048_576} MB") }, onClick = { onPick(m.name); open = false })
            }
        }
    }
    if (models.isEmpty()) Text(stringResource(R.string.no_models_downloaded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun NewProjectDialog(models: List<ModelFile>, defaultModel: String?, onDismiss: () -> Unit, onCreate: (String, String, List<String>, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var objective by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultModel.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_project)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.project_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(objective, { objective = it }, label = { Text(stringResource(R.string.project_objective)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(criteria, { criteria = it }, label = { Text(stringResource(R.string.project_criteria_hint)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.project_model), style = MaterialTheme.typography.labelMedium)
                ModelPicker(models, model) { model = it }
                Text(stringResource(R.string.project_background_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = objective.isNotBlank(), onClick = { onCreate(title, objective, criteria.lines().filter { it.isNotBlank() }, model) }) {
                Text(stringResource(R.string.start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun AgentSettingsDialog(settings: AgentSettings, models: List<ModelFile>, onDismiss: () -> Unit) {
    var minBattery by remember { mutableStateOf(settings.minBatteryPercent.toString()) }
    var charging by remember { mutableStateOf(settings.onlyWhileCharging) }
    var web by remember { mutableStateOf(settings.allowWeb) }
    var ctx by remember { mutableStateOf(settings.contextSize.toString()) }
    var model by remember { mutableStateOf(settings.defaultModel.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.agent_default_model), style = MaterialTheme.typography.labelMedium)
                ModelPicker(models, model) { model = it }
                OutlinedTextField(minBattery, { minBattery = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.agent_min_battery)) }, singleLine = true)
                OutlinedTextField(ctx, { ctx = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.agent_context_size)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_only_charging), modifier = Modifier.weight(1f))
                    Switch(checked = charging, onCheckedChange = { charging = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_allow_web), modifier = Modifier.weight(1f))
                    Switch(checked = web, onCheckedChange = { web = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.minBatteryPercent = minBattery.toIntOrNull() ?: 30
                settings.onlyWhileCharging = charging
                settings.allowWeb = web
                settings.contextSize = (ctx.toIntOrNull() ?: 4096).coerceIn(2048, 32768)
                settings.defaultModel = model.ifBlank { null }
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.agent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.druk.agent.model.MemoryKind
import com.druk.agent.model.Progress
import com.druk.agent.model.Project
import com.druk.agent.model.ProjectStatus
import com.druk.agent.model.StepRecord
import com.druk.agent.model.Task
import com.druk.agent.model.TaskStatus
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.ModelFile
import kotlinx.coroutines.delay
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

// ---------------------------------------------------------------- terminal palette

/** Claude Code–style palette layered over the app theme (works in light and dark). */
private class Term(
    val accent: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val dim: Color,
    val text: Color,
    val panel: Color,
    val border: Color,
)

@Composable
private fun term(): Term {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return Term(
        accent = Color(0xFFD97757),
        ok = if (dark) Color(0xFF4EBA65) else Color(0xFF2E8B47),
        warn = if (dark) Color(0xFFE5C07B) else Color(0xFF9A6A00),
        err = cs.error,
        dim = cs.onSurfaceVariant,
        text = cs.onSurface,
        panel = cs.surfaceContainerLow,
        border = cs.outlineVariant,
    )
}

private val Mono = FontFamily.Monospace

private fun mono(size: Int = 13, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Mono, fontSize = size.sp, lineHeight = (size + 5).sp, fontWeight = weight)

/** The rotating glyph Claude Code shows while it works. */
@Composable
private fun spinnerGlyph(running: Boolean): String {
    val frames = remember { listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢") }
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (running) { delay(130); i = (i + 1) % frames.size }
    }
    return if (running) frames[i] else "✻"
}

// ---------------------------------------------------------------- root

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
    var creating by rememberSaveable { mutableStateOf(false) }
    val project = projects.firstOrNull { it.id == selected }
    when {
        creating -> {
            BackHandler { creating = false }
            NewProjectScreen(models, settings.defaultModel, onBack = { creating = false }) { t, o, c, m ->
                actions.onCreate(t, o, c, m); creating = false
            }
        }
        selected != null && project != null -> {
            BackHandler { selected = null }
            LaunchedEffect(project.id, project.stepCount) { actions.onOpen(project.id) }
            ProjectSession(project, steps, files, models, runnerStatus, actions, onBack = { selected = null })
        }
        else -> ProjectList(projects, runnerStatus, models, settings, actions, onSelect = { selected = it }, onNew = { creating = true })
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
    onNew: () -> Unit,
) {
    val t = term()
    var showSettings by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val anyActive = projects.any { it.status == ProjectStatus.ACTIVE }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.projects), style = mono(17, FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("/config", style = mono(13), color = t.dim) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                // Welcome box, Claude Code style.
                Column(
                    Modifier.fillMaxWidth().border(1.dp, t.accent, RoundedCornerShape(10.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = t.accent)) { append(spinnerGlyph(anyActive) + " ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Autonomous projects") }
                    }, style = mono(15))
                    Text(
                        "${projects.count { it.status == ProjectStatus.ACTIVE }} active · " +
                            "${projects.count { it.status == ProjectStatus.PAUSED || it.status == ProjectStatus.STOPPED }} paused · " +
                            "${projects.count { it.status == ProjectStatus.COMPLETED }} done",
                        style = mono(12), color = t.dim,
                    )
                    if (runnerStatus.isNotBlank()) Text("⎿  $runnerStatus", style = mono(12), color = t.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(10.dp)).clickable(onClick = onNew).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("> ", style = mono(14, FontWeight.Bold), color = t.accent)
                    Text(stringResource(R.string.new_project_prompt), style = mono(14), color = t.dim)
                }
            }
            if (projects.isEmpty()) item {
                Text("⎿  " + stringResource(R.string.projects_empty), style = mono(12), color = t.dim, modifier = Modifier.padding(top = 4.dp))
            }
            val order = listOf(ProjectStatus.ACTIVE, ProjectStatus.PAUSED, ProjectStatus.STOPPED, ProjectStatus.COMPLETED)
            items(projects.sortedWith(compareBy<Project> { order.indexOf(it.status) }.thenByDescending { it.updatedAt }), key = { it.id }) { p ->
                ProjectRow(p, expanded[p.id] == true, onToggle = { expanded[p.id] = expanded[p.id] != true }, onOpen = { onSelect(p.id) }, actions = actions)
            }
        }
    }
    if (showSettings) AgentSettingsDialog(settings, models) { showSettings = false }
}

@Composable
private fun statusDot(p: Project, t: Term): Pair<String, Color> = when (p.status) {
    ProjectStatus.ACTIVE -> spinnerGlyph(true) to t.accent
    ProjectStatus.PAUSED -> "⏸" to t.warn
    ProjectStatus.STOPPED -> "■" to t.dim
    ProjectStatus.COMPLETED -> "✔" to t.ok
}

@Composable
private fun ProjectRow(p: Project, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, actions: ProjectActions) {
    val t = term()
    val (glyph, color) = statusDot(p, t)
    val running = p.tasks.firstOrNull { it.status == TaskStatus.RUNNING }
    val done = p.tasks.count { it.status == TaskStatus.DONE }
    val total = p.tasks.count { it.status != TaskStatus.CANCELLED }
    val blockers = p.openBlockers()
    Column(Modifier.fillMaxWidth().background(t.panel, RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, style = mono(14), color = color)
            Spacer(Modifier.width(8.dp))
            Text(p.title, style = mono(14, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (expanded) "▾" else "▸", style = mono(14), color = t.dim, modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 8.dp))
        }
        Text(
            "  ${p.status.name.lowercase()} · step ${p.stepCount} · $done/$total todos · ${p.criteria.count { it.satisfied }}/${p.criteria.size} criteria" +
                if (blockers.isNotEmpty()) " · ${blockers.size} blocked" else "",
            style = mono(12), color = if (blockers.isNotEmpty()) t.warn else t.dim,
        )
        ProgressBar(p.progressFraction(), t, Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
        val now = running?.let { "◼ ${it.id} ${it.title}" } ?: p.nextAction.takeIf { it.isNotBlank() }
        if (now != null) Text("  ⎿  $now", style = mono(12), maxLines = 1, overflow = TextOverflow.Ellipsis)
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 16.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                KeyVal("objective", p.objective, t, maxLines = 4)
                if (p.strategy.isNotBlank()) KeyVal("strategy", p.strategy, t, maxLines = 3)
                if (p.lastAchievement.isNotBlank()) KeyVal("last win", p.lastAchievement, t, valueColor = t.ok)
                p.checkpoints.lastOrNull()?.let { KeyVal("checkpoint", "step ${it.step} (${it.label})", t) }
                blockers.forEach { KeyVal("blocker", it.text, t, valueColor = t.warn, maxLines = 3) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CmdChip("open", t) { onOpen() }
                    if (p.status == ProjectStatus.ACTIVE) CmdChip("pause", t) { actions.onPause(p.id) }
                    else CmdChip("resume", t) { actions.onResume(p.id) }
                }
            }
        }
    }
}

@Composable
private fun KeyVal(key: String, value: String, t: Term, valueColor: Color = t.text, maxLines: Int = 2) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = t.dim)) { append("$key: ") }
        withStyle(SpanStyle(color = valueColor)) { append(value) }
    }, style = mono(12), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ProgressBar(f: Float, t: Term, modifier: Modifier = Modifier) {
    // Text-mode bar: ██████░░░░ 62%
    val cells = 20
    val filled = (f.coerceIn(0f, 1f) * cells).toInt()
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = t.accent)) { append("█".repeat(filled)) }
        withStyle(SpanStyle(color = t.border)) { append("░".repeat(cells - filled)) }
        withStyle(SpanStyle(color = t.dim)) { append(" ${(f * 100).toInt()}%") }
    }, style = mono(11), modifier = modifier)
}

@Composable
private fun CmdChip(label: String, t: Term, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = mono(12), color = if (selected) t.accent else t.dim,
        modifier = Modifier
            .border(1.dp, if (selected) t.accent else t.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------- session (detail)

private enum class Panel(val label: String) { TODOS("todos"), CRITERIA("criteria"), FINDINGS("findings"), FILES("files"), MEMORY("memory"), OBJECTIVE("objective") }

@Composable
private fun ProjectSession(
    p: Project,
    steps: List<StepRecord>,
    files: List<Pair<String, Long>>,
    models: List<ModelFile>,
    runnerStatus: String,
    actions: ProjectActions,
    onBack: () -> Unit,
) {
    val t = term()
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf<String?>(Panel.TODOS.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val expandedSteps = remember { mutableStateMapOf<Long, Boolean>() }
    // Local command echoes ("⎿ Paused."), newest last; not persisted.
    val echoes = remember(p.id) { mutableStateListOf<Pair<String, String>>() }
    val listState = rememberLazyListState()
    val chronological = remember(steps) { steps.sortedBy { it.step } }

    // Terminal behaviour: stick to the bottom when new steps arrive, unless the user scrolled up.
    LaunchedEffect(chronological.size, echoes.size) {
        val total = listState.layoutInfo.totalItemsCount
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (total == 0 || lastVisible >= total - 3) listState.animateScrollToItem((total - 1).coerceAtLeast(0))
    }

    fun run(input: String) {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        fun echo(out: String) { echoes += cmd to out }
        val arg = cmd.substringAfter(' ', "").trim()
        when (cmd.substringBefore(' ').lowercase()) {
            "/help" -> echo(HELP)
            "/pause" -> { actions.onPause(p.id); echo("Paused. Nothing runs until /resume.") }
            "/resume" -> { actions.onResume(p.id); echo("Resumed from step ${p.stepCount}.") }
            "/stop" -> { actions.onStop(p.id); echo("Stopped.") }
            "/objective" -> if (arg.isBlank()) echo(p.objective) else { actions.onChangeObjective(p.id, arg); echo("Objective updated; the agent will re-plan.") }
            "/criterion" -> if (arg.isBlank()) echo("Usage: /criterion <done when…>") else { actions.onAddCriterion(p.id, arg); echo("Criterion added.") }
            "/retry" -> if (p.task(arg) == null) echo("No task '$arg'.") else { actions.onRetryTask(p.id, arg); echo("Retrying $arg.") }
            "/cancel" -> if (p.task(arg) == null) echo("No task '$arg'.") else { actions.onCancelTask(p.id, arg); echo("Cancelled $arg.") }
            "/dismiss" -> {
                val targets = if (arg == "all" || arg.isBlank()) p.openBlockers() else p.openBlockers().filter { it.id == arg }
                targets.forEach { actions.onResolveBlocker(p.id, it.id) }
                echo(if (targets.isEmpty()) "No open blockers." else "Dismissed ${targets.size} blocker(s).")
            }
            "/model" -> { modelMenu = true; echo("Choose a model…") }
            "/export" -> { scope.launch { actions.onShare(p.title, actions.report(p)) }; echo("Exporting report…") }
            "/delete" -> confirmDelete = true
            "/todos", "/criteria", "/findings", "/files", "/memory", "/objective-panel" ->
                panel = Panel.entries.firstOrNull { "/" + it.label == cmd.lowercase() }?.name
            else -> if (cmd.startsWith("/")) echo("Unknown command. Try /help.") else {
                actions.onAddInstruction(p.id, cmd); echo("Noted. The agent will follow this from its next step.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(p.title, style = mono(15, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (g, c) = statusDot(p, t)
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = c)) { append("$g ${p.status.name.lowercase()}") }
                            withStyle(SpanStyle(color = t.dim)) { append(" · step ${p.stepCount} · ${models.firstOrNull { it.name == p.modelFile }?.displayName ?: p.modelFile.ifBlank { "default model" }}") }
                        }, style = mono(11), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { modelMenu = true })
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    if (p.status == ProjectStatus.ACTIVE) TextButton(onClick = { run("/pause") }) { Text("pause", style = mono(13), color = t.warn) }
                    else TextButton(onClick = { run("/resume") }) { Text("resume", style = mono(13), color = t.ok) }
                    Box {
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            DropdownMenuItem(text = { Text("default model", style = mono(13)) }, onClick = { actions.onSetModel(p.id, ""); modelMenu = false })
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text("${if (m.name == p.modelFile) "● " else "  "}${m.displayName} · ${m.sizeBytes / 1_048_576} MB", style = mono(13)) },
                                    onClick = { actions.onSetModel(p.id, m.name); modelMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            PromptBar(t, enabled = true, hint = if (p.status == ProjectStatus.ACTIVE) stringResource(R.string.prompt_hint_active) else stringResource(R.string.prompt_hint_idle)) { run(it) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Panel switcher: one row of toggles, each expands an inline view (like toggling the todo list).
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val counts = mapOf(
                    Panel.TODOS to "${p.tasks.count { it.status == TaskStatus.DONE }}/${p.tasks.count { it.status != TaskStatus.CANCELLED }}",
                    Panel.CRITERIA to "${p.criteria.count { it.satisfied }}/${p.criteria.size}",
                    Panel.FINDINGS to "${p.experiments.count { it.succeeded }}",
                    Panel.FILES to "${files.size}",
                    Panel.MEMORY to "${p.memory.count { !it.archived }}",
                    Panel.OBJECTIVE to "",
                )
                Panel.entries.forEach { pn ->
                    val sel = panel == pn.name
                    CmdChip("${pn.label}${counts[pn]?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""}", t, selected = sel) {
                        panel = if (sel) null else pn.name
                    }
                }
            }
            AnimatedVisibility(panel != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(horizontal = 12.dp)
                        .background(t.panel, RoundedCornerShape(10.dp)).padding(10.dp).verticalScroll(rememberScrollState()),
                ) {
                    when (panel?.let { Panel.valueOf(it) }) {
                        Panel.TODOS -> TodosPanel(p, t, actions)
                        Panel.CRITERIA -> CriteriaPanel(p, t, actions)
                        Panel.FINDINGS -> FindingsPanel(p, t)
                        Panel.FILES -> FilesPanel(p, files, t, actions)
                        Panel.MEMORY -> MemoryPanel(p, t)
                        Panel.OBJECTIVE -> ObjectivePanel(p, t)
                        null -> Unit
                    }
                }
            }
            // Transcript.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "objective") {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = t.accent, fontWeight = FontWeight.Bold)) { append("> ") }
                        append(p.objective)
                    }, style = mono(13), color = t.text, modifier = Modifier.background(t.panel, RoundedCornerShape(6.dp)).padding(8.dp).fillMaxWidth())
                }
                if (p.stepCount > chronological.size && chronological.isNotEmpty()) item(key = "older") {
                    Text("… ${p.stepCount - chronological.size} earlier steps (full log in Export)", style = mono(11), color = t.dim)
                }
                items(chronological, key = { "s" + it.step }) { s ->
                    StepBlock(s, expandedSteps[s.step] == true, t) { expandedSteps[s.step] = expandedSteps[s.step] != true }
                }
                p.openBlockers().forEach { b ->
                    item(key = "b" + b.id) {
                        Column {
                            Text("⚠ ${b.text}", style = mono(12), color = t.warn)
                            Text("  ⎿  /dismiss ${b.id} · /resume", style = mono(11), color = t.dim)
                        }
                    }
                }
                if (p.status == ProjectStatus.COMPLETED && p.finalReport.isNotBlank()) item(key = "report") {
                    Column(Modifier.border(1.dp, t.ok, RoundedCornerShape(8.dp)).padding(10.dp)) {
                        Text("✔ Verified complete", style = mono(13, FontWeight.SemiBold), color = t.ok)
                        SelectionContainer { Text(p.finalReport, style = mono(11), color = t.text) }
                    }
                }
                items(echoes.size, key = { "e$it" }) { i ->
                    val (cmd, out) = echoes[i]
                    Column {
                        Text("> $cmd", style = mono(13), color = t.dim)
                        Text("  ⎿  $out", style = mono(12), color = t.dim)
                    }
                }
                if (p.status == ProjectStatus.ACTIVE) item(key = "working") { WorkingLine(p, runnerStatus, t) }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_project), style = mono(15, FontWeight.SemiBold)) },
        text = { Text(stringResource(R.string.delete_project_confirm), style = mono(13)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete(p.id); onBack() }) { Text("delete", style = mono(13), color = t.err) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("cancel", style = mono(13)) } },
    )
}

private const val HELP = """/pause · /resume · /stop
/objective [text]   show or change the objective
/criterion <text>   add a completion criterion
/retry <tN> · /cancel <tN>
/dismiss [bN|all]   dismiss blockers
/todos /criteria /findings /files /memory
/model · /export · /delete
Anything else is sent to the agent as an instruction."""

@Composable
private fun WorkingLine(p: Project, runnerStatus: String, t: Term) {
    val glyph = spinnerGlyph(true)
    val verbs = remember { listOf("Working", "Thinking", "Experimenting", "Measuring", "Iterating", "Verifying") }
    var v by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4000); v = (v + 1) % verbs.size } }
    val current = p.tasks.firstOrNull { it.status == TaskStatus.RUNNING }
    Column {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = t.accent)) { append("$glyph ") }
            withStyle(SpanStyle(color = t.accent)) { append("${verbs[v]}…") }
            withStyle(SpanStyle(color = t.dim)) { append("  (step ${p.stepCount + 1}${current?.let { " · ${it.id}" } ?: ""} · /pause to interrupt)") }
        }, style = mono(13))
        if (runnerStatus.isNotBlank()) Text("  ⎿  $runnerStatus", style = mono(11), color = t.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One step, rendered like a Claude Code tool call: ⏺ Tool(args) / ⎿ output, tap to expand. */
@Composable
private fun StepBlock(s: StepRecord, expanded: Boolean, t: Term, onToggle: () -> Unit) {
    val parts = s.note.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
    val system = parts.filter { it.startsWith("watchdog") || it.startsWith("task t") || it.startsWith("paused") || it == "strategy change" }
    val thought = parts.firstOrNull { it !in system }
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        if (thought != null) {
            Row {
                Text("⏺ ", style = mono(13), color = t.text)
                Text(thought, style = mono(13), color = t.text)
            }
            Spacer(Modifier.height(4.dp))
        }
        val (dotColor, header) = when (s.kind) {
            "plan" -> t.accent to AnnotatedString("Plan")
            "reflect" -> t.warn to AnnotatedString("Rethink strategy")
            "verify" -> t.ok to AnnotatedString("Verify completion")
            else -> (if (s.action == "invalid") t.err else if (s.ok) t.ok else t.err) to buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(toolTitle(s.action)) }
                if (s.args.isNotBlank()) withStyle(SpanStyle(color = t.dim)) { append("(" + s.args.replace('\n', ' ').take(if (expanded) 600 else 70) + if (!expanded && s.args.length > 70) "…)" else ")") }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("⏺ ", style = mono(13), color = dotColor)
            Text(header, style = mono(13), color = t.text, modifier = Modifier.weight(1f), maxLines = if (expanded) 12 else 1, overflow = TextOverflow.Ellipsis)
            val (badge, bc) = when (s.progress) {
                Progress.MEANINGFUL -> "+progress" to t.ok
                Progress.NEUTRAL -> "" to t.dim
                Progress.NONE -> "no progress" to t.err
            }
            if (badge.isNotEmpty()) Text(" $badge", style = mono(10), color = bc)
            Text(" #${s.step}", style = mono(10), color = t.dim)
        }
        val lines = s.output.trimEnd().lines()
        val shown = if (expanded) lines else lines.take(3)
        shown.forEachIndexed { i, line ->
            Text((if (i == 0) "  ⎿  " else "     ") + line, style = mono(12),
                color = if (!s.ok && i == 0) t.err else t.dim,
                maxLines = if (expanded) 50 else 1, overflow = TextOverflow.Ellipsis)
        }
        if (!expanded && lines.size > 3) Text("     … +${lines.size - 3} lines (tap to expand)", style = mono(11), color = t.dim, fontStyle = FontStyle.Italic)
        system.forEach { Text("  ⚠ $it", style = mono(11), color = t.warn) }
    }
}

private fun toolTitle(action: String): String = when (action) {
    "write_file" -> "Write"; "edit_file" -> "Update"; "read_file" -> "Read"; "list_files" -> "List"
    "run_code" -> "Run"; "run_experiment" -> "Experiment"; "compare_experiments" -> "Compare"
    "web_search" -> "Web Search"; "read_url" -> "Fetch"; "record_finding" -> "Finding"; "note" -> "Note"
    "recall" -> "Recall"; "add_task" -> "Add Todo"; "complete_task" -> "Complete"; "fail_task" -> "Give Up"
    "mark_criterion" -> "Criterion"; "finish_project" -> "Finish"; "invalid" -> "Invalid reply"
    else -> action
}

// ---------------------------------------------------------------- panels

@Composable
private fun PanelTitle(text: String, t: Term) {
    Text(text, style = mono(12, FontWeight.SemiBold), color = t.accent, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun TodosPanel(p: Project, t: Term, actions: ProjectActions) {
    val order = listOf(TaskStatus.RUNNING, TaskStatus.PENDING, TaskStatus.BLOCKED, TaskStatus.FAILED, TaskStatus.DONE, TaskStatus.CANCELLED)
    val tasks = p.tasks.sortedWith(compareBy<Task> { order.indexOf(it.status) }.thenByDescending { it.priority })
    val open = remember { mutableStateMapOf<String, Boolean>() }
    PanelTitle("Todos", t)
    if (tasks.isEmpty()) Text("⎿  " + stringResource(R.string.no_tasks_yet), style = mono(12), color = t.dim)
    if (p.strategy.isNotBlank()) Text("strategy: ${p.strategy}", style = mono(11), color = t.dim, modifier = Modifier.padding(bottom = 4.dp))
    tasks.forEach { task ->
        val (box, color, strike) = when (task.status) {
            TaskStatus.DONE -> Triple("☒", t.dim, true)
            TaskStatus.RUNNING -> Triple("◼", t.accent, false)
            TaskStatus.PENDING -> Triple("☐", t.text, false)
            TaskStatus.BLOCKED -> Triple("⊘", t.warn, false)
            TaskStatus.FAILED -> Triple("✗", t.err, false)
            TaskStatus.CANCELLED -> Triple("–", t.dim, true)
        }
        val isOpen = open[task.id] == true
        Column(Modifier.fillMaxWidth().clickable { open[task.id] = !isOpen }.padding(vertical = 2.dp)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = color)) { append("$box ") }
                withStyle(SpanStyle(color = t.dim)) { append("${task.id} ") }
                withStyle(SpanStyle(color = color, fontWeight = if (task.status == TaskStatus.RUNNING) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (strike) TextDecoration.LineThrough else null)) { append(task.title) }
            }, style = mono(12), maxLines = if (isOpen) 4 else 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(isOpen) {
                Column(Modifier.padding(start = 20.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (task.description.isNotBlank()) Text(task.description, style = mono(11), color = t.dim)
                    KeyVal("status", "${task.status.name.lowercase()} · p${task.priority} · attempt ${task.attempts}/${task.maxAttempts} · ${task.stepsSpent} steps", t)
                    if (task.dependsOn.isNotEmpty()) KeyVal("after", task.dependsOn.joinToString(), t)
                    if (task.result.isNotBlank()) KeyVal("result", task.result + if (task.evidence.isNotEmpty()) " [${task.evidence.joinToString()}]" else "", t, valueColor = t.ok, maxLines = 4)
                    if (task.failureReason.isNotBlank()) KeyVal("failed", task.failureReason, t, valueColor = t.err, maxLines = 4)
                    if (task.blockedReason.isNotBlank()) KeyVal("blocked", task.blockedReason, t, valueColor = t.warn)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (task.status == TaskStatus.FAILED || task.status == TaskStatus.BLOCKED || task.status == TaskStatus.CANCELLED) CmdChip("retry", t) { actions.onRetryTask(p.id, task.id) }
                        if (task.status == TaskStatus.PENDING || task.status == TaskStatus.BLOCKED) CmdChip("cancel", t) { actions.onCancelTask(p.id, task.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CriteriaPanel(p: Project, t: Term, actions: ProjectActions) {
    PanelTitle("Completion criteria", t)
    p.criteria.forEach { c ->
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = if (c.satisfied) t.ok else t.text)) { append(if (c.satisfied) "☒ " else "☐ ") }
            withStyle(SpanStyle(color = t.dim)) { append("${c.id} ") }
            append(c.text)
            if (c.evidence.isNotEmpty()) withStyle(SpanStyle(color = t.dim)) { append("  [${c.evidence.joinToString()}]") }
        }, style = mono(12))
    }
    Text("⎿  add one with /criterion <text>", style = mono(11), color = t.dim, modifier = Modifier.padding(top = 4.dp))
    val blockers = p.openBlockers()
    if (blockers.isNotEmpty()) {
        PanelTitle("Blockers", t)
        blockers.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠ ${b.text}", style = mono(12), color = t.warn, modifier = Modifier.weight(1f))
                CmdChip("dismiss", t) { actions.onResolveBlocker(p.id, b.id) }
            }
        }
    }
}

@Composable
private fun FindingsPanel(p: Project, t: Term) {
    val disc = p.memory.filter { it.kind == MemoryKind.DISCOVERY }
    if (disc.isNotEmpty()) {
        PanelTitle("Discoveries", t)
        disc.forEach { Text("★ ${it.text}  [${it.evidence.joinToString()}]", style = mono(12), color = t.text) }
        Spacer(Modifier.height(6.dp))
    }
    PanelTitle("Experiments (${p.experiments.count { it.succeeded }} ok / ${p.experiments.size})", t)
    if (p.experiments.isEmpty()) Text("⎿  none yet", style = mono(12), color = t.dim)
    val open = remember { mutableStateMapOf<String, Boolean>() }
    p.experiments.asReversed().take(150).forEach { e ->
        val isOpen = open[e.id] == true
        Column(Modifier.fillMaxWidth().clickable { open[e.id] = !isOpen }) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = if (e.succeeded) t.ok else t.err)) { append(if (e.succeeded) "✓ " else "✗ ") }
                withStyle(SpanStyle(color = t.dim)) { append(e.id.padEnd(6)) }
                append(e.name.take(24).padEnd(25))
                withStyle(SpanStyle(color = t.dim)) { append(e.metrics.entries.joinToString("  ") { "${it.key}=${"%.4g".format(it.value)}" }) }
            }, style = mono(11), maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(isOpen) {
                Column(Modifier.padding(start = 16.dp, bottom = 4.dp)) {
                    if (e.hypothesis.isNotBlank()) KeyVal("hypothesis", e.hypothesis, t, maxLines = 4)
                    KeyVal("code", e.codePath.ifBlank { "(inline)" }, t)
                    KeyVal("task", e.taskId, t)
                    if (e.outputExcerpt.isNotBlank()) Text(e.outputExcerpt, style = mono(10), color = t.dim)
                }
            }
        }
    }
    val failed = p.memory.filter { it.kind == MemoryKind.FAILURE && !it.archived }
    if (p.strategyHistory.isNotEmpty() || failed.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        PanelTitle("Failed approaches", t)
        p.strategyHistory.asReversed().forEach { Text("↺ step ${it.step}: ${it.reason}\n   → ${it.newStrategy}", style = mono(11), color = t.warn) }
        failed.asReversed().take(20).forEach { Text("✗ ${it.text}", style = mono(11), color = t.dim) }
    }
}

@Composable
private fun FilesPanel(p: Project, files: List<Pair<String, Long>>, t: Term, actions: ProjectActions) {
    PanelTitle("Workspace", t)
    if (files.isEmpty()) Text("⎿  " + stringResource(R.string.no_files_yet), style = mono(12), color = t.dim)
    var openPath by remember(p.id) { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    LaunchedEffect(openPath) { openPath?.let { content = "…"; content = actions.readFile(p.id, it) } }
    files.forEach { (path, size) ->
        val isOpen = openPath == path
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { openPath = if (isOpen) null else path }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isOpen) "▾ " else "▸ ", style = mono(12), color = t.dim)
                Text(path, style = mono(12), color = t.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (size < 1024) "$size B" else "${size / 1024} KB", style = mono(10), color = t.dim)
            }
            AnimatedVisibility(isOpen) {
                Column(Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                    val numbered = remember(content) {
                        content.lines().take(400).mapIndexed { i, l -> "${(i + 1).toString().padStart(4)}  $l" }.joinToString("\n")
                    }
                    SelectionContainer {
                        Text(numbered, style = mono(10), color = t.text,
                            modifier = Modifier.fillMaxWidth().background(t.panel).horizontalScroll(rememberScrollState()).padding(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                        CmdChip("share", t) { actions.onShare(path, content) }
                        CmdChip("close", t) { openPath = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryPanel(p: Project, t: Term) {
    PanelTitle("Project memory (${p.memory.count { !it.archived }} active · ${p.memory.count { it.archived }} archived)", t)
    if (p.summary.isNotBlank()) {
        Text(p.summary, style = mono(11), color = t.text, modifier = Modifier.padding(bottom = 6.dp))
    }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    p.memory.filter { !it.archived }.asReversed().take(120).forEach { m ->
        val isOpen = open[m.id] == true
        val (glyph, color) = when (m.kind) {
            MemoryKind.DISCOVERY -> "★" to t.accent
            MemoryKind.FINDING -> "•" to t.text
            MemoryKind.FAILURE -> "✗" to t.err
            MemoryKind.SUMMARY -> "Σ" to t.ok
            MemoryKind.INSTRUCTION -> "»" to t.warn
            MemoryKind.NOTE -> "·" to t.dim
        }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append("$glyph ") }
            withStyle(SpanStyle(color = t.dim)) { append("${m.id} ") }
            append(m.text)
            if (m.evidence.isNotEmpty()) withStyle(SpanStyle(color = t.dim)) { append("  [${m.evidence.joinToString()}]") }
        }, style = mono(11), maxLines = if (isOpen) 30 else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { open[m.id] = !isOpen }.padding(vertical = 1.dp))
    }
    if (p.instructions.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        PanelTitle("Your instructions", t)
        p.instructions.forEach { Text("» $it", style = mono(11), color = t.warn) }
    }
}

@Composable
private fun ObjectivePanel(p: Project, t: Term) {
    PanelTitle("Objective", t)
    SelectionContainer { Text(p.objective, style = mono(12), color = t.text) }
    Text("⎿  change it with /objective <new text>", style = mono(11), color = t.dim, modifier = Modifier.padding(top = 4.dp))
    if (p.checkpoints.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        PanelTitle("Checkpoints", t)
        p.checkpoints.asReversed().take(10).forEach { Text("◆ step ${it.step} · ${it.label}", style = mono(11), color = t.dim) }
    }
}

// ---------------------------------------------------------------- prompt bar

@Composable
private fun PromptBar(t: Term, enabled: Boolean, hint: String, onSubmit: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val showHelp = text.startsWith("/") && !text.contains(' ')
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (showHelp) {
            val cmds = listOf("/help", "/pause", "/resume", "/stop", "/objective", "/criterion", "/retry", "/cancel", "/dismiss",
                "/todos", "/criteria", "/findings", "/files", "/memory", "/model", "/export", "/delete")
            Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
                cmds.filter { it.startsWith(text.lowercase()) }.take(6).forEach { c ->
                    Text(c, style = mono(12), color = t.accent, modifier = Modifier.fillMaxWidth().clickable { text = "$c " }.padding(vertical = 3.dp))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("> ", style = mono(14, FontWeight.Bold), color = t.accent)
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text(hint, style = mono(14), color = t.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = mono(14).copy(color = t.text),
                    cursorBrush = SolidColor(t.accent),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmit(text); text = "" }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (text.isNotBlank()) Text("↵", style = mono(16, FontWeight.Bold), color = t.accent,
                modifier = Modifier.clickable { onSubmit(text); text = "" }.padding(start = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- new project

@Composable
private fun NewProjectScreen(models: List<ModelFile>, defaultModel: String?, onBack: () -> Unit, onCreate: (String, String, List<String>, String) -> Unit) {
    val t = term()
    var title by rememberSaveable { mutableStateOf("") }
    var objective by rememberSaveable { mutableStateOf("") }
    var criteria by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(defaultModel.orEmpty()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_project), style = mono(16, FontWeight.SemiBold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.fillMaxWidth().border(1.dp, t.accent, RoundedCornerShape(10.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = t.accent)) { append("✻ ") }
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("What should the agent work on?") }
                }, style = mono(14))
                Text(stringResource(R.string.project_background_note), style = mono(11), color = t.dim)
            }
            TermField("objective", objective, { objective = it }, "e.g. Invent a lossless compressor that beats RLE+Huffman on the test corpus", t, minHeight = 120)
            TermField("done when (one per line)", criteria, { criteria = it }, "a codec with measured ratio < 0.5\nround-trip verified on every file", t, minHeight = 70)
            TermField("title (optional)", title, { title = it }, "codec research", t, minHeight = 0, singleLine = true)
            Text("model", style = mono(12), color = t.dim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CmdChip("default", t, selected = model.isBlank()) { model = "" }
                models.forEach { m -> CmdChip("${m.displayName} · ${m.sizeBytes / 1_048_576}MB", t, selected = model == m.name) { model = m.name } }
            }
            if (models.isEmpty()) Text("⚠ " + stringResource(R.string.no_models_downloaded), style = mono(12), color = t.warn)
            Row(
                Modifier.fillMaxWidth()
                    .border(1.dp, if (objective.isNotBlank()) t.accent else t.border, RoundedCornerShape(10.dp))
                    .clickable(enabled = objective.isNotBlank()) { onCreate(title, objective, criteria.lines().filter { it.isNotBlank() }, model) }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("↵ start project", style = mono(14, FontWeight.SemiBold), color = if (objective.isNotBlank()) t.accent else t.dim)
            }
        }
    }
}

@Composable
private fun TermField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, t: Term, minHeight: Int, singleLine: Boolean = false) {
    Column {
        Text(label, style = mono(12), color = t.dim, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth().border(1.dp, t.border, RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text("> ", style = mono(14, FontWeight.Bold), color = t.accent)
            Box(Modifier.weight(1f).heightIn(min = minHeight.dp)) {
                if (value.isEmpty()) Text(placeholder, style = mono(13), color = t.dim.copy(alpha = 0.6f))
                BasicTextField(value, onChange, textStyle = mono(13).copy(color = t.text), cursorBrush = SolidColor(t.accent),
                    singleLine = singleLine, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- /config

@Composable
private fun AgentSettingsDialog(settings: AgentSettings, models: List<ModelFile>, onDismiss: () -> Unit) {
    val t = term()
    var minBattery by remember { mutableStateOf(settings.minBatteryPercent.toString()) }
    var charging by remember { mutableStateOf(settings.onlyWhileCharging) }
    var web by remember { mutableStateOf(settings.allowWeb) }
    var ctx by remember { mutableStateOf(settings.contextSize.toString()) }
    var model by remember { mutableStateOf(settings.defaultModel.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("/config", style = mono(16, FontWeight.SemiBold), color = t.accent) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("default model", style = mono(12), color = t.dim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CmdChip("auto", t, selected = model.isBlank()) { model = "" }
                    models.forEach { m -> CmdChip(m.displayName, t, selected = model == m.name) { model = m.name } }
                }
                TermField("pause below battery % (unplugged)", minBattery, { minBattery = it.filter(Char::isDigit).take(3) }, "30", t, 0, singleLine = true)
                TermField("context size (tokens)", ctx, { ctx = it.filter(Char::isDigit).take(5) }, "4096", t, 0, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_only_charging), style = mono(12), modifier = Modifier.weight(1f))
                    Switch(checked = charging, onCheckedChange = { charging = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_allow_web), style = mono(12), modifier = Modifier.weight(1f))
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
            }) { Text("save", style = mono(13), color = t.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel", style = mono(13)) } },
    )
}

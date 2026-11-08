@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

// Tool test (Settings -> Tools -> top-right button). Self-contained in this
// file so it can be removed in one go: the only other changes are the button
// in ToolsScreen and the dialog in ToolsFragment. Text is English only.

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.chat.ToolCallRecord
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.App
import com.druk.lmplayground.conversation.GenerationParams
import com.druk.lmplayground.conversation.PreambleCacheManager
import com.druk.lmplayground.conversation.SystemPromptExtras
import com.druk.lmplayground.conversation.stripThinkTags
import com.druk.lmplayground.models.ChatTemplateOverrides
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.theme.PopupMenuShape
import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.tools.createDefault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One check. [prompt] is an everyday request that doesn't name the tool, so
 * the model has to work out which one fits from the system prompt alone.
 * It passes when the model calls [tool] with arguments [argsOk] accepts and
 * answers with what [expect] picks from the tool's result (null: nothing to
 * check, e.g. live search results). A null [tool] is the opposite check: no
 * tool should be called, and [expect] is matched against the answer itself.
 * Tools really run, except those with a [simulated] result (no alarm is
 * set, no note is saved).
 */
class ToolTestCase(
    val tool: String?,
    val prompt: String,
    val argsOk: (JSONObject) -> Boolean = { true },
    val expect: (String) -> String? = { null },
    val simulated: String? = null,
    /** Shown instead of the tool name for the no-tool checks. */
    val label: String = tool ?: "",
)

private fun JSONObject.text(key: String) = optString(key).lowercase()

/**
 * [key] from a successful plain-text result ("Key: value" lines, or the whole
 * text for a lone result), or null after an error or when it's missing.
 */
private fun String.field(key: String): String? {
    if (startsWith("Error:")) return null
    val label = key.replace('_', ' ').replaceFirstChar { it.uppercase() } + ": "
    val line = lines().firstOrNull { it.startsWith(label) }?.removePrefix(label)
    return (line ?: takeIf { key == "result" })?.trim()?.takeIf { it.isNotEmpty() }
}

val TOOL_TEST_CASES = listOf(
    ToolTestCase("calculate", "What is 1234 times 5678?",
        { "1234" in it.text("expression") && "5678" in it.text("expression") }, { it.field("result") }),
    // Math hidden in an everyday question, which small models try in their heads.
    ToolTestCase("calculate", "I paid with a \$20 bill for a \$7 order. How much change should I get?",
        { "20" in it.text("expression") && "7" in it.text("expression") }, { it.field("result") },
        label = "calculate (everyday)"),
    // The whole number is enough: "42.16 km" and "about 42.2 km" both answer it.
    ToolTestCase("convert_units", "How many kilometers is 26.2 miles?",
        { it.text("from").startsWith("mi") && it.text("to").startsWith("k") },
        { it.field("result")?.substringAfter("= ")?.substringBefore('.') }),
    // "In the morning" must reach the tool as morning, not get turned into PM.
    ToolTestCase("set_alarm", "Remind me to take out the trash at 6:15 in the morning.",
        { t -> "6:15" in t.text("time") && ("am" in t.text("time") || "morning" in t.text("time")) }, { "6:15" },
        simulated = """{"result":"Alarm \"take out the trash\" set for 6:15 AM tomorrow."}"""),
    // The date is in the system prompt, so ask for the time, which isn't.
    ToolTestCase("get_date_time", "What time is it right now?", { true },
        { it.field("time")?.substringBefore(' ') }),
    // The model should check, not ask the user where they are.
    ToolTestCase("get_location", "I'm lost. Can you tell me where I am?", { true }, { it.field("place")?.substringBefore(',') }),
    ToolTestCase("get_device_info", "How much RAM does my phone have?", { true }, { it.field("memory")?.substringBefore(' ') }),
    ToolTestCase("remember", "Please remember that my favorite color is teal.", { "teal" in it.text("fact") }, { null },
        simulated = """{"result":"Saved as fact 1."}"""),
    // Live search results differ every time, so only the call is checked.
    ToolTestCase("web_search", "What's in the news today?", { it.text("query").isNotBlank() }, { null }),
    ToolTestCase("web_search", "What's the title of the page at https://example.com?",
        { "example.com" in it.text("url") }, { it.field("title") }),
    ToolTestCase("search_wikipedia", "According to Wikipedia, what year was the Eiffel Tower completed?",
        { "eiffel" in it.text("query") }, { r -> "1889".takeIf { !r.startsWith("Error:") && it in r } }),
    ToolTestCase("run_javascript", "Write and run code to count the letter r in \"strawberry raspberry\".",
        { "strawberry" in it.text("code") }, { it.field("result") }),
    // And the other way round: no tool for small talk or writing.
    ToolTestCase(null, "Hi! How are you today?", label = "No tool: small talk"),
    ToolTestCase(null, "Write a two-line poem about the sea.", label = "No tool: writing"),
)

/** null: not run yet; Running: in progress; otherwise whether it passed and why. */
data class ToolTestResult(val passed: Boolean? = null, val running: Boolean = false, val detail: String = "")

/**
 * Loads the chosen model on its own and keeps it, with one session, until
 * the screen closes or another model is picked: the session is rewound to
 * before its question after each case, and the chat's preamble cache keeps
 * the 2,500-token tool list on disk, so the list is processed once.
 */
class ToolTestViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)
    private val storage = StorageRepository(app, prefs)
    private val preambleCache = PreambleCacheManager(app.filesDir)

    // The real tools and descriptions, all switched on, as a chat would send them.
    private val registry = ToolRegistry.createDefault(app).apply { getAllTools().forEach { setToolEnabled(it.name, true) } }
    private val toolsJson = registry.toOpenAIToolsJson()
    // The same system prompt builder a chat uses (no memory notes).
    private val promptExtras = SystemPromptExtras(app, prefs)

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models
    private val _results = MutableStateFlow(TOOL_TEST_CASES.map { ToolTestResult() })
    val results: StateFlow<List<ToolTestResult>> = _results
    private val _status = MutableStateFlow("Pick a model, then tap Run all, or Run next to one tool.")
    val status: StateFlow<String> = _status
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private var job: Job? = null

    private var loadedFile: String? = null
    private var handle: StorageRepository.ModelFileHandle? = null
    private var llama: LlamaModel? = null
    private var session: LlamaGenerationSession? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = storage.getModelFiles().map { it.name }.toSet()
            _models.value = ModelInfoProvider.getModelsWithStatus(downloaded).filter { it.isDownloaded }.map { it.model }
        }
    }

    fun stop() { job?.cancel() }

    /** Unloads the model; the screen calls this when it closes or the model changes. */
    fun release() {
        viewModelScope.launch(Dispatchers.IO) {
            job?.cancel()
            job?.join()
            unload()
        }
    }

    override fun onCleared() = unload()

    private fun unload() {
        try { session?.destroy() } catch (_: Throwable) {}
        try { llama?.unloadModel() } catch (_: Throwable) {}
        handle?.close()
        session = null; llama = null; handle = null; loadedFile = null
    }

    /** Tests every tool, or only the one at [only]. */
    fun run(model: ModelInfo, only: Int? = null) {
        if (_running.value) return
        val indices = only?.let { listOf(it) } ?: TOOL_TEST_CASES.indices.toList()
        indices.forEach { update(it, ToolTestResult()) }
        job = viewModelScope.launch(Dispatchers.IO) {
            _running.value = true
            try {
                val loaded = load(model) ?: return@launch
                // A changed default prompt or settings needs a fresh session.
                session?.destroy(); session = null
                for (i in indices) {
                    _status.value = "Testing ${TOOL_TEST_CASES[i].label}…"
                    update(i, ToolTestResult(running = true))
                    val started = System.currentTimeMillis()
                    val r = runCase(loaded, model.filename, model.name, TOOL_TEST_CASES[i])
                    update(i, r.copy(detail = "%.1fs. ".format((System.currentTimeMillis() - started) / 1000.0) + r.detail))
                }
                _status.value = "${model.name}: ${indices.count { _results.value[it].passed == true }} of ${indices.size} passed."
            } catch (e: CancellationException) {
                _status.value = "Stopped."
                throw e
            } catch (t: Throwable) {
                _status.value = "The test couldn't run: ${t.message}"
            } finally {
                _results.value = _results.value.map { if (it.running) ToolTestResult() else it }
                _running.value = false
            }
        }
    }

    private fun update(i: Int, r: ToolTestResult) {
        _results.value = _results.value.toMutableList().also { it[i] = r }
    }

    /** [model], loading it (and unloading any other) if needed; null if it can't be tested. */
    private fun load(model: ModelInfo): LlamaModel? {
        if (loadedFile == model.filename) return llama
        unload()
        val app = getApplication<Application>()
        val llamaCpp = (app as? App)?.llamaCpp ?: error("The inference engine isn't available.")
        val file = storage.openModelFile(model.filename) ?: error("Can't open ${model.filename}.")
        handle = file
        _status.value = "Loading ${model.name}…"
        val loaded = llamaCpp.loadModel(
            file.pfd,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    _status.value = "Loading ${model.name}… ${(progress * 100).toInt()}%"
                }
            },
            disableRepack = !prefs.repackEnabled,
            chatTemplateOverride = ChatTemplateOverrides.forModel(app, model.filename),
        )
        llama = loaded
        if (!loaded.supportsToolCalling()) {
            unload()
            _status.value = "${model.name} doesn't support tool calling, so there is nothing to test."
            return null
        }
        loadedFile = model.filename
        return loaded
    }

    private suspend fun runCase(llama: LlamaModel, filename: String, modelName: String, case: ToolTestCase): ToolTestResult {
        // Reuse the session, rewound to before its first question; a new one
        // when that isn't possible. The chat's default context (the tool list
        // alone is about 2,500 tokens), a short thinking budget so thinking
        // models fit both rounds, greedy decoding and a fixed seed.
        val live = session?.takeIf { it.rewindToTurn(0, "") >= 0 } ?: run {
            session?.destroy()
            // What a chat would send: the default system prompt (or the one
            // from Settings) with the tool list, and this model's own sampling
            // settings. Only the seed is fixed, so a rerun gives the same result.
            val prompt = promptExtras.compose("")
            val p = prefs.getModelGenerationParams(filename)?.let { GenerationParams.fromMap(it) }
                ?: GenerationParams.recommendedFor(filename)
            llama.createSession(4096, p.temperature, p.topP, p.repetitionPenalty, p.topK, p.minP, 42, 256, prompt)?.also {
                it.setTools(toolsJson)
                preambleCache.apply(it, filename, llama.getModelSize(), prompt, toolsJson)
            }.also { session = it }
        } ?: return ToolTestResult(false, detail = "Couldn't start a session.")
        live.addMessage(case.prompt, false)
        var answer = ""
        val ctx = currentCoroutineContext()
        val r = ToolCallLoop(live, testTools, maxToolRounds = 3).run(
            object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { answer = response }
            },
            supportsThinking = llama.supportsThinking(),
            enableThinking = false,
            isActive = { ctx.isActive },
        )
        return judge(case, r.rc, r.records, stripThinkTags(answer).trim())
    }

    private fun judge(case: ToolTestCase, rc: Int, records: List<ToolCallRecord>, answer: String): ToolTestResult {
        val said = "Answer: " + answer.ifEmpty { "(empty)" }.take(300)
        // A non-zero code means the engine gave up (e.g. the prompt didn't fit), not the model.
        if (rc != 0 && records.isEmpty() && answer.isEmpty()) {
            return ToolTestResult(false, detail = "The engine stopped with error $rc before the model answered.")
        }
        val called = records.joinToString { it.name }
        if (case.tool == null) {
            return when {
                records.isNotEmpty() -> ToolTestResult(false, detail = "Called $called, but no tool was needed. $said")
                answer.isEmpty() -> ToolTestResult(false, detail = "Gave no answer.")
                else -> ToolTestResult(true, detail = said)
            }
        }
        // The call with the right input if there is one (a search before reading a page is fine).
        val calls = records.filter { it.name == case.tool }
        val call = calls.firstOrNull { case.argsOk(runCatching { JSONObject(it.arguments) }.getOrDefault(JSONObject())) }
            ?: calls.firstOrNull() ?: return ToolTestResult(
            false,
            detail = (if (records.isEmpty()) "Didn't use a tool; answered on its own." else "Used $called instead.") + " $said",
        )
        // Other calls that weren't asked for (a memory note for an alarm) fail it
        // too; looking up the time first is harmless.
        records.map { it.name }.filter { it != case.tool && it != "get_date_time" }.distinct().takeIf { it.isNotEmpty() }?.let {
            return ToolTestResult(false, detail = "Right tool, but also called ${it.joinToString()}, which wasn't needed. $said")
        }
        val args = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())
        if (!case.argsOk(args)) return ToolTestResult(false, detail = "Right tool, wrong input: ${call.arguments}")
        val returned = (if (case.simulated != null) "Simulated: " else "Tool returned: ") + call.result.take(200)
        if (answer.isEmpty()) return ToolTestResult(false, detail = "Called it, then gave no answer. $returned")
        val expected = case.expect(call.result)
        fun norm(s: String) = s.lowercase().replace(Regex("[,\\s\\u00A0\\u202F]"), "")
        if (expected != null && norm(expected) !in norm(answer)) {
            return ToolTestResult(false, detail = "Called it, but the answer doesn't use the result (\"$expected\"). $returned\n$said")
        }
        return ToolTestResult(true, detail = "Called with ${call.arguments}. $returned\n$said")
    }

    /** The real tools, except the ones with side effects, which get their case's simulated result. */
    private val testTools = object : ToolCallLoop.ToolExecutor {
        // forget has no case of its own, but must never delete a real note in a test.
        private val simulated = TOOL_TEST_CASES.mapNotNull { c -> c.simulated?.let { c.tool!! to it } }.toMap() +
            ("forget" to """{"result":"Fact deleted."}""")
        override fun executeToolCalls(toolCallsJson: String): String {
            val calls = JSONArray(toolCallsJson)
            return JSONArray((0 until calls.length()).map { i ->
                val call = calls.getJSONObject(i)
                val name = call.getString("name")
                val content = simulated[name]?.let(registry::asText) ?: runCatching {
                    JSONArray(registry.executeToolCalls(JSONArray().put(call).toString())).getJSONObject(0).getString("content")
                }.getOrElse { "Error: " + (it.message ?: "The $name tool failed.") }
                JSONObject().put("id", call.getString("id")).put("name", name).put("content", content)
            }).toString()
        }
        override fun cancelInFlight() = registry.cancelInFlight()
    }
}

@Composable
fun ToolTestDialog(viewModel: ToolTestViewModel, onDismiss: () -> Unit) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<ModelInfo?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Location is tested with the real tool, so ask for its permission first;
    // the run starts after the answer either way.
    var afterAsking by remember { mutableStateOf<(() -> Unit)?>(null) }
    val (locationGranted, askLocation) = rememberLocationPermission {
        afterAsking?.invoke()
        afterAsking = null
    }
    fun start(only: Int? = null) {
        val model = chosen ?: return
        val run = { viewModel.run(model, only) }
        if ((only == null || TOOL_TEST_CASES[only].tool == "get_location") && !locationGranted && askLocation != null) {
            afterAsking = run
            askLocation()
        } else {
            run()
        }
    }
    val close = { viewModel.release(); onDismiss() }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Test tools") },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close") } }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Text(
                    "Each tool gets an everyday request that doesn't name it, with the same system prompt, " +
                        "tool list and sampling settings a chat uses. It passes when the model picks that tool " +
                        "on its own, gives it the right input and answers with the result. Two more checks make " +
                        "sure it answers small talk and writing without tools. Tools really run, except Set " +
                        "alarm and Memory, which are simulated. The model loads alongside any chat model " +
                        "(unload that first if memory is tight) and stays loaded until you close this screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { pickerOpen = true }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                            Text(chosen?.name ?: "Choose a model", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(pickerOpen, { pickerOpen = false }, shape = PopupMenuShape, border = menuBorder) {
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.name) }, onClick = {
                                    // Free the previous model now rather than at the next run.
                                    if (chosen != null && chosen != m) viewModel.release()
                                    chosen = m
                                    pickerOpen = false
                                })
                            }
                        }
                    }
                    Button(
                        onClick = { if (running) viewModel.stop() else start() },
                        enabled = chosen != null,
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text(if (running) "Stop" else "Run all") }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                HorizontalDivider()
                LazyColumn {
                    itemsIndexed(TOOL_TEST_CASES) { i, case ->
                        ToolTestRow(case, results[i], canRun = chosen != null && !running) { start(only = i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTestRow(case: ToolTestCase, r: ToolTestResult, canRun: Boolean, onRun: () -> Unit) {
    val failed = r.passed == false
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mark = when { r.running -> "…"; r.passed == true -> "✓"; failed -> "✗"; else -> "·" }
            Text(mark, color = if (failed) MaterialTheme.colorScheme.error else faint, modifier = Modifier.width(24.dp))
            Text(case.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRun, enabled = canRun) { Text("Run") }
        }
        Column(Modifier.padding(start = 24.dp)) {
            Text("“${case.prompt}”", style = MaterialTheme.typography.bodySmall, color = faint)
            if (r.detail.isNotEmpty()) {
                Text(
                    r.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else faint,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider()
}

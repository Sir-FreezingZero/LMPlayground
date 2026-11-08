@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

// Settings -> Testing: fixed tests run on a downloaded model, and one export
// of everything they saw and did (system prompt, tools, calls, results,
// answers, speeds) to send for review. Diagnostic text, so English only.

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.chat.ToolCallRecord
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.App
import com.druk.lmplayground.BuildConfig
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.GenerationParams
import com.druk.lmplayground.conversation.PreambleCacheManager
import com.druk.lmplayground.conversation.SystemPromptExtras
import com.druk.lmplayground.conversation.codeBlock
import com.druk.lmplayground.conversation.shareMarkdown
import com.druk.lmplayground.conversation.stripThinkTags
import com.druk.lmplayground.models.ChatTemplateOverrides
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import com.druk.lmplayground.theme.PlaygroundTheme
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One tool check. [prompt] is an everyday request that doesn't name the tool,
 * so the model has to work out which one fits from the system prompt alone.
 * It passes when the model calls [tool] with arguments [argsOk] accepts and
 * answers with what [expect] picks from the tool's result (null: nothing to
 * check, e.g. live search results). A null [tool] is the opposite check: no
 * tool should be called, and the answer must satisfy [answerOk].
 */
class ToolTestCase(
    val tool: String?,
    val prompt: String,
    val argsOk: (JSONObject) -> Boolean = { true },
    val expect: (String) -> String? = { null },
    /** Shown instead of the tool name for the no-tool checks. */
    val label: String = tool ?: "",
    val answerOk: (String) -> Boolean = { true },
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
        { t -> "6:15" in t.text("time") && ("am" in t.text("time") || "morning" in t.text("time")) }, { "6:15" }),
    // The date is in the system prompt, so ask for the time, which isn't.
    ToolTestCase("get_date_time", "What time is it right now?", { true },
        { it.field("time")?.substringBefore(' ') }),
    // The model should check, not ask the user where they are.
    ToolTestCase("get_location", "I'm lost. Can you tell me where I am?", { true }, { it.field("place")?.substringBefore(',') }),
    ToolTestCase("get_device_info", "How much RAM does my phone have?", { true }, { it.field("RAM")?.substringBefore(' ') }),
    // The fact must say what it is about, not just "teal".
    ToolTestCase("remember", "Please remember that my favorite color is teal.", { "teal" in it.text("fact") && "colo" in it.text("fact") }),
    // Live search results differ every time, so only the call is checked.
    ToolTestCase("web_search", "What's in the news today?", { it.text("query").isNotBlank() }),
    ToolTestCase("web_search", "What's the title of the page at https://example.com?",
        { "example.com" in it.text("url") }, { it.field("title") }),
    ToolTestCase("search_wikipedia", "According to Wikipedia, what year was the Eiffel Tower completed?",
        { "eiffel" in it.text("query") }, { r -> "1889".takeIf { !r.startsWith("Error:") && it in r } }),
    ToolTestCase("run_javascript", "Write and run code to count the letter r in \"strawberry raspberry\".",
        { "strawberry" in it.text("code") }, { it.field("result") }),
    // And the other way round: no tool for small talk or writing.
    ToolTestCase(null, "Hi! How are you today?", label = "No tool: small talk"),
    // Two lines, so a refusal ("I can't write poems") fails.
    ToolTestCase(null, "Write a two-line poem about the sea.", label = "No tool: writing",
        answerOk = { a -> a.lines().count { it.isNotBlank() } >= 2 }),
)

/**
 * One conversation, sent in order with every tool on: small talk, each tool,
 * following up on a result, and using earlier results. Nothing is judged.
 */
val CHAT_SCRIPT = listOf(
    "hello",
    "what's a good name for a goldfish?",
    "what is 1234 times 5678?",
    "I paid with a \$20 bill and my order was \$7. how much change do I get?",
    "how many kilometers is 26.2 miles?",
    "what time is it?",
    "I'm lost, where am I?",
    "how much RAM does my phone have?",
    "what's in the news today?",
    "open the first one and tell me what it says",
    "according to wikipedia, when was the Eiffel Tower finished?",
    "remember that my favorite color is teal",
    "set an alarm for 6:15 tomorrow morning called trash",
    "what did you find out about where I am, and what's my favorite color?",
    "thanks!",
)

private val READING = (1..12).joinToString("\n\n") {
    "Part $it. The harbor town of Westmere sits where the river meets the sea. Fishing boats leave before dawn " +
        "and return by noon, when the market fills with buyers from the inland villages. In winter the storms " +
        "close the harbor for days at a time, and the town lives on what it has dried, salted and stored."
}

/** Speed, each in a fresh session without tools, read from the engine's own timings. */
val SPEED_CASES = listOf(
    "Writing" to "Write a short story about a lighthouse keeper, about 150 words.",
    "Reading" to "Summarize this in two sentences.\n\n$READING",
)

enum class TestSuite(val title: String, val about: String) {
    TOOLS("Tools", "Each tool gets an everyday request that doesn't name it. It passes when the model picks " +
        "that tool on its own, gives it the right input and answers with the result; the last two check it " +
        "answers small talk and writing without tools."),
    CHAT("Chat", "One conversation of ${CHAT_SCRIPT.size} messages in order, with every tool on. Nothing " +
        "is judged: it shows how the model chats, uses tools and remembers results."),
    SPEED("Speed", "Reading and writing speed with no tools, from the engine's own timings."),
}

private fun TestSuite.labels(): List<Pair<String, String>> = when (this) {
    TestSuite.TOOLS -> TOOL_TEST_CASES.map { it.label to it.prompt }
    TestSuite.CHAT -> CHAT_SCRIPT.mapIndexed { i, m -> "Message ${i + 1}" to m }
    TestSuite.SPEED -> SPEED_CASES
}

/** Not run yet (passed null, not running), running, or done: [detail] for the screen, [log] for the export. */
data class TestResult(val passed: Boolean? = null, val running: Boolean = false, val detail: String = "", val log: String = "")

/**
 * Loads the chosen model on its own and keeps it until the screen closes or
 * another model is picked. Uses what a chat uses: the system prompt builder
 * (no memory notes), every real tool, and the model's own settings with a
 * fixed seed. Tools really run, except set_alarm, remember and forget, whose
 * results are made up so a test sets no alarm and changes no saved facts.
 */
class ModelTestsViewModel(app: Application) : AndroidViewModel(app) {

    /** What one message produced. */
    private class Turn(val rc: Int, val records: List<ToolCallRecord>, val answer: String, val ms: Long)

    private val prefs = StoragePreferences(app)
    private val storage = StorageRepository(app, prefs)
    private val preambleCache = PreambleCacheManager(app.filesDir)
    private val registry = ToolRegistry.createDefault(app).apply { getAllTools().forEach { setToolEnabled(it.name, true) } }
    private val toolsJson = registry.toOpenAIToolsJson()
    private val promptExtras = SystemPromptExtras(app, prefs)

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models
    private val _results = MutableStateFlow(TestSuite.entries.associateWith { s -> s.labels().map { TestResult() } })
    val results: StateFlow<Map<TestSuite, List<TestResult>>> = _results
    private val _status = MutableStateFlow("Pick a model, then run all tests or one group. Export sends everything.")
    val status: StateFlow<String> = _status
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private var job: Job? = null
    private var tested: ModelInfo? = null

    private var loadedFile: String? = null
    private var handle: StorageRepository.ModelFileHandle? = null
    private var llama: LlamaModel? = null
    private var toolSession: LlamaGenerationSession? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = storage.getModelFiles().map { it.name }.toSet()
            _models.value = ModelInfoProvider.getModelsWithStatus(downloaded).filter { it.isDownloaded }.map { it.model }
        }
    }

    fun stop() { job?.cancel() }

    /** Unloads the model when another one is picked. */
    fun release() {
        viewModelScope.launch(Dispatchers.IO) {
            job?.cancel()
            job?.join()
            unload()
        }
    }

    override fun onCleared() = unload()

    private fun unload() {
        try { toolSession?.destroy() } catch (_: Throwable) {}
        try { llama?.unloadModel() } catch (_: Throwable) {}
        handle?.close()
        toolSession = null; llama = null; handle = null; loadedFile = null
    }

    /** Runs [suites], or only case [only] of the one suite given. */
    fun run(model: ModelInfo, suites: List<TestSuite>, only: Int? = null) {
        if (_running.value) return
        if (tested != model) _results.value = TestSuite.entries.associateWith { s -> s.labels().map { TestResult() } }
        tested = model
        job = viewModelScope.launch(Dispatchers.IO) {
            _running.value = true
            try {
                val loaded = load(model) ?: return@launch
                // A changed default prompt or settings needs a fresh session.
                dropToolSession()
                for (suite in suites) {
                    val indices = only?.let { listOf(it) } ?: suite.labels().indices.toList()
                    indices.forEach { update(suite, it, TestResult()) }
                    when (suite) {
                        TestSuite.TOOLS -> indices.forEach { i -> runTool(loaded, model, i) }
                        TestSuite.CHAT -> runChat(loaded, model)
                        TestSuite.SPEED -> indices.forEach { i -> runSpeed(loaded, model, i) }
                    }
                }
                val tools = _results.value.getValue(TestSuite.TOOLS)
                _status.value = "${model.name}: done. Tools: ${tools.count { it.passed == true }} of " +
                    "${tools.count { it.passed != null }} passed. Tap share to export."
            } catch (e: CancellationException) {
                _status.value = "Stopped."
                throw e
            } catch (t: Throwable) {
                _status.value = "The test couldn't run: ${t.message}"
            } finally {
                _results.value = _results.value.mapValues { (_, list) -> list.map { if (it.running) TestResult() else it } }
                _running.value = false
            }
        }
    }

    private fun update(suite: TestSuite, i: Int, r: TestResult) {
        _results.value = _results.value.toMutableMap().also { m -> m[suite] = m.getValue(suite).toMutableList().also { it[i] = r } }
    }

    private fun started(suite: TestSuite, i: Int) {
        _status.value = "${suite.title}: ${suite.labels()[i].first}…"
        update(suite, i, TestResult(running = true))
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
        loadedFile = model.filename
        return loaded
    }

    private fun params(model: ModelInfo) =
        prefs.getModelGenerationParams(model.filename)?.let { GenerationParams.fromMap(it) } ?: GenerationParams()

    private fun systemPrompt(model: ModelInfo, withTools: Boolean) =
        promptExtras.compose("")

    /**
     * A session as a chat would start it: the model's context size and
     * sampling settings with a fixed seed, so a rerun gives the same result,
     * and a short thinking budget (tests run with thinking off).
     */
    private fun newSession(llama: LlamaModel, model: ModelInfo, withTools: Boolean): LlamaGenerationSession {
        val p = params(model)
        val prompt = systemPrompt(model, withTools)
        val session = llama.createSession(p.contextSize, p.temperature, p.topP, p.repetitionPenalty, p.topK, p.minP,
            42, 256, prompt) ?: error("Couldn't start a session.")
        if (withTools) {
            session.setTools(toolsJson)
            preambleCache.apply(session, model.filename, llama.getModelSize(), prompt, toolsJson)
        }
        return session
    }

    private suspend fun send(llama: LlamaModel, session: LlamaGenerationSession, message: String, maxRounds: Int, withTools: Boolean = true): Turn {
        val started = System.currentTimeMillis()
        session.addMessage(message, false)
        var answer = ""
        val ctx = currentCoroutineContext()
        val r = ToolCallLoop(session, testTools, maxToolRounds = maxRounds).run(
            object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { answer = response }
            },
            supportsThinking = llama.supportsThinking(),
            enableThinking = false,
            isActive = { ctx.isActive },
        )
        return Turn(r.rc, r.records, stripThinkTags(answer).trim(), System.currentTimeMillis() - started)
    }

    private suspend fun runTool(llama: LlamaModel, model: ModelInfo, i: Int) {
        started(TestSuite.TOOLS, i)
        if (!llama.supportsToolCalling()) {
            update(TestSuite.TOOLS, i, TestResult(false, detail = "This model can't call tools.", log = "This model can't call tools."))
            return
        }
        // One session, rewound to before its question after each case, so the
        // tool list is processed once; a new one when that isn't possible.
        val session = toolSession?.takeIf { it.rewindToTurn(0, "") >= 0 }
            ?: newSession(llama, model, withTools = true).also { toolSession?.destroy(); toolSession = it }
        val case = TOOL_TEST_CASES[i]
        val turn = send(llama, session, case.prompt, maxRounds = 3)
        val (passed, why) = judge(case, turn)
        update(TestSuite.TOOLS, i, TestResult(passed, detail = "%.1fs. %s".format(turn.ms / 1000.0, why), log = "$why\n\n" + log(turn)))
    }

    /** Frees the tool test's session, so only one session holds memory at a time. */
    private fun dropToolSession() {
        toolSession?.destroy(); toolSession = null
    }

    private suspend fun runChat(llama: LlamaModel, model: ModelInfo) {
        dropToolSession()
        val withTools = llama.supportsToolCalling()
        val session = newSession(llama, model, withTools)
        try {
            for ((i, message) in CHAT_SCRIPT.withIndex()) {
                started(TestSuite.CHAT, i)
                val turn = send(llama, session, message, maxRounds = prefs.maxToolSteps, withTools = withTools)
                val calls = turn.records.joinToString { it.name }.ifEmpty { "no tools" }
                update(TestSuite.CHAT, i, TestResult(
                    passed = turn.rc == 0 && turn.answer.isNotEmpty(),
                    detail = "%.1fs, %s. %s".format(turn.ms / 1000.0, calls, turn.answer.take(300)),
                    log = log(turn) + (if (i == CHAT_SCRIPT.lastIndex) "\n" + session.getReport() else ""),
                ))
            }
        } finally {
            session.destroy()
        }
    }

    private suspend fun runSpeed(llama: LlamaModel, model: ModelInfo, i: Int) {
        started(TestSuite.SPEED, i)
        dropToolSession()
        val session = newSession(llama, model, withTools = false)
        try {
            val turn = send(llama, session, SPEED_CASES[i].second, maxRounds = 0, withTools = false)
            val report = session.getReport()
            val speeds = report.lines().filter { "t/s" in it && "Sampling" !in it }.joinToString(", ") { it.trim() }
            update(TestSuite.SPEED, i, TestResult(
                passed = turn.rc == 0 && turn.answer.isNotEmpty(),
                detail = "%.1fs. %s".format(turn.ms / 1000.0, speeds),
                log = log(turn) + "\n" + report,
            ))
        } finally {
            session.destroy()
        }
    }

    private fun judge(case: ToolTestCase, turn: Turn): Pair<Boolean, String> {
        val records = turn.records
        val answer = turn.answer
        // A non-zero code means the engine gave up (e.g. the prompt didn't fit), not the model.
        if (turn.rc != 0 && records.isEmpty() && answer.isEmpty()) return false to "The engine stopped with error ${turn.rc} before the model answered."
        val called = records.joinToString { it.name }
        if (case.tool == null) {
            return when {
                records.isNotEmpty() -> false to "Called $called, but no tool was needed."
                answer.isEmpty() -> false to "Gave no answer."
                !case.answerOk(answer) -> false to "Didn't do what was asked: " + answer.take(200)
                else -> true to answer.take(300)
            }
        }
        // The call with the right input if there is one (a search before reading a page is fine).
        val calls = records.filter { it.name == case.tool }
        val call = calls.firstOrNull { case.argsOk(args(it)) } ?: calls.firstOrNull()
            ?: return false to (if (records.isEmpty()) "Didn't use a tool; answered on its own: " else "Used $called instead: ") + answer.take(200)
        // Other calls that weren't asked for fail it too; looking up the time first is harmless.
        records.map { it.name }.filter { it != case.tool && it != "get_date_time" }.distinct().takeIf { it.isNotEmpty() }?.let {
            return false to "Right tool, but also called ${it.joinToString()}, which wasn't needed."
        }
        if (!case.argsOk(args(call))) return false to "Right tool, wrong input: ${call.arguments}"
        // Knowing the answer anyway (e.g. 1889) doesn't make a failed tool pass.
        if (call.result.startsWith("Error:")) return false to "The tool failed: " + call.result.lineSequence().first()
        if (answer.isEmpty()) return false to "Called it, then gave no answer."
        val expected = case.expect(call.result)
        fun norm(s: String) = s.lowercase().replace(Regex("[,\\s\\u00A0\\u202F]"), "")
        if (expected != null && norm(expected) !in norm(answer)) return false to "Called it, but the answer doesn't use the result (\"$expected\")."
        return true to "Called with ${call.arguments}. ${answer.take(200)}"
    }

    private fun args(call: ToolCallRecord) = runCatching { JSONObject(call.arguments) }.getOrDefault(JSONObject())

    /** A message's tool calls, results and answer, as Markdown. */
    private fun log(turn: Turn) = buildString {
        for (c in turn.records) {
            appendLine("Tool call `${c.name}` (${c.durationMs} ms)")
            appendLine()
            codeBlock("Arguments", c.arguments, "json")
            codeBlock("Result" + (if (c.name in SIMULATED) " (made up for the test)" else ""), c.result)
        }
        codeBlock("Answer (%.1f s%s)".format(turn.ms / 1000.0, if (turn.rc != 0) ", engine code ${turn.rc}" else ""),
            turn.answer.ifEmpty { "(empty)" })
    }

    /** Everything run so far, with the model, device and settings, as one Markdown file. */
    fun export(): String = buildString {
        val model = tested
        val app = getApplication<Application>()
        val ram = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        appendLine("# LM Playground test results")
        appendLine()
        appendLine("- Date: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        appendLine("- App: ${BuildConfig.VERSION_NAME}")
        appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            "%.1f GB RAM".format(ram.totalMem / 1e9))
        if (model != null) {
            val p = params(model)
            appendLine("- Model: ${model.name} (`${model.filename}`)")
            appendLine("- Settings: context ${p.contextSize}, temperature ${p.temperature}, top-p ${p.topP}, top-k ${p.topK}, " +
                "min-p ${p.minP}, repetition ${p.repetitionPenalty}, seed 42, thinking off")
            appendLine()
            appendLine("## System prompt (tools on)")
            appendLine()
            codeBlock(null, systemPrompt(model, withTools = true))
            appendLine("## Tools offered")
            appendLine()
            codeBlock(null, JSONArray(toolsJson).toString(2), "json")
        }
        for ((suite, list) in _results.value) {
            if (list.all { it.log.isEmpty() }) continue
            appendLine("## ${suite.title}")
            appendLine()
            appendLine(suite.about)
            appendLine()
            suite.labels().zip(list).forEach { (label, r) ->
                if (r.log.isEmpty()) return@forEach
                val mark = when (r.passed) { true -> "✓"; false -> "✗"; null -> "·" }
                appendLine("### $mark ${label.first}")
                appendLine()
                codeBlock("Sent", label.second)
                appendLine(r.log)
            }
        }
    }

    /** The real tools, except the ones with side effects, which get a made-up result. */
    private val testTools = object : ToolCallLoop.ToolExecutor {
        override fun executeToolCalls(toolCallsJson: String): String {
            val calls = JSONArray(toolCallsJson)
            return JSONArray((0 until calls.length()).map { i ->
                val call = calls.getJSONObject(i)
                val name = call.getString("name")
                val content = SIMULATED[name]?.invoke(runCatching { JSONObject(call.getString("arguments")) }.getOrDefault(JSONObject()))
                    ?: runCatching {
                        JSONArray(registry.executeToolCalls(JSONArray().put(call).toString())).getJSONObject(0).getString("content")
                    }.getOrElse { "Error: " + (it.message ?: "The $name tool failed.") }
                JSONObject().put("id", call.getString("id")).put("name", name).put("content", content)
            }).toString()
        }
        override fun cancelInFlight() = registry.cancelInFlight()
    }

    private companion object {
        val SIMULATED: Map<String, (JSONObject) -> String> = mapOf(
            "set_alarm" to { a -> "Alarm" + a.optString("label").let { if (it.isBlank()) "" else " \"$it\"" } + " set for ${a.optString("time")}." },
            "remember" to { _ -> "Saved as fact 1." },
            "forget" to { a -> "Fact ${a.optString("number", "1")} deleted." },
        )
    }
}

class ModelTestsFragment : Fragment() {

    private val viewModel: ModelTestsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                PlaygroundTheme {
                    ModelTestsScreen(viewModel, onBack = { findNavController().popBackStack() })
                }
            }
        }
}

@Composable
private fun ModelTestsScreen(viewModel: ModelTestsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<ModelInfo?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    // The location tool really runs, so ask for its permission first; the
    // run starts after the answer either way.
    var afterAsking by remember { mutableStateOf<(() -> Unit)?>(null) }
    val (locationGranted, askLocation) = rememberLocationPermission {
        afterAsking?.invoke()
        afterAsking = null
    }
    fun start(suites: List<TestSuite>, only: Int? = null) {
        val model = chosen ?: return
        val run = { viewModel.run(model, suites, only) }
        val needsLocation = TestSuite.CHAT in suites ||
            (TestSuite.TOOLS in suites && (only == null || TOOL_TEST_CASES[only].tool == "get_location"))
        if (needsLocation && !locationGranted && askLocation != null) {
            afterAsking = run
            askLocation()
        } else {
            run()
        }
    }
    val exportable = results.values.any { list -> list.any { it.log.isNotEmpty() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.testing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(enabled = exportable && !running, onClick = {
                        scope.launch {
                            try {
                                shareMarkdown(context, "tests", viewModel.export(), "Export test results")
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: e.javaClass.simpleName, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Icon(Icons.Outlined.Share, contentDescription = "Export test results") }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(
                    "Runs fixed tests on a downloaded model with the same system prompt, tools and settings a chat " +
                        "uses. Set alarm and Memory are simulated; other tools really run. The model loads " +
                        "alongside any chat model (unload that first if memory is tight). When done, tap share " +
                        "to export everything as one file.",
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
                        onClick = { if (running) viewModel.stop() else start(TestSuite.entries) },
                        enabled = chosen != null,
                        modifier = Modifier.padding(start = 12.dp),
                    ) { Text(if (running) "Stop" else "Run all") }
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            for (suite in TestSuite.entries) {
                item {
                    HorizontalDivider()
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(suite.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { start(listOf(suite)) }, enabled = chosen != null && !running) { Text("Run") }
                    }
                    Text(suite.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(suite.labels()) { i, (label, prompt) ->
                    // The chat runs as one conversation, so only the other groups run one case.
                    val runOne = if (suite == TestSuite.CHAT) null else ({ start(listOf(suite), i) })
                    TestRow(label, prompt, results.getValue(suite)[i], canRun = chosen != null && !running, onRun = runOne)
                }
            }
        }
    }
}

@Composable
private fun TestRow(label: String, prompt: String, r: TestResult, canRun: Boolean, onRun: (() -> Unit)?) {
    val failed = r.passed == false
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mark = when { r.running -> "…"; r.passed == true -> "✓"; failed -> "✗"; else -> "·" }
            Text(mark, color = if (failed) MaterialTheme.colorScheme.error else faint, modifier = Modifier.width(24.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onRun != null) TextButton(onClick = onRun, enabled = canRun) { Text("Run") }
        }
        Column(Modifier.padding(start = 24.dp)) {
            Text("“${prompt.lineSequence().first()}”", style = MaterialTheme.typography.bodySmall, color = faint,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
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

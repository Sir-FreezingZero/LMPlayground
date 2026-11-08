package com.druk.lmplayground.conversation

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A chat as one Markdown file, for debugging and keeping: the settings, the
 * system prompt and tools exactly as sent, then every message with its
 * thinking, tool calls and results in order. Labels stay in English, like a
 * log, so exports from any language read the same.
 */
object ChatExport {

    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun markdown(
        title: String,
        modelName: String?,
        modelFilename: String?,
        params: GenerationParams?,
        thinking: Boolean,
        systemPrompt: String,
        toolsJson: String?,
        promptStart: String?,
        messages: List<Message>,
        /** What was really sent for a user message, when it differs from what's shown. */
        sentContent: (Message) -> String?,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("- Exported: ${time.format(Date())}")
        appendLine("- Model: ${modelName ?: "none loaded"}${modelFilename?.let { " (`$it`)" } ?: ""}")
        params?.let {
            appendLine("- Parameters: context ${it.contextSize}, temperature ${it.temperature}, top-p ${it.topP}, " +
                "top-k ${it.topK}, min-p ${it.minP}, repetition penalty ${it.repetitionPenalty}, seed ${it.seed}")
        }
        appendLine("- Thinking: ${if (thinking) "on" else "off"}")
        appendLine()

        section("System prompt (as sent)", systemPrompt.ifBlank { "(empty)" }, "text")
        if (!toolsJson.isNullOrBlank() && toolsJson != "[]") section("Tools offered", pretty(toolsJson), "json")
        // The engine's own rendering of the start of the prompt: the chat
        // format's special tokens around the system prompt and tools.
        if (!promptStart.isNullOrBlank()) section("Prompt start (as the model's chat format renders it)", promptStart, "text")

        appendLine("## Conversation")
        appendLine()
        for (m in messages) {
            appendLine("### ${m.author} · ${time.format(Date(m.timestamp))}")
            appendLine()
            if (m.imageUri != null || m.imagePath != null) appendLine("_[image attached]_\n")
            val sent = if (m.author == "User") sentContent(m) else null
            if (sent != null) {
                codeBlock("Shown", m.content, "text")
                codeBlock("Sent to the model (with document excerpts)", sent, "text")
            }
            m.toolCalls?.forEach { call ->
                if (call.precedingThinking.isNotBlank()) codeBlock("Written before the tool call", call.precedingThinking, "text")
                appendLine("**Tool call: `${call.name}`** (${call.durationMs} ms)")
                appendLine()
                codeBlock("Arguments", pretty(call.arguments), "json")
                codeBlock("Result", call.result, "text")
            }
            if (sent == null && (m.content.isNotEmpty() || m.toolCalls.isNullOrEmpty())) {
                codeBlock(if (m.toolCalls.isNullOrEmpty()) null else "Reply", m.content, "text")
            }
            if (m.responseTokens > 0 || m.thinkingTokens > 0) {
                appendLine("_${m.thinkingTokens} thinking tokens, ${m.responseTokens} reply tokens, " +
                    "${"%.1f".format(Locale.US, m.responseDurationSeconds)} s_")
                appendLine()
            }
        }
    }

    private fun StringBuilder.section(heading: String, body: String, lang: String) {
        appendLine("## $heading")
        appendLine()
        codeBlock(null, body, lang)
    }

    private fun pretty(json: String): String = try {
        val t = json.trim()
        if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
    } catch (_: Exception) {
        json
    }
}

/** A labelled Markdown code block; the fence grows past any backticks inside. */
internal fun StringBuilder.codeBlock(label: String?, body: String, lang: String = "text") {
    label?.let { appendLine("$it:") }
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0) + 1))
    appendLine("$fence$lang")
    appendLine(body)
    appendLine(fence)
    appendLine()
}

/**
 * Writes [text] to `cache/exports/<name>_<time>.md` (only the newest export
 * is kept) and opens the share sheet for it.
 */
suspend fun shareMarkdown(context: Context, name: String, text: String, chooserTitle: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, name + "_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".md").apply { writeText(text) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

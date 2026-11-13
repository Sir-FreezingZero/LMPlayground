package com.druk.agent.llm

import com.druk.agent.json.Json

data class ParsedAction(val tool: String, val args: Map<String, String>, val thought: String = "")

/**
 * Lenient parser for the small-model action format:
 *
 * ```
 * THOUGHT: one line (optional)
 * ACTION: write_file
 * path: rle.js
 * content:
 * ```js
 * ...multi-line...
 * ```
 * ```
 *
 * A fenced block belongs to the `key:` line right before it (or to the
 * tool's default block argument). Also accepts a JSON object
 * `{"action": ..., "args": {...}}` / `{"name": ..., "arguments": {...}}`,
 * since some models fall back to tool-call habits. `<think>` blocks are
 * stripped first.
 */
object ActionParser {

    private val KEY_LINE = Regex("""^\s*\**([A-Za-z_][A-Za-z0-9_]*)\**\s*[:=]\s*(.*)$""")

    fun parse(raw: String, knownTools: Set<String>, defaultBlockArg: (String) -> String = { "content" }): ParsedAction? {
        val text = stripThinking(raw).trim()
        parseJson(text, knownTools)?.let { return it }

        val lines = text.lines()
        val actionIdx = lines.indexOfFirst { Regex("""^\s*\**(ACTION|Action|action|TOOL|Tool)\**\s*[:=]""").containsMatchIn(it) }
        if (actionIdx < 0) return null
        val tool = normalizeTool(lines[actionIdx].substringAfter(':', lines[actionIdx].substringAfter('=')).trim(), knownTools) ?: return null
        val thought = lines.take(actionIdx).firstOrNull { it.trim().uppercase().startsWith("THOUGHT") }
            ?.substringAfter(':')?.trim().orEmpty()

        val args = LinkedHashMap<String, String>()
        var i = actionIdx + 1
        var pendingKey: String? = null
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                val key = pendingKey ?: defaultBlockArg(tool)
                if (!args.containsKey(key) || args[key].isNullOrBlank()) args[key] = body.toString().trimEnd('\n')
                pendingKey = null
                i++
                continue
            }
            if (Regex("""^\s*\**(ACTION|Action)\**\s*:""").containsMatchIn(line)) break // a second action: ignore
            val m = KEY_LINE.find(line)
            if (m != null) {
                val key = m.groupValues[1].lowercase()
                val value = m.groupValues[2].trim()
                if (value.isEmpty()) {
                    pendingKey = key
                    // Unfenced multi-line value: take following lines until the next key line or fence.
                    val buf = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && KEY_LINE.find(lines[j]) == null && !lines[j].trim().startsWith("```")) {
                        buf.append(lines[j]).append('\n'); j++
                    }
                    if (buf.isNotBlank() && (j >= lines.size || !lines[j].trim().startsWith("```"))) {
                        args[key] = buf.toString().trimEnd('\n'); pendingKey = null; i = j; continue
                    }
                } else {
                    args[key] = unquote(value.trim { it == '*' || it.isWhitespace() })
                    pendingKey = null
                }
            }
            i++
        }
        return ParsedAction(tool, args, thought)
    }

    fun stripThinking(s: String): String {
        var t = s.replace(Regex("(?s)<think>.*?</think>"), "")
        // An unterminated think block (budget cut) — keep what follows nothing.
        val open = t.indexOf("<think>")
        if (open >= 0) t = t.substring(0, open)
        return t
    }

    private fun unquote(v: String): String {
        val t = v.trim()
        if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))) return t.substring(1, t.length - 1)
        if (t.length >= 2 && t.startsWith('`') && t.endsWith('`') && !t.startsWith("```")) return t.substring(1, t.length - 1)
        return t
    }

    private fun normalizeTool(name: String, known: Set<String>): String? {
        val n = name.trim { it.isWhitespace() || it in "`*\"'.:" }.substringBefore('(').trim { it.isWhitespace() || it in "`*\"'" }.lowercase().replace(' ', '_').replace('-', '_')
        if (n in known) return n
        return known.firstOrNull { n.startsWith(it) || it.startsWith(n) && n.length >= 4 }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(text: String, known: Set<String>): ParsedAction? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        if (Regex("""(?m)^\s*\**ACTION\**\s*:""").containsMatchIn(text.substring(0, start))) return null
        val obj = try { Json.parseObject(text.substring(start, end + 1)) } catch (_: Exception) { return null }
        val name = (obj["action"] ?: obj["tool"] ?: obj["name"]) as? String ?: return null
        val tool = normalizeTool(name, known) ?: return null
        val rawArgs = obj["args"] ?: obj["arguments"] ?: obj["parameters"]
        val argMap: Map<String, Any?> = when (rawArgs) {
            is Map<*, *> -> rawArgs as Map<String, Any?>
            is String -> try { Json.parseObject(rawArgs) } catch (_: Exception) { emptyMap() }
            else -> obj.filterKeys { it !in setOf("action", "tool", "name", "thought") }
        }
        return ParsedAction(tool, argMap.mapValues { (_, v) -> if (v is String) v else Json.stringify(v) }, obj["thought"] as? String ?: "")
    }
}

/** Parsed output of a planning call. */
data class PlanOutput(val strategy: String, val tasks: List<PlannedTask>)

data class PlannedTask(val title: String, val description: String, val priority: Int, val dependsOn: List<String>)

/**
 * Parses
 * ```
 * STRATEGY: ...
 * TASK: title | description | priority | depends
 * ```
 * Depends entries are either "1"/"#1" (another TASK in this plan, 1-based)
 * or an existing task id like "t7". Also tolerates numbered/bulleted tasks
 * without the TASK: prefix when there are no TASK lines at all.
 */
object PlanParser {
    fun parse(raw: String, maxTasks: Int = 6): PlanOutput {
        val text = ActionParser.stripThinking(raw)
        val lines = text.lines().map { it.trim() }
        val strategy = lines.firstOrNull { it.uppercase().trimStart('*').startsWith("STRATEGY") }
            ?.substringAfter(':')?.trim()?.trim('*')?.trim().orEmpty()
        var taskLines = lines.filter { it.uppercase().trimStart('*', '-', ' ').startsWith("TASK") && it.contains(':') }
            .map { it.substringAfter(':').trim() }
        if (taskLines.isEmpty()) {
            taskLines = lines.filter { Regex("""^(\d+[.)]|[-*])\s+\S""").containsMatchIn(it) }
                .map { it.replaceFirst(Regex("""^(\d+[.)]|[-*])\s+"""), "") }
        }
        val tasks = taskLines.take(maxTasks).mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            val title = parts.getOrNull(0)?.trim('*', '"')?.takeIf { it.length >= 3 } ?: return@mapNotNull null
            val desc = parts.getOrNull(1).orEmpty()
            val prio = parts.getOrNull(2)?.let { Regex("\\d").find(it)?.value?.toIntOrNull() } ?: 3
            val deps = parts.getOrNull(3)?.let { d ->
                Regex("""t\d+|#?\d+""").findAll(d).map { it.value.removePrefix("#") }.toList()
            } ?: emptyList()
            PlannedTask(title, desc, prio.coerceIn(1, 5), deps)
        }
        return PlanOutput(strategy, tasks)
    }
}

data class Reflection(val whyFailed: String, val newApproach: String)

object ReflectionParser {
    fun parse(raw: String): Reflection? {
        val text = ActionParser.stripThinking(raw)
        fun field(name: String) = text.lines().firstOrNull { it.trim().uppercase().trimStart('*').startsWith(name) }
            ?.substringAfter(':')?.trim()?.trim('*')?.trim().orEmpty()
        val why = field("WHY")
        val next = field("NEW_APPROACH").ifEmpty { field("NEW APPROACH") }.ifEmpty { field("APPROACH") }
        if (next.length < 8) return null
        return Reflection(why, next)
    }
}

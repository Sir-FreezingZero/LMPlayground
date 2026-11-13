package com.druk.lmplayground.agent

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.druk.agent.tools.CodeResult
import com.druk.agent.tools.CodeRunner
import com.druk.agent.tools.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * On-device code execution for the agent: a fresh V8 isolate per run via
 * androidx.javascriptengine — no filesystem, no network, no DOM, capped
 * heap, wall-clock timeout (the isolate is torn down on expiry).
 *
 * The isolate can't touch the disk, so workspace files are passed *in*
 * (those whose path appears literally in the code) and files the program
 * writes are passed *out* and persisted by the agent's tool layer, still
 * confined to the project workspace. Helpers mirror the desktop runner:
 * `print(...)`, `readFile(path)`, `writeFile(path, text)`, `listFiles()`.
 *
 * Python isn't available on stock Android without bundling an interpreter
 * (e.g. Chaquopy); see DEVLOG.md. The agent is told which languages exist.
 */
class SandboxJsRunner(private val context: Context, private val heapBytes: Long = 64L * 1024 * 1024) : CodeRunner {

    override val languages: Set<String> = setOf("js")

    override suspend fun run(language: String, code: String, workspace: Workspace, timeoutMs: Long): CodeResult {
        if (language != "js") return CodeResult(false, "", "Only JavaScript runs on this device (language 'js'). Rewrite the program in JavaScript.")
        return runInterruptible(Dispatchers.IO) { runBlocking(code, workspace, timeoutMs) }
    }

    private fun runBlocking(code: String, workspace: Workspace, timeoutMs: Long): CodeResult {
        if (!JavaScriptSandbox.isSupported()) return CodeResult(false, "", "JavaScript sandbox is not supported on this device (needs an up-to-date Android System WebView)")
        val started = System.currentTimeMillis()
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).get(10, TimeUnit.SECONDS)
        try {
            val largeInput = sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
            val budget = if (largeInput) 8L * 1024 * 1024 else 600L * 1024
            val files = JSONObject()
            var used = code.length.toLong()
            for ((path, size) in workspace.list()) {
                if (!code.contains(path)) continue
                if (used + size > budget) continue
                runCatching { files.put(path, workspace.read(path)); used += size }
            }
            val params = IsolateStartupParameters()
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) params.setMaxHeapSizeBytes(heapBytes)
            val isolate = sandbox.createIsolate(params)
            try {
                val script = buildScript(files.toString(), JSONObject.quote(code))
                val raw = try {
                    isolate.evaluateJavaScriptAsync(script).get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    return CodeResult(false, "", "TIMEOUT after ${timeoutMs}ms", timedOut = true, durationMs = System.currentTimeMillis() - started)
                }
                val obj = JSONObject(raw)
                val out = obj.optString("out")
                val err = if (obj.isNull("error")) "" else obj.optString("error")
                val written = LinkedHashMap<String, String>()
                obj.optJSONObject("files")?.let { f -> f.keys().forEach { k -> written[k] = f.optString(k) } }
                return CodeResult(err.isEmpty(), out.take(64_000), err.take(4_000), writtenFiles = written, durationMs = System.currentTimeMillis() - started)
            } catch (e: Exception) {
                // Heap exhaustion / isolate crash / evaluation failure.
                return CodeResult(false, "", (e.cause?.message ?: e.message ?: e.javaClass.simpleName).take(2000), durationMs = System.currentTimeMillis() - started)
            } finally {
                runCatching { isolate.close() }
            }
        } finally {
            runCatching { sandbox.close() }
        }
    }

    companion object {
        /** Splices by position, so placeholder text inside files or code can't corrupt the script. */
        fun buildScript(filesJson: String, codeJson: String): String {
            val a = PRELUDE.indexOf("__FILES_JSON__")
            val b = PRELUDE.indexOf("__CODE_JSON__")
            return PRELUDE.substring(0, a) + filesJson + PRELUDE.substring(a + "__FILES_JSON__".length, b) + codeJson + PRELUDE.substring(b + "__CODE_JSON__".length)
        }

        private val PRELUDE = """
(function(){
const __FILES = __FILES_JSON__;
const __WRITES = {};
const __OUT = [];
let __size = 0;
function __fmt(x){ return typeof x === 'string' ? x : (x === undefined ? 'undefined' : JSON.stringify(x)); }
function print(){ const s = Array.prototype.map.call(arguments, __fmt).join(' '); __size += s.length; if (__size < 200000) __OUT.push(s); }
const console = { log: print, info: print, warn: print, error: print };
function readFile(p){ p = String(p); if (Object.prototype.hasOwnProperty.call(__WRITES, p)) return __WRITES[p]; if (Object.prototype.hasOwnProperty.call(__FILES, p)) return __FILES[p]; throw new Error('no such file: ' + p + ' (only workspace files named literally in the code are available)'); }
function writeFile(p, s){ p = String(p); if (p.startsWith('/') || p.split('/').indexOf('..') >= 0) throw new Error('path must stay inside the workspace: ' + p); __WRITES[p] = String(s); }
function listFiles(){ return Object.keys(Object.assign({}, __FILES, __WRITES)); }
let __err = null;
try {
  const fn = new Function('print', 'console', 'readFile', 'writeFile', 'listFiles', __CODE_JSON__);
  const r = fn(print, console, readFile, writeFile, listFiles);
  if (r !== undefined) print(r);
} catch (e) { __err = String((e && e.stack) || e); }
return JSON.stringify({ out: __OUT.join('\n'), files: __WRITES, error: __err });
})()
""".trimIndent()
    }
}

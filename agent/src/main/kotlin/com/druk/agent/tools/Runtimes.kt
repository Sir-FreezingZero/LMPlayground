package com.druk.agent.tools

import java.io.File
import java.util.concurrent.TimeUnit

data class CodeResult(
    val ok: Boolean,
    val stdout: String,
    val stderr: String = "",
    val timedOut: Boolean = false,
    /** Files the sandbox asked to write (sandboxes without disk access return them here). */
    val writtenFiles: Map<String, String> = emptyMap(),
    val durationMs: Long = 0,
)

/**
 * Executes model-written code. Implementations must enforce a timeout and
 * must not grant access beyond the project workspace.
 *
 * - Android: V8 isolate via androidx.javascriptengine (no filesystem, no
 *   network, capped heap) — see the app's SandboxJsRunner.
 * - Desktop/harness/tests: [ProcessCodeRunner] (python3 / node).
 */
interface CodeRunner {
    val languages: Set<String>
    suspend fun run(language: String, code: String, workspace: Workspace, timeoutMs: Long): CodeResult
}

data class SearchHit(val title: String, val url: String, val snippet: String)

/** Web access. Implementations should return errors as exceptions; the tool layer records them. */
interface WebAccess {
    suspend fun search(query: String): List<SearchHit>
    suspend fun fetch(url: String): String
}

/**
 * Runs python3 / node as a child process in the workspace directory.
 *
 * Restrictions (defense in depth — a child process on a desktop is not a
 * hard security boundary; the on-device runner is a V8 isolate):
 * - wall-clock timeout, process tree destroyed on expiry;
 * - output capped at [maxOutputChars];
 * - empty environment except PATH/HOME=workspace/LANG;
 * - Python: RLIMIT_AS / RLIMIT_CPU / RLIMIT_FSIZE / RLIMIT_NPROC set before
 *   user code, and an audit hook refusing file opens outside the workspace,
 *   sockets, and subprocess/exec;
 * - Node: --max-old-space-size, and the same helper API as the Android
 *   sandbox (readFile/writeFile/print) restricted to the workspace.
 */
class ProcessCodeRunner(
    private val python: String? = which("python3"),
    private val node: String? = which("node"),
    private val memoryMb: Int = 512,
    private val maxOutputChars: Int = 64_000,
) : CodeRunner {

    override val languages: Set<String> = buildSet {
        if (python != null) add("python")
        if (node != null) add("js")
    }

    override suspend fun run(language: String, code: String, workspace: Workspace, timeoutMs: Long): CodeResult =
        kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { runBlocking(language, code, workspace, timeoutMs) }

    private fun runBlocking(language: String, code: String, workspace: Workspace, timeoutMs: Long): CodeResult {
        val dir = workspace.root.canonicalFile
        val scriptDir = File(dir, ".run").apply { mkdirs() }
        val cmd: List<String> = when (language) {
            "python" -> {
                val f = File(scriptDir, "main.py")
                f.writeText(pythonPrelude(dir.path, timeoutMs) + "\n" + code)
                listOf(python ?: error("python unavailable"), "-I", "-S", "-B", f.path)
            }
            "js" -> {
                val f = File(scriptDir, "main.js")
                f.writeText(NODE_PRELUDE + "\n;(function(){\n" + code + "\n})();\n__flush();\n")
                listOf(node ?: error("node unavailable"), "--max-old-space-size=$memoryMb", f.path)
            }
            else -> return CodeResult(false, "", "unsupported language '$language' (available: ${languages.joinToString()})")
        }
        val started = System.currentTimeMillis()
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(false)
        pb.environment().clear()
        pb.environment()["PATH"] = "/usr/bin:/bin:/usr/local/bin"
        pb.environment()["HOME"] = dir.path
        pb.environment()["LANG"] = "C.UTF-8"
        val proc = pb.start()
        proc.outputStream.close()
        val out = StreamCollector(proc.inputStream, maxOutputChars).also { it.start() }
        val err = StreamCollector(proc.errorStream, maxOutputChars / 4).also { it.start() }
        val finished = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            throw e
        }
        if (!finished) {
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            proc.waitFor(2, TimeUnit.SECONDS)
        }
        out.join(2000); err.join(2000)
        scriptDir.deleteRecursively()
        val dur = System.currentTimeMillis() - started
        return if (!finished) CodeResult(false, out.text(), err.text() + "\nTIMEOUT after ${timeoutMs}ms", timedOut = true, durationMs = dur)
        else CodeResult(proc.exitValue() == 0, out.text(), err.text(), durationMs = dur)
    }

    private class StreamCollector(private val input: java.io.InputStream, private val max: Int) : Thread() {
        private val sb = StringBuilder()
        private var truncated = false
        init { isDaemon = true }
        override fun run() {
            val reader = input.bufferedReader()
            val buf = CharArray(4096)
            while (true) {
                val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                if (n < 0) break
                synchronized(sb) {
                    if (sb.length < max) sb.append(buf, 0, minOf(n, max - sb.length)) else truncated = true
                }
            }
        }
        fun text(): String = synchronized(sb) { sb.toString() + if (truncated) "\n…[output truncated]" else "" }
    }

    private fun pythonPrelude(dir: String, timeoutMs: Long): String {
        val cpu = ((timeoutMs / 1000) + 1).coerceAtLeast(1)
        return """
import sys, os, resource
def __lim(r, v):
    try: resource.setrlimit(r, (v, v))
    except Exception: pass
__lim(resource.RLIMIT_AS, ${memoryMb}*1024*1024)
__lim(resource.RLIMIT_CPU, $cpu)
__lim(resource.RLIMIT_FSIZE, 32*1024*1024)
try: __lim(resource.RLIMIT_NPROC, 0)
except Exception: pass
__ROOT = os.path.realpath(${pyQuote(dir)})
def __audit(event, args):
    if event == 'open':
        p = args[0]
        if isinstance(p, int): return
        rp = os.path.realpath(p if os.path.isabs(str(p)) else os.path.join(os.getcwd(), str(p)))
        mode = args[1] if len(args) > 1 else 'r'
        writing = isinstance(mode, str) and any(c in mode for c in 'wax+')
        if not (rp == __ROOT or rp.startswith(__ROOT + os.sep)):
            if writing or not (rp.startswith(sys.prefix) or rp.startswith(sys.base_prefix) or rp.startswith('/usr/lib') or rp.startswith('/dev/null')):
                raise PermissionError('sandbox: access outside workspace denied: ' + str(p))
    elif event in ('socket.connect', 'socket.bind', 'subprocess.Popen', 'os.system', 'os.exec', 'os.posix_spawn', 'os.fork', 'os.kill', 'shutil.rmtree'):
        raise PermissionError('sandbox: ' + event + ' is not allowed')
sys.addaudithook(__audit)
del __audit, __lim
""".trimIndent()
    }

    private fun pyQuote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        fun which(bin: String): String? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .plus(listOf("/usr/bin", "/usr/local/bin", "/bin"))
            .map { File(it, bin) }.firstOrNull { it.canExecute() }?.path

        private val NODE_PRELUDE = """
const __fs = require('fs'); const __path = require('path'); const __root = __fs.realpathSync(process.cwd());
function __chk(p){ const r = __path.resolve(__root, String(p)); if (r !== __root && !r.startsWith(__root + __path.sep)) throw new Error('sandbox: path outside workspace: ' + p); return r; }
globalThis.readFile = (p) => __fs.readFileSync(__chk(p), 'utf8');
globalThis.writeFile = (p, s) => { const r = __chk(p); __fs.mkdirSync(__path.dirname(r), {recursive:true}); __fs.writeFileSync(r, String(s)); };
globalThis.listFiles = () => __fs.readdirSync(__root);
globalThis.print = (...a) => console.log(...a.map(x => typeof x === 'string' ? x : JSON.stringify(x)));
globalThis.__flush = () => {};
{ const M = require('module'); const orig = M.prototype.require; const banned = new Set(['fs','fs/promises','child_process','net','http','https','http2','dgram','dns','tls','worker_threads','cluster','inspector','vm','v8']);
  M.prototype.require = function(id){ const n = String(id).replace(/^node:/,''); if (banned.has(n)) throw new Error('sandbox: module ' + n + ' is not allowed; use readFile/writeFile'); return orig.apply(this, arguments); }; }
""".trimIndent()
    }
}

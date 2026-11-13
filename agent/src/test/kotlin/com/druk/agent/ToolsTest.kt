package com.druk.agent

import com.druk.agent.model.*
import com.druk.agent.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ToolsTest {
    private val registry = ToolRegistry.standard()

    private fun ctx(runner: CodeRunner? = FakeRunner(), web: WebAccess? = FakeWeb(), p: Project? = null): ToolContext {
        val ws = Workspace(tempDir("ws"))
        val proj = p ?: Project("p1", "t", "o", tasks = listOf(Task("t1", "task", status = TaskStatus.RUNNING)),
            criteria = listOf(CompletionCriterion("c1", "has result")), stepCount = 1)
        return ToolContext(proj, "t1", ws, runner, web, { it == 1L })
    }

    private fun exec(c: ToolContext, tool: String, vararg args: Pair<String, String>) = runBlocking { registry.execute(tool, mapOf(*args), c) }

    @Test fun workspaceRefusesEscapes() {
        val c = ctx()
        for (bad in listOf("../x", "/etc/passwd", "a/../../x", "workspace/../../y")) {
            val r = exec(c, "write_file", "path" to bad, "content" to "x")
            assertFalse(bad, r.ok)
            assertTrue(r.output, r.output.contains("not allowed") || r.output.contains("escapes"))
        }
        val r = exec(c, "read_file", "path" to "../../../../etc/hostname")
        assertFalse(r.ok)
    }

    @Test fun writeReadEditAndDuplicateDetection() {
        val c = ctx()
        val w = exec(c, "write_file", "path" to "src/a.py", "content" to "def f(x):\n    return x * 2\n")
        assertTrue(w.ok); assertEquals(Effect.WRITE_NEW, w.effect)
        assertEquals(1, c.project.artifacts.size)
        val again = exec(c, "write_file", "path" to "src/a.py", "content" to "def f(x):\n    return x * 2\n\n")
        assertEquals(Effect.WRITE_DUP, again.effect)
        val e = exec(c, "edit_file", "path" to "src/a.py", "find" to "x * 2", "replace" to "x * 3")
        assertTrue(e.ok)
        assertTrue(exec(c, "read_file", "path" to "src/a.py").output.contains("x * 3"))
        assertFalse(exec(c, "edit_file", "path" to "src/a.py", "find" to "nope", "replace" to "y").ok)
        assertEquals(1, c.project.artifacts.count { it.path == "src/a.py" })
    }

    @Test fun missingFileAndMissingArgumentsAreStructuredErrors() {
        val c = ctx()
        val r = exec(c, "read_file", "path" to "nope.txt")
        assertFalse(r.ok); assertEquals(Effect.ERROR, r.effect); assertTrue(r.output.contains("no such file"))
        val m = exec(c, "write_file", "content" to "x")
        assertFalse(m.ok); assertTrue(m.output.contains("missing argument 'path'"))
        val u = exec(c, "teleport")
        assertFalse(u.ok); assertTrue(u.output.contains("unknown tool"))
    }

    @Test fun experimentRecordsMetricsAndRepeatsAreNotNewEvidence() {
        val c = ctx()
        val r = exec(c, "run_experiment", "name" to "rle", "code" to "metric ratio 0.5\nmetric ms 12")
        assertTrue(r.output, r.ok); assertEquals(Effect.EXPERIMENT, r.effect)
        val e = c.project.experiments.single()
        assertEquals(mapOf("ratio" to 0.5, "ms" to 12.0), e.metrics)
        assertTrue(e.succeeded)
        val again = exec(c, "run_experiment", "name" to "rle", "code" to "metric ratio 0.5\nmetric ms 12")
        assertEquals(Effect.EXPERIMENT_REPEAT, again.effect)
        val noMetrics = exec(c, "run_experiment", "name" to "x", "code" to "print hi")
        assertFalse(noMetrics.ok); assertTrue(noMetrics.output.contains("no METRIC"))
        val failed = exec(c, "run_experiment", "name" to "y", "code" to "fail")
        assertEquals(Effect.CODE_FAIL, failed.effect)
        assertFalse(c.project.experiments.last().succeeded)
    }

    @Test fun sandboxReturnedFilesArePersistedAsArtifacts() {
        val c = ctx()
        val r = exec(c, "run_code", "code" to "write out/result.txt 42")
        assertTrue(r.ok)
        assertEquals("42", c.workspace.read("out/result.txt"))
        assertTrue(c.project.artifacts.any { it.path == "out/result.txt" })
    }

    @Test fun completeTaskRequiresValidEvidence() {
        val c = ctx()
        val rejected = exec(c, "complete_task", "summary" to "done!", "evidence" to "")
        assertEquals(Effect.REJECTED, rejected.effect)
        assertEquals(TaskStatus.RUNNING, c.project.task("t1")!!.status)
        assertEquals(Effect.REJECTED, exec(c, "complete_task", "summary" to "done", "evidence" to "e99").effect)
        exec(c, "run_experiment", "name" to "x", "code" to "metric r 1")
        val eid = c.project.experiments.last().id
        val ok = exec(c, "complete_task", "summary" to "done", "evidence" to eid)
        assertEquals(Effect.TASK_DONE, ok.effect)
        assertEquals(TaskStatus.DONE, c.project.task("t1")!!.status)
    }

    @Test fun artifactEvidenceGoesStaleWhenFileChanges() {
        val c = ctx()
        exec(c, "write_file", "path" to "a.txt", "content" to "version one of the file")
        val aid = c.project.artifacts.single().id
        assertTrue(c.evidence().check(listOf(aid)).valid.isNotEmpty())
        File(c.workspace.root, "a.txt").writeText("tampered")
        val r = c.evidence().check(listOf(aid, "a.txt"))
        assertTrue(r.valid.isEmpty())
        assertTrue(r.invalid.all { it.second.contains("changed") })
        File(c.workspace.root, "a.txt").delete()
        assertTrue(c.evidence().check(listOf(aid)).invalid.single().second.contains("missing"))
    }

    @Test fun findingsWithoutEvidenceAreDowngradedAndDiscoveriesRejected() {
        val c = ctx()
        assertEquals(Effect.REJECTED, exec(c, "record_finding", "text" to "LZ beats everything", "discovery" to "yes").effect)
        val n = exec(c, "record_finding", "text" to "LZ77 uses a sliding window")
        assertEquals(Effect.NOTE, n.effect)
        exec(c, "run_experiment", "name" to "x", "code" to "metric r 1")
        val eid = c.project.experiments.last().id
        assertEquals(Effect.FINDING, exec(c, "record_finding", "text" to "Ratio 1 for x", "evidence" to eid).effect)
        assertEquals(Effect.FINDING_DUP, exec(c, "record_finding", "text" to "Ratio 1 for x", "evidence" to eid).effect)
    }

    @Test fun finishProjectIsVerified() {
        val c = ctx()
        val r = exec(c, "finish_project", "summary" to "all done")
        assertEquals(Effect.REJECTED, r.effect)
        assertTrue(r.output.contains("c1 not satisfied"))
        assertEquals(ProjectStatus.ACTIVE, c.project.status)
        exec(c, "run_experiment", "name" to "x", "code" to "metric r 1")
        assertEquals(Effect.REJECTED, exec(c, "mark_criterion", "id" to "c1", "evidence" to "nonsense").effect)
        assertEquals(Effect.CRITERION, exec(c, "mark_criterion", "id" to "c1", "evidence" to c.project.experiments.last().id).effect)
        assertEquals(Effect.PROJECT_DONE, exec(c, "finish_project", "summary" to "done").effect)
        assertEquals(ProjectStatus.COMPLETED, c.project.status)
        assertTrue(c.workspace.exists("REPORT.md"))
    }

    @Test fun failedWebRequestsAreRecordedNotThrown() {
        val c = ctx(web = FakeWeb(failing = true))
        val r = exec(c, "web_search", "query" to "huffman")
        assertFalse(r.ok); assertTrue(r.output.contains("network unreachable"))
        assertFalse(exec(c, "read_url", "url" to "https://x.org").ok)
        val off = ctx(web = null)
        assertTrue(exec(off, "web_search", "query" to "q").output.contains("disabled"))
    }

    @Test fun readUrlCachesPagesInWorkspace() {
        val web = FakeWeb()
        val c = ctx(web = web)
        assertTrue(exec(c, "read_url", "url" to "https://a.org/p").ok)
        web.failing = true
        assertTrue("second read served from cache", exec(c, "read_url", "url" to "https://a.org/p").ok)
    }

    @Test fun compareRanksExperiments() {
        val c = ctx()
        exec(c, "run_experiment", "name" to "a", "code" to "metric ratio 0.6")
        exec(c, "run_experiment", "name" to "b", "code" to "metric ratio 0.4")
        val r = exec(c, "compare_experiments", "metric" to "ratio", "goal" to "min")
        val b = c.project.experiments.first { it.name == "b" }.id
        assertTrue(r.output, r.output.contains("best: $b b"))
    }

    @Test fun recallFindsArchivedMemory() {
        val c = ctx()
        c.project = c.project.copy(memory = listOf(MemoryEntry("m1", MemoryKind.FINDING, "Burrows-Wheeler helps text", archived = true)))
        assertTrue(exec(c, "recall", "query" to "burrows wheeler").output.contains("m1"))
    }

    // ---- real process sandbox ----

    private fun python(): ProcessCodeRunner {
        val r = ProcessCodeRunner()
        assumeTrue("python3 not installed", "python" in r.languages)
        return r
    }

    @Test fun pythonRunsAndCapturesMetrics() {
        val c = ctx(runner = python())
        val r = exec(c, "run_experiment", "name" to "zlib", "language" to "python",
            "code" to "import zlib\nd=b'abc'*1000\nprint('METRIC ratio=%.4f' % (len(zlib.compress(d))/len(d)))\nopen('out.txt','w').write('ok')")
        assertTrue(r.output, r.ok)
        assertTrue(c.project.experiments.single().metrics.getValue("ratio") < 0.1)
        assertEquals("ok", c.workspace.read("out.txt"))
    }

    @Test fun pythonTimeoutKillsProcess() {
        val c = ToolContext(ctx().project, "t1", Workspace(tempDir()), python(), null, { false }, codeTimeoutMs = 1500)
        val t0 = System.currentTimeMillis()
        val r = exec(c, "run_code", "language" to "python", "code" to "while True: pass")
        assertFalse(r.ok)
        assertTrue(r.output, r.output.contains("TIMEOUT"))
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
    }

    @Test fun pythonInvalidCodeReportsError() {
        val c = ctx(runner = python())
        val r = exec(c, "run_code", "language" to "python", "code" to "def broken(:\n  pass")
        assertFalse(r.ok); assertEquals(Effect.CODE_FAIL, r.effect)
        assertTrue(r.output, r.output.contains("SyntaxError"))
    }

    @Test fun pythonSandboxBlocksOutsideFilesNetworkAndSubprocess() {
        val c = ctx(runner = python())
        val outside = File(tempDir("outside"), "secret.txt").apply { writeText("s") }
        val r1 = exec(c, "run_code", "language" to "python", "code" to "print(open(${"'" + outside.path + "'"}).read())")
        assertFalse(r1.output, r1.ok); assertTrue(r1.output.contains("sandbox"))
        val r2 = exec(c, "run_code", "language" to "python", "code" to "open('/tmp/agent_escape_test.txt','w').write('x')")
        assertFalse(r2.ok); assertFalse(File("/tmp/agent_escape_test.txt").exists())
        val r3 = exec(c, "run_code", "language" to "python", "code" to "import socket\ns=socket.socket()\ns.connect(('1.1.1.1',80))")
        assertFalse(r3.ok); assertTrue(r3.output, r3.output.contains("sandbox"))
        val r4 = exec(c, "run_code", "language" to "python", "code" to "import subprocess\nsubprocess.run(['ls'])")
        assertFalse(r4.ok)
        // Ordinary stdlib use still works.
        assertTrue(exec(c, "run_code", "language" to "python", "code" to "import json, math, collections\nprint(json.dumps({'a': math.pi}))").ok)
    }

    @Test fun nodeSandboxHelpersWork() {
        val r = ProcessCodeRunner()
        assumeTrue("node not installed", "js" in r.languages)
        val c = ctx(runner = r)
        val ok = exec(c, "run_code", "language" to "js", "code" to "writeFile('x.txt', 'hi'); print(readFile('x.txt') + '!'); print('METRIC n=3')")
        assertTrue(ok.output, ok.ok); assertTrue(ok.output.contains("hi!"))
        val bad = exec(c, "run_code", "language" to "js", "code" to "require('child_process').execSync('ls')")
        assertFalse(bad.ok); assertTrue(bad.output, bad.output.contains("not allowed"))
        val esc = exec(c, "run_code", "language" to "js", "code" to "readFile('../../etc/passwd')")
        assertFalse(esc.ok)
    }
}

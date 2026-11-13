package com.druk.agent

import com.druk.agent.llm.ActionParser
import com.druk.agent.llm.PlanParser
import com.druk.agent.llm.ReflectionParser
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    private val tools = setOf("write_file", "run_code", "run_experiment", "complete_task", "list_files", "read_file")

    @Test fun parsesKeyValuesAndFencedBlock() {
        val a = ActionParser.parse("""
            THOUGHT: write the encoder
            ACTION: write_file
            path: rle.js
            content:
            ```js
            function enc(s){ return s }
            // key: not an arg
            ```
        """.trimIndent(), tools)!!
        assertEquals("write_file", a.tool)
        assertEquals("rle.js", a.args["path"])
        assertEquals("function enc(s){ return s }\n// key: not an arg", a.args["content"])
        assertEquals("write the encoder", a.thought)
    }

    @Test fun fencedBlockWithoutKeyGoesToDefaultArg() {
        val a = ActionParser.parse("ACTION: run_code\nlanguage: python\n```python\nprint(1)\n```", tools) { if (it == "run_code") "code" else "content" }!!
        assertEquals("print(1)", a.args["code"])
        assertEquals("python", a.args["language"])
    }

    @Test fun stripsThinkingAndMarkdownNoise() {
        val a = ActionParser.parse("<think>I should ACTION: nothing</think>\n**ACTION:** `complete_task`\n**summary**: done\nevidence: e1, a2", tools)!!
        assertEquals("complete_task", a.tool)
        assertEquals("done", a.args["summary"])
        assertEquals("e1, a2", a.args["evidence"])
    }

    @Test fun acceptsJsonToolCallStyle() {
        val a = ActionParser.parse("""Sure! {"name": "read_file", "arguments": {"path": "a.txt"}}""", tools)!!
        assertEquals("read_file", a.tool)
        assertEquals("a.txt", a.args["path"])
        val b = ActionParser.parse("""{"action":"run_code","args":{"code":"print(2)","language":"python"}}""", tools)!!
        assertEquals("print(2)", b.args["code"])
    }

    @Test fun rejectsUnknownToolsAndProse() {
        assertNull(ActionParser.parse("I think we should explore compression more broadly.", tools))
        assertNull(ActionParser.parse("ACTION: launch_rockets\nx: 1", tools))
    }

    @Test fun normalizesToolNames() {
        assertEquals("run_code", ActionParser.parse("ACTION: Run-Code()\ncode: x", tools)!!.tool)
    }

    @Test fun unfencedMultilineValue() {
        val a = ActionParser.parse("ACTION: write_file\npath: n.md\ncontent:\nline one\nline two", tools)!!
        assertEquals("line one\nline two", a.args["content"])
    }

    @Test fun onlyFirstActionIsTaken() {
        val a = ActionParser.parse("ACTION: list_files\nACTION: read_file\npath: x", tools)!!
        assertEquals("list_files", a.tool)
        assertNull(a.args["path"])
    }

    @Test fun planParserReadsTasksAndDependencies() {
        val p = PlanParser.parse("""
            STRATEGY: Benchmark baselines, then try context mixing.
            TASK: Build corpus | create test files | 5 | none
            TASK: Baseline RLE | implement and measure | 4 | 1
            TASK: Compare | rank results | 3 | 1, 2, t7
        """.trimIndent())
        assertEquals("Benchmark baselines, then try context mixing.", p.strategy)
        assertEquals(3, p.tasks.size)
        assertEquals(listOf("1"), p.tasks[1].dependsOn)
        assertEquals(listOf("1", "2", "t7"), p.tasks[2].dependsOn)
        assertEquals(5, p.tasks[0].priority)
    }

    @Test fun planParserFallsBackToNumberedList() {
        val p = PlanParser.parse("Plan:\n1. Research algorithms\n2. Implement baseline\n- Measure ratio")
        assertEquals(3, p.tasks.size)
    }

    @Test fun reflectionParser() {
        val r = ReflectionParser.parse("WHY_FAILED: the model rewrote the same code\nNEW_APPROACH: measure a tiny baseline first")!!
        assertEquals("measure a tiny baseline first", r.newApproach)
        assertNull(ReflectionParser.parse("no idea"))
    }
}

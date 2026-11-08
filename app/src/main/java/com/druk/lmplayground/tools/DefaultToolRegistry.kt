package com.druk.lmplayground.tools

import android.content.Context
import com.druk.llamacpp.tools.ToolRegistry

/**
 * Builds the registry the app ships with.
 *
 * [ToolRegistry] itself lives in :llamacpp so the harness and any non-Android
 * caller can use the real registry (its OpenAI tool JSON and tool-result
 * shapes are part of what we test). The concrete tools can't follow it there:
 * they need a Context, okhttp and the androidx JavaScript engine.
 */
fun ToolRegistry.Companion.createDefault(context: Context): ToolRegistry =
    ToolRegistry().apply {
        // The order every tool list shows: grouped by what the tools do (the
        // web, math and code, the phone, then memory).
        // Shared link store: web_search hands the model compact "ddg:N"
        // references instead of long URLs and resolves them back when it's
        // asked to read one. See WebLinkStore.
        register(WebSearchTool(webLinkStore))
        register(WikipediaTool())
        register(CalculatorTool())
        register(UnitConverterTool())
        register(JavaScriptTool(context))
        register(GetDateTimeTool(context))
        register(SetAlarmTool(context))
        register(LocationTool(context))
        register(HardwareInfoTool(context))
        register(MemoryTool(context))
    }

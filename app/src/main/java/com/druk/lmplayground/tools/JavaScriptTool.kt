package com.druk.lmplayground.tools

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.toolResult
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JavaScriptTool(private val context: Context) : Tool {
    override val name = "run_javascript"
    override val description = "Runs JavaScript and returns the value of its last line. Useful for text, dates, lists and other data that are easier to handle in code. No internet or files."
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"JavaScript source; the last expression's value is returned. E.g. '(2 + 3) * 4', 'new Date().toString()'."}},"required":["code"]}"""

    override fun execute(arguments: String): String = try {
        if (!JavaScriptSandbox.isSupported()) {
            toolError("JavaScript engine not supported on this device")
        } else {
            val code = JSONObject(arguments).getString("code")
            JavaScriptSandbox.createConnectedInstanceAsync(context).get(5, TimeUnit.SECONDS).use { sandbox ->
                val params = IsolateStartupParameters()
                if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    params.setMaxHeapSizeBytes(16L * 1024 * 1024)
                }
                sandbox.createIsolate(params).use { isolate ->
                    // The engine returns "" unless the code produces a string, so
                    // String(eval(...)) gives the last expression's value. The code
                    // goes in as a quoted string, not a template literal, so its own
                    // backticks and ${...} are left alone.
                    toolResult(isolate.evaluateJavaScriptAsync("String(eval(${JSONObject.quote(code)}))").get(10, TimeUnit.SECONDS))
                }
            }
        }
    } catch (e: Exception) {
        toolError(e.cause?.message ?: e.message ?: "Execution failed")
    }
}

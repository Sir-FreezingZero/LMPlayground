package com.druk.llamacpp.tools

import org.json.JSONObject

/** One function the model can call: its name, what it does and returns, and its inputs as a JSON schema. */
class ToolFunction(val name: String, val description: String, val parametersSchema: String)

interface Tool {
    /** The tool's id in settings, chats and the UI. */
    val name: String
    val description: String
    val parametersSchema: String

    /** The name the model calls it by: a verb, so the name says what calling it does. */
    val functionName: String get() = name

    /** What the model sees and can call. Most tools offer one function. */
    val functions: List<ToolFunction> get() = listOf(ToolFunction(functionName, description, parametersSchema))

    fun execute(arguments: String): String

    /** Run [function], one of [functions]. */
    fun execute(function: String, arguments: String): String = execute(arguments)

    /** Abort in-flight work (a network request) so Stop returns promptly. */
    fun cancelInFlight() {}
}

/** A result that is one line of text, `{"result": text}`; the model reads just the text. */
fun toolResult(text: String): String = JSONObject().put("result", text).toString()

/** A failed result, `{"error": message}`; the model reads it as "Error: message". */
fun toolError(message: String): String = JSONObject().put("error", message).toString()

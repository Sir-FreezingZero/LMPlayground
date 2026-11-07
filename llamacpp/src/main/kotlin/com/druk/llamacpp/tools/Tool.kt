package com.druk.llamacpp.tools

/**
 * One function the model can call: its name, what it does and returns (the
 * first sentence is also listed in the system prompt), and its inputs as a
 * JSON schema.
 */
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

    /**
     * Abort any in-flight work (e.g. a blocking network request) so a Stop
     * during tool execution returns promptly. Default no-op for tools with
     * nothing to cancel (e.g. JavaScript).
     */
    fun cancelInFlight() {}
}

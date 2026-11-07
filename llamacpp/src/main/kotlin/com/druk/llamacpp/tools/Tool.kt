package com.druk.llamacpp.tools

interface Tool {
    val name: String
    val description: String
    val parametersSchema: String

    /**
     * One plain sentence on what this tool does and when it helps ("Works out
     * math exactly, from simple sums and money to percentages and formulas.").
     * Listed in the system prompt, where small models notice it more readily
     * than in the JSON definitions.
     */
    val useFor: String get() = ""
    fun execute(arguments: String): String

    /**
     * Abort any in-flight work (e.g. a blocking network request) so a Stop
     * during tool execution returns promptly. Default no-op for tools with
     * nothing to cancel (e.g. JavaScript).
     */
    fun cancelInFlight() {}
}

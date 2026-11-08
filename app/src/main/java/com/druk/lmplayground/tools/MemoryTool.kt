package com.druk.lmplayground.tools

import android.content.Context
import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.toolResult
import com.druk.llamacpp.tools.ToolFunction
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.MemoryNoteEntity
import org.json.JSONObject

/**
 * Lets the model keep short facts about the user across chats, stored
 * locally in Room. Two plain functions, remember and forget, rather than one
 * with an "action" switch, which small models fill in wrong. Saved facts
 * are already in the system prompt with their numbers, so there is no list.
 * Bounded (max notes + max length) so it can't grow unchecked. Runs off the
 * main thread (the tool loop), so the synchronous DAO calls are safe.
 */
class MemoryTool(private val context: Context) : Tool {
    override val name = "memory"
    override val description = "Saves a short fact about the user, like their name or a preference, so you know it in later chats."
    override val parametersSchema = """{"type":"object","properties":{"fact":{"type":"string","description":"The fact in a few words, e.g. \"Name is Zach\""}},"required":["fact"]}"""

    override val functions = listOf(
        ToolFunction("remember", description, parametersSchema),
        ToolFunction(
            "forget",
            "Deletes a saved fact about the user, by the number shown next to it in your instructions.",
            """{"type":"object","properties":{"number":{"type":"integer","description":"The fact's number"}},"required":["number"]}""",
        ),
    )

    private val dao get() = AppDatabase.getInstance(context).memoryDao()

    override fun execute(arguments: String) = execute("remember", arguments)

    override fun execute(function: String, arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            when (function) {
                "forget" -> {
                    val id = args.optLong("number", args.optLong("id", -1L))
                    if (id < 0L) return toolError("Give the number of the fact to delete.")
                    if (dao.deleteById(id) > 0) toolResult("Fact $id deleted.")
                    else toolError("There is no fact number $id.")
                }
                else -> {
                    val fact = args.optString("fact").ifBlank { args.optString("content") }.trim()
                    if (fact.isEmpty()) return toolError("Give the fact to save.")
                    if (fact.length > MAX_LEN) return toolError("That fact is too long (max $MAX_LEN characters).")
                    if (dao.count() >= MAX_NOTES) {
                        return toolError("Memory is full ($MAX_NOTES facts). Ask the user to delete some in Settings.")
                    }
                    val now = System.currentTimeMillis()
                    val id = dao.insert(MemoryNoteEntity(content = fact, createdAt = now, updatedAt = now))
                    toolResult("Saved as fact $id.")
                }
            }
        } catch (e: Exception) {
            toolError(e.message ?: "Memory operation failed")
        }
    }

    companion object {
        private const val MAX_LEN = 2000
        private const val MAX_NOTES = 200
    }
}

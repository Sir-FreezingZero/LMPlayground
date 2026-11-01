package com.druk.lmplayground.conversation

import android.app.Application
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Adds app-wide context to the system prompt the user picked, right before a
 * session is created. The user's own prompt is stored and shown unchanged;
 * only the copy handed to the engine carries the extras. A chat without a
 * prompt of its own gets the default one (Settings -> System prompts).
 *
 * The date is a day-level snapshot, not the time: a session's preamble is
 * fixed when it is created, and a string that changed every minute would
 * defeat the preamble KV cache. A date stays the same all day, so the cache is
 * reused within the day and refreshes at midnight.
 */
class SystemPromptExtras(
    private val app: Application,
    private val prefs: StoragePreferences,
) {
    fun compose(userPrompt: String): String {
        val parts = mutableListOf<String>()
        if (prefs.includeDateInPrompt) {
            val today = DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(Date())
            parts.add(app.getString(R.string.system_prompt_date, today))
        }
        userPrompt.ifBlank { prefs.defaultSystemPrompt }.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString("\n\n")
    }
}

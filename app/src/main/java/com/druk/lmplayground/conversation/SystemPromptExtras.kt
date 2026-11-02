package com.druk.lmplayground.conversation

import com.druk.lmplayground.storage.StoragePreferences

/**
 * Builds the system prompt handed to the engine, right before a session is
 * created. The user's own prompt is stored and shown unchanged; a chat
 * without a prompt of its own gets the default one (Settings -> System
 * prompts).
 */
class SystemPromptExtras(
    private val prefs: StoragePreferences,
) {
    fun compose(userPrompt: String): String = userPrompt.ifBlank { prefs.defaultSystemPrompt }
}

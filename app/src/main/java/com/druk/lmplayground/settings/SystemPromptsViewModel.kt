package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.druk.lmplayground.storage.StoragePreferences

/** Backs Settings -> System prompts: the default prompt. */
class SystemPromptsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = StoragePreferences(app)

    /** Used by every chat that has no system prompt of its own; "" for none. */
    var defaultPrompt: String
        get() = prefs.defaultSystemPrompt
        set(value) { prefs.defaultSystemPrompt = value }
}

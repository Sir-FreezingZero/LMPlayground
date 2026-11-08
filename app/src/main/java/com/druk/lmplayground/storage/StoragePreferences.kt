package com.druk.lmplayground.storage

import android.content.Context
import com.druk.lmplayground.conversation.GenerationParams
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class StoragePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_URI = "model_storage_uri"
        private const val KEY_MODEL_PARAMS = "model_params"
    }

    var modelStorageUri: Uri?
        get() = prefs.getString(KEY_URI, null)?.toUri()
        set(value) = prefs.edit { putString(KEY_URI, value?.toString()) }

    /** Play a chime when generation finishes while the app is backgrounded. */
    var soundOnCompletion: Boolean
        get() = prefs.getBoolean("sound_on_completion", true)
        set(value) = prefs.edit { putBoolean("sound_on_completion", value) }

    /** Per-token typewriter haptic while a response streams in. */
    var hapticOnGeneration: Boolean
        get() = prefs.getBoolean("haptic_on_generation", true)
        set(value) = prefs.edit { putBoolean("haptic_on_generation", value) }

    /** Add today's date to the system prompt, so the model knows what day it is. */
    var includeDateInPrompt: Boolean
        get() = prefs.getBoolean("include_date_in_prompt", true)
        set(value) = prefs.edit { putBoolean("include_date_in_prompt", value) }

    /** Most tool rounds per reply (Settings → Tools → Tool steps), 1 to 20. */
    /** Saved remote servers, as JSON; see [com.druk.lmplayground.remote.RemoteServers]. */
    var remoteServersJson: String
        get() = prefs.getString("remote_servers", null) ?: "[]"
        set(value) = prefs.edit { putString("remote_servers", value) }

    var maxToolSteps: Int
        get() = prefs.getInt("max_tool_steps", 10).coerceIn(1, 20)
        set(value) = prefs.edit { putInt("max_tool_steps", value.coerceIn(1, 20)) }

    /** App theme: 0 = follow the system, 1 = light, 2 = dark (Settings → Appearance). */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(value) = prefs.edit { putInt("theme_mode", value) }

    /** Status line brightness, 0 (dim) to 1 (full); see StatusLineColors.brightness. */
    var statusLineBrightness: Float
        get() = prefs.getFloat("status_line_brightness", 0.75f)
        set(value) = prefs.edit { putFloat("status_line_brightness", value) }

    /** A custom status line color (ARGB) for the state named [key], or null for its default. */
    fun statusLineColor(key: String): Int? =
        if (prefs.contains("status_color_$key")) prefs.getInt("status_color_$key", 0) else null

    fun setStatusLineColor(key: String, argb: Int?) = prefs.edit {
        if (argb == null) remove("status_color_$key") else putInt("status_color_$key", argb)
    }

    /** Ask the loaded model for a short chat title after a new chat's first reply. */
    var autoNameChats: Boolean
        get() = prefs.getBoolean("auto_name_chats", true)
        set(value) = prefs.edit { putBoolean("auto_name_chats", value) }

    /**
     * Whether live HTML previews may load anything from the internet (CDN
     * libraries, fonts, images, APIs). Off by default, so previews keep the
     * app fully offline until the user opts in from Settings → General.
     */
    var htmlPreviewInternet: Boolean
        get() = prefs.getBoolean("html_preview_internet", false)
        set(value) = prefs.edit { putBoolean("html_preview_internet", value) }

    /** Show ```html blocks in replies as live preview cards; off shows them as plain code. */
    var livePreviews: Boolean
        get() = prefs.getBoolean("live_previews", true)
        set(value) = prefs.edit { putBoolean("live_previews", value) }

    /**
     * True once everything previews stored while online (cache, storage,
     * cookies) has been deleted since internet access was last on. Use
     * HtmlPreviewInternet rather than writing this directly.
     */
    var htmlPreviewOfflineClean: Boolean
        get() = prefs.getBoolean("html_preview_offline_clean", false)
        set(value) = prefs.edit { putBoolean("html_preview_offline_clean", value) }

    /**
     * Disable llama.cpp CPU weight repacking (load weights memory-mapped only).
     * OFF by default → repacking stays on, which is faster but allocates a
     * second resident copy of the weights (~2x footprint) and can OOM-crash
     * large models on low-RAM devices. Users who hit those crashes can turn
     * this ON to trade decode speed for a far smaller resident set. Read by
     * [com.druk.lmplayground.conversation.ConversationViewModel] at load time.
     */
    // Stored inverted under the legacy "disable_repack" key so existing
    // installs keep their setting; the UI reads the positive form.
    var repackEnabled: Boolean
        get() = !prefs.getBoolean("disable_repack", false)
        set(value) = prefs.edit { putBoolean("disable_repack", !value) }

    fun getCustomModelMetadata(filename: String): Pair<String, Boolean>? {
        val value = prefs.getString("custom_model_$filename", null) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.size != 2) return null
        return Pair(parts[0], parts[1].toBoolean())
    }

    fun setCustomModelMetadata(filename: String, name: String, hasChatTemplate: Boolean) {
        prefs.edit { putString("custom_model_$filename", "$name|$hasChatTemplate") }
    }

    /**
     * The model settings every model uses (PrivateLM-style: one set, not per
     * model). Only temperature, context size, longest reply and the thinking
     * limit are kept; the rest of the sampling is fixed (see GenerationParams).
     * Before the first save the context and reply length follow this phone's
     * RAM (see DeviceTier).
     */
    var modelParams: GenerationParams
        get() {
            val saved = prefs.getString(KEY_MODEL_PARAMS, null)?.let(::decodeParams)
                ?: return GenerationParams(
                    contextSize = recommendedContextSize,
                    maxTokens = recommendedMaxTokens,
                )
            val context = (saved["contextSize"]?.toInt() ?: recommendedContextSize)
                .coerceIn(GenerationParams.MIN_CONTEXT_SIZE, GenerationParams.MAX_CONTEXT_SIZE)
            return GenerationParams(
                contextSize = context,
                temperature = (saved["temperature"] ?: GenerationParams().temperature).coerceIn(0f, 2f),
                thinkingBudget = saved["thinkingBudget"]?.toInt()?.takeIf { it >= 0 } ?: (context / 4),
                maxTokens = (saved["maxTokens"]?.toInt() ?: recommendedMaxTokens)
                    .coerceIn(GenerationParams.MIN_MAX_TOKENS, GenerationParams.MAX_MAX_TOKENS),
            )
        }
        set(value) {
            val kept = mapOf(
                "contextSize" to value.contextSize.toFloat(),
                "temperature" to value.temperature,
                "thinkingBudget" to value.thinkingBudget.toFloat(),
                "maxTokens" to value.maxTokens.toFloat(),
            )
            prefs.edit { putString(KEY_MODEL_PARAMS, kept.entries.joinToString(",") { "${it.key}=${it.value}" }) }
        }

    /** Context size suggested for this phone's RAM; set once by DeviceTier. */
    var recommendedContextSize: Int
        get() = prefs.getInt("recommended_context_size", GenerationParams.DEFAULT_CONTEXT_SIZE)
        set(value) = prefs.edit { putInt("recommended_context_size", value) }

    /** Longest reply suggested for this phone's RAM; set once by DeviceTier. */
    var recommendedMaxTokens: Int
        get() = prefs.getInt("recommended_max_tokens", GenerationParams.DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit { putInt("recommended_max_tokens", value) }

    private fun decodeParams(encoded: String): Map<String, Float>? = try {
        encoded.split(",").associate {
            val (k, v) = it.split("=", limit = 2)
            k to v.toFloat()
        }
    } catch (_: Exception) { null }

    // --- Tool enablement (two-level: default + per-chat setting) ---
    //
    // The default (Settings → Tools, "Default tools") applies to every chat; a
    // chat's own setting ("Chat-specific tools", kept in the chat's metadata)
    // wins for that chat only. Tools are OFF by default.

    fun isToolEnabledDefault(toolName: String): Boolean {
        return prefs.getBoolean("tool_enabled_$toolName", false)
    }

    fun setToolEnabledDefault(toolName: String, enabled: Boolean) {
        prefs.edit { putBoolean("tool_enabled_$toolName", enabled) }
    }

    /** A tool's state in a chat: its own setting in [chatTools] (from the chat's metadata), else the default. */
    fun toolEnabled(toolName: String, chatTools: Map<String, String>): Boolean =
        chatTools[toolName]?.toBooleanStrictOrNull() ?: isToolEnabledDefault(toolName)

    /** Show each quick select tool's name next to its icon, above the message box. */
    var quickToolNames: Boolean
        get() = prefs.getBoolean("quick_tool_names", false)
        set(value) = prefs.edit { putBoolean("quick_tool_names", value) }

    /** Used by every chat that has no system prompt of its own; "" for none. */
    var defaultSystemPrompt: String
        get() = prefs.getString("default_system_prompt", "").orEmpty()
        set(value) = prefs.edit { putString("default_system_prompt", value) }

    // --- Detected model capabilities cache ---
    //
    // Capabilities (tool calling, thinking) read from a model's chat template at
    // load time, cached per filename so the model list can show accurate badges
    // for models the user has already loaded (including custom GGUFs).

    fun getDetectedCaps(filename: String): Pair<Boolean, Boolean>? {
        if (!prefs.contains("caps_tools_$filename")) return null
        return Pair(
            prefs.getBoolean("caps_tools_$filename", false),
            prefs.getBoolean("caps_thinking_$filename", false),
        )
    }

    fun setDetectedCaps(filename: String, supportsTools: Boolean, supportsThinking: Boolean) {
        prefs.edit {
            putBoolean("caps_tools_$filename", supportsTools)
            putBoolean("caps_thinking_$filename", supportsThinking)
        }
    }

    // --- One-time "image support available" chat nudge ---
    //
    // Tracks, per model filename, whether the offer to download a missing image
    // module has already been shown so it's surfaced at most once per model.

    fun wasVisionModuleHintShown(filename: String): Boolean {
        return prefs.getBoolean("vision_hint_$filename", false)
    }

    fun setVisionModuleHintShown(filename: String) {
        prefs.edit { putBoolean("vision_hint_$filename", true) }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}

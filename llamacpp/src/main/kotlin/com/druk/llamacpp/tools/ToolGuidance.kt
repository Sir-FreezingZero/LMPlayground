package com.druk.llamacpp.tools

/**
 * The part of the system prompt that tells the model when to call a tool and,
 * just as much, when not to.
 *
 * The tool definitions (from the chat format) say what each tool does but
 * not when it's wanted, and small models fill that gap badly both ways: they
 * call get_location on "hello", or announce "let me look that up" and never
 * call anything. So: a plain rule that most messages need no tool, one line
 * per enabled tool saying when it is the right one, and how to call it.
 * A general "call a tool instead of asking the user" line was tried and made
 * small models look up the location on every message; the per-tool lines
 * scope each tool instead.
 *
 * Written for the model, so it stays in English and short.
 */
object ToolGuidance {

    /** When each known function is the right one. A function not listed here still works; it just gets no line. */
    private val WHEN = linkedMapOf(
        "web_search" to "news, prices, weather, sports, anything recent or that may have changed, or facts you " +
            "are not sure of; to read a link, call it with url",
        "search_wikipedia" to "facts about a well-known person, place, event or idea that you are not sure of",
        "calculate" to "any math beyond simple mental arithmetic; give its result instead of working it out yourself",
        "convert_units" to "converting an amount from one unit to another",
        "run_javascript" to "working with text, dates, lists or data that is easier in code",
        "get_date_time" to "the current time or time zone, or the date if it is not given above",
        "set_alarm" to "only when the user asks for an alarm or a reminder at a time",
        "get_location" to "only when the user asks where they are, or wants something local (like weather or " +
            "places nearby) without naming a place",
        "get_device_info" to "only when the user asks about their phone",
        "remember" to "when the user tells you a lasting fact about themselves (like their name or a preference) " +
            "or asks you to remember something",
        "forget" to "when the user asks you to forget something saved",
    )

    /**
     * Guidance for the enabled functions [names], or null when there are none.
     * [compact] (models under about 9B) leaves out the longer web tips.
     */
    fun forFunctions(names: Collection<String>, compact: Boolean = false): String? {
        if (names.isEmpty()) return null
        val lines = WHEN.filterKeys { it in names }.map { (name, use) -> "- $name: $use." }
        val webTips = when {
            "web_search" !in names -> ""
            compact -> " Do not repeat the same search."
            else -> " If a search result looks relevant, read its page with web_search and its url. Do not " +
                "repeat a search; rephrase it if it found nothing useful."
        }
        return buildString {
            append("Most messages do not need a tool. For greetings, small talk, opinions, advice, explanations, ")
            append("writing (stories, poems, emails, code) and anything you already know well, just reply.")
            if (lines.isNotEmpty()) {
                append(" Use a tool only when the request needs what it gives:\n")
                append(lines.joinToString("\n"))
                append("\n")
            } else {
                append(" Use a tool only when the request needs what it gives. ")
            }
            append("To use a tool, call it right away instead of saying you will. Then answer the user from its ")
            append("result in plain words.")
            append(webTips)
        }
    }
}

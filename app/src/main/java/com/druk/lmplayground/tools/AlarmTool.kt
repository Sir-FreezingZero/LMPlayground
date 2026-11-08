package com.druk.lmplayground.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.toolError
import com.druk.llamacpp.tools.toolResult
import com.druk.lmplayground.App
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `set_alarm`: a one-time alarm in the phone's Clock app.
 *
 * The model passes the time as the user said it ("6:30", "6:30 pm",
 * "18:30", "in 2 hours"). With no AM/PM the app settles it the way a person
 * would: on a 12-hour phone "6:30" is whichever 6:30 comes next; on a
 * 24-hour phone it is 06:30.
 */
class SetAlarmTool(
    private val context: Context,
    private val now: () -> LocalTime = { LocalTime.now() },
    private val twelveHourClock: () -> Boolean = { !DateFormat.is24HourFormat(context) },
) : Tool {
    override val name = "set_alarm"
    override val description = "Sets an alarm in the phone's Clock app, for a time of day or a time from now. Returns the time it was set for."
    override val parametersSchema = """{"type":"object","properties":{""" +
        """"time":{"type":"string","description":"The time as the user said it, e.g. \"6:30 PM\", \"6:15 in the morning\", \"in 2 hours\""},""" +
        """"label":{"type":"string","description":"The name the user gave the alarm. Leave it out if they didn't name it."}},"required":["time"]}"""

    override fun execute(arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        val label = args.optString("label").trim().take(60)
        val text = args.optString("time")
        val inSeconds = relativeSeconds(text)
        if (inSeconds > 24 * 3600) return toolError("Alarms can be at most 24 hours ahead. Tell the user.")
        // "in 20 minutes": the current minute plus that (10:28 + 1 hour = 11:28);
        // under a minute ("in 30 seconds") goes to the next minute.
        val time = if (inSeconds > 0) now().withSecond(0).withNano(0).plusSeconds(maxOf(inSeconds, 60)).withSecond(0)
        else time(text) ?: return toolError("Give time like \"6:30\", \"6:30 PM\", \"18:30\" or \"in 20 minutes\".")

        // Android won't let a background app open another app.
        if ((context.applicationContext as? App)?.isAppInForeground == false) {
            return toolError("Open LM Playground and try again; it can't open the Clock app from the background.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            return toolError("This phone has no Clock app that can set alarms.")
        } catch (e: SecurityException) {
            return toolError("Android refused to open the Clock app: ${e.message}")
        }
        val day = if (time > now()) "today" else "tomorrow"
        val ahead = if (inSeconds > 0) " (in ${text.trim().replaceFirst(LEADING_IN, "")})" else ""
        val shown = time.format(DateTimeFormatter.ofPattern(if (twelveHourClock()) "h:mm a" else "HH:mm", Locale.ENGLISH))
        val name = if (label.isEmpty()) "Alarm" else "Alarm \"$label\""
        return toolResult("$name set for $shown $day$ahead.")
    }

    /** "in 20 minutes", "in 1 hour 30 min", "2 hours" in seconds from now; 0 if it isn't one. */
    private fun relativeSeconds(text: String): Long =
        DURATION.findAll(text.lowercase()).sumOf { m ->
            m.groupValues[1].toDouble() * when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
        }.toLong()

    /**
     * "6:30", "6:30 pm", "6 am", "18:30", "6:15 in the morning", "9 tonight" as a
     * time of day; with no AM/PM or part of day on a 12-hour phone, the next one.
     */
    private fun time(text: String): LocalTime? {
        val lower = text.lowercase()
        val m = CLOCK.find(lower) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val amPm = m.groupValues[3].ifEmpty {
            when {
                MORNING.containsMatchIn(lower) -> "a"
                LATER.containsMatchIn(lower) -> "p"
                else -> ""
            }
        }
        return when {
            amPm.isNotEmpty() && hour in 1..12 -> LocalTime.of(hour % 12 + if (amPm == "p") 12 else 0, minute)
            hour !in 1..12 || !twelveHourClock() -> LocalTime.of(hour, minute)
            else -> {
                // No AM/PM: whichever comes next.
                val morning = LocalTime.of(hour % 12, minute)
                val evening = morning.plusHours(12)
                val current = now()
                if (current < morning || current >= evening) morning else evening
            }
        }
    }

    private companion object {
        val DURATION = Regex("""(\d+(?:\.\d+)?)\s*(hour|hr|h\b|minute|min|m\b|second|sec|s\b)""")
        val CLOCK = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?""")
        val MORNING = Regex("""\bmorning\b""")
        val LATER = Regex("""\b(afternoon|evening|night|tonight)\b""")
        val LEADING_IN = Regex("(?i)^in\\s+")
    }
}

package com.druk.lmplayground.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import com.druk.llamacpp.tools.Tool
import com.druk.lmplayground.App
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `set_alarm`: a one-time alarm in the phone's Clock app.
 *
 * The model passes the time as the user said it ("6:30", "6:30 pm",
 * "18:30"). It is asked to check get_date_time first when there's no AM/PM,
 * but the app settles that case itself as well, the way a person would: on
 * a 12-hour phone "6:30" is whichever 6:30 comes next; on a 24-hour phone
 * it is 06:30.
 */
class SetAlarmTool(
    private val context: Context,
    private val now: () -> LocalTime = { LocalTime.now() },
    private val twelveHourClock: () -> Boolean = { !DateFormat.is24HourFormat(context) },
) : Tool {
    override val name = "set_alarm"
    override val description =
        "Set an alarm in the phone's Clock app. There are two kinds of request, and time is written differently for each:\n" +
            "1. A clock time, like \"set an alarm for 6:30\": time is that time, e.g. \"6:30\", \"6:30 PM\" or \"18:30\". " +
            "If the user didn't say AM or PM, call get_date_time first.\n" +
            "2. A time from now, like \"remind me in 2 hours\": time keeps the word \"in\", e.g. \"in 2 hours\". " +
            "Never turn it into a clock time like \"2:00\"; the app works out when that is."
    override val parametersSchema = """{"type":"object","properties":{""" +
        """"time":{"type":"string","description":"A clock time (\"6:30 PM\") or a time from now (\"in 2 hours\")"},""" +
        """"label":{"type":"string","description":"The name the user gave the alarm. Leave it out if they didn't name it."}},"required":["time"]}"""

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: JSONException) {
            JSONObject()
        }
        val label = args.optString("label").trim().take(60)
        val text = args.optString("time")
        val inSeconds = relativeSeconds(text)
        if (inSeconds > 24 * 3600) return error("Alarms can be at most 24 hours ahead. Tell the user.")
        // "in 20 minutes": the current minute plus that (10:28 + 1 hour = 11:28);
        // under a minute ("in 30 seconds") goes to the next minute.
        val time = if (inSeconds > 0) now().withSecond(0).withNano(0).plusSeconds(maxOf(inSeconds, 60)).withSecond(0)
        else time(text) ?: return error("Give time like \"6:30\", \"6:30 PM\", \"18:30\" or \"in 20 minutes\".")

        // Android won't let a background app open another app.
        if ((context.applicationContext as? App)?.isAppInForeground == false) {
            return error("Open LM Playground and try again; it can't open the Clock app from the background.")
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
            return error("This phone has no Clock app that can set alarms.")
        } catch (e: SecurityException) {
            return error("Android refused to open the Clock app: ${e.message}")
        }
        val day = if (time > now()) "today" else "tomorrow"
        val ahead = if (inSeconds > 0) " (in ${text.trim().replaceFirst(Regex("(?i)^in\\s+"), "")})" else ""
        val shown = time.format(DateTimeFormatter.ofPattern(if (twelveHourClock()) "h:mm a" else "HH:mm", Locale.ENGLISH))
        val name = if (label.isEmpty()) "Alarm" else "Alarm \"$label\""
        return JSONObject().put("result", "$name set for $shown $day$ahead.").toString()
    }

    /** "in 20 minutes", "in 1 hour 30 min", "2 hours" in seconds from now; 0 if it isn't one. */
    private fun relativeSeconds(text: String): Long =
        Regex("""(\d+(?:\.\d+)?)\s*(hour|hr|h\b|minute|min|m\b|second|sec|s\b)""").findAll(text.lowercase()).sumOf { m ->
            m.groupValues[1].toDouble() * when (m.groupValues[2].first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
        }.toLong()

    /** "6:30", "6:30 pm", "6 am", "18:30" as a time of day; with no AM/PM on a 12-hour phone, the next one. */
    private fun time(text: String): LocalTime? {
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?""").find(text.lowercase()) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour > 23 || minute > 59) return null
        val amPm = m.groupValues[3]
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

    private fun error(message: String) = JSONObject().put("error", message).toString()
}

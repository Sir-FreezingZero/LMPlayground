package com.druk.lmplayground.tools

import android.content.Context
import android.text.format.DateFormat
import com.druk.llamacpp.tools.Tool
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The phone's time formatter: "3:20:05 PM" or "15:20:05", following the clock setting. */
private fun clockFormat(twelveHour: Boolean): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (twelveHour) "h:mm:ss a" else "HH:mm:ss", Locale.ENGLISH)

/** `get_date_time`: today's date, the current time and the time zone. Nothing leaves the phone. */
class GetDateTimeTool(
    private val context: Context,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val twelveHour: () -> Boolean = { !DateFormat.is24HourFormat(context) },
) : Tool {
    override val name = "get_date_time"
    override val description = "Get the current date, time and time zone."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        val now = now()
        return JSONObject()
            .put("date", now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)))
            .put("time", now.format(clockFormat(twelveHour())))
            .put("time_zone", now.zone.id)
            .toString()
    }
}

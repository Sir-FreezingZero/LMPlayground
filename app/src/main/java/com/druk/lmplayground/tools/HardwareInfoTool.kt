package com.druk.lmplayground.tools

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.druk.llamacpp.tools.Tool
import org.json.JSONObject
import java.util.Locale

/**
 * Reports this device's hardware/system specs. All fields are readable without
 * any runtime permission. Mildly sensitive (device model, RAM, battery) so the
 * Settings row carries a "reads device specs" note; the tool stays OFF by
 * default (opt-in) like every tool.
 */
class HardwareInfoTool(private val context: Context) : Tool {
    override val name = "device_info"
    override val useFor = "this phone's model, memory, storage or battery"
    override val description = "Get this phone's details: model, Android version, chipset, CPU cores, RAM, storage and battery."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        return try {
            // Plain values the model can repeat as they are; no internal
            // fields (API level, ABIs) that small models read out verbatim.
            val out = JSONObject()
            out.put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            out.put("android", Build.VERSION.RELEASE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                out.put("chipset", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            }
            out.put("cpu_cores", Runtime.getRuntime().availableProcessors())

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                out.put("memory", "${humanBytes(mi.totalMem)} (${humanBytes(mi.availMem)} free)")
            }

            val stat = StatFs(context.filesDir.absolutePath)
            out.put("storage", "${humanBytes(stat.blockCountLong * stat.blockSizeLong)} " +
                "(${humanBytes(stat.availableBlocksLong * stat.blockSizeLong)} free)")

            (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.let { bm ->
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) out.put("battery", "$level%, " + if (bm.isCharging) "charging" else "not charging")
            }
            out.toString()
        } catch (e: Exception) {
            """{"error":"${(e.message ?: "Could not read device info").replace("\"", "'")}"}"""
        }
    }

    /** Human-readable size with 1 decimal (base-1024). */
    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }
}

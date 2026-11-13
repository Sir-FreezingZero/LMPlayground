package com.druk.lmplayground.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.druk.agent.engine.RunPolicy
import com.druk.agent.tools.SearchHit
import com.druk.agent.tools.WebAccess
import com.druk.llamacpp.tools.WebLinkStore
import com.druk.lmplayground.tools.WebFetchTool
import com.druk.lmplayground.tools.WebSearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bridges the agent's web tools to the app's existing web_search / web_fetch
 * implementations (DuckDuckGo HTML search, jsoup → markdown). Search results
 * may carry compact "ddg:N" references instead of URLs; the shared
 * [WebLinkStore] resolves them on fetch, exactly as in chat.
 */
class AgentWebAccess : WebAccess {
    private val links = WebLinkStore()
    private val search = WebSearchTool(links)
    private val fetch = WebFetchTool(links)

    override suspend fun search(query: String): List<SearchHit> {
        val raw = call { search.execute(JSONObject().put("query", query).put("max_results", 6).toString()) }
        val obj = JSONObject(raw)
        if (obj.has("error")) throw java.io.IOException(obj.getString("error"))
        val arr = obj.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            SearchHit(r.optString("title"), r.optString("url").ifEmpty { r.optString("ref") }, r.optString("snippet"))
        }
    }

    override suspend fun fetch(url: String): String {
        val raw = call { fetch.execute(JSONObject().put("url", url).put("max_length", 20000).toString()) }
        val obj = JSONObject(raw)
        if (obj.has("error")) throw java.io.IOException(obj.getString("error"))
        return buildString {
            obj.optString("title").takeIf { it.isNotBlank() }?.let { append("# ").append(it).append("\n\n") }
            append(obj.optString("content"))
        }
    }

    /** Blocking okhttp call off the caller's dispatcher; cancellation aborts the request. */
    private suspend fun call(block: () -> String): String = withContext(Dispatchers.IO) {
        val job = async { block() }
        try { job.await() } catch (e: CancellationException) {
            search.cancelInFlight(); fetch.cancelInFlight(); throw e
        }
    }
}

/** User-tunable agent settings (SharedPreferences "agent_prefs"). */
class AgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)

    var minBatteryPercent: Int
        get() = prefs.getInt("min_battery", 30)
        set(v) = prefs.edit().putInt("min_battery", v.coerceIn(0, 100)).apply()

    var onlyWhileCharging: Boolean
        get() = prefs.getBoolean("only_charging", false)
        set(v) = prefs.edit().putBoolean("only_charging", v).apply()

    var allowWeb: Boolean
        get() = prefs.getBoolean("allow_web", true)
        set(v) = prefs.edit().putBoolean("allow_web", v).apply()

    var defaultModel: String?
        get() = prefs.getString("default_model", null)
        set(v) = prefs.edit().putString("default_model", v).apply()

    var contextSize: Int
        get() = prefs.getInt("context_size", 4096)
        set(v) = prefs.edit().putInt("context_size", v).apply()
}

/**
 * Battery / thermal / power-saver gate, checked before every step.
 * Stopping only ends this run: projects stay ACTIVE and the periodic
 * resume work picks them up when conditions improve.
 */
class DeviceRunPolicy(private val context: Context, private val settings: AgentSettings) : RunPolicy {
    override fun decide(): RunPolicy.Decision {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l < 0 || s <= 0) 100 else l * 100 / s
        } ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (settings.onlyWhileCharging && !plugged) return RunPolicy.Decision.Stop("waiting for charger")
        if (!plugged && level < settings.minBatteryPercent) return RunPolicy.Decision.Stop("battery $level% < ${settings.minBatteryPercent}%")
        val pm = context.getSystemService(PowerManager::class.java)
        when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> return RunPolicy.Decision.Stop("device too hot")
            PowerManager.THERMAL_STATUS_MODERATE -> return RunPolicy.Decision.Throttle(60_000, "device warm")
            else -> Unit
        }
        if (pm.isPowerSaveMode && !plugged) return RunPolicy.Decision.Throttle(30_000, "battery saver")
        if (ChatActivityGate.chatBusy()) return RunPolicy.Decision.Throttle(5_000, "chat in use")
        return RunPolicy.Decision.Run
    }
}

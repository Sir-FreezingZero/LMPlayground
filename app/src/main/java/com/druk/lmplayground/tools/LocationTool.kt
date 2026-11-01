package com.druk.lmplayground.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.druk.llamacpp.tools.Tool
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reports the device's current APPROXIMATE location (coarse / network accuracy)
 * plus a best-effort nearby place name. Sensitive: guarded by the runtime
 * ACCESS_COARSE_LOCATION permission (requested when the user turns the tool on in
 * Settings), self-checks it at call time, and carries a transparency note in the
 * Settings row. Uses the last-known fix when it is recent, and otherwise asks for
 * a fresh one, waiting at most 10 seconds per provider.
 */
class LocationTool(private val context: Context) : Tool {
    override val name = "location"
    override val description = "Get the device's current approximate location: latitude, longitude, accuracy in meters, and, when available, the nearby place (city, region, country). Requires location permission; if it isn't granted, tell the user to enable the Location tool in Settings."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        return try {
            if (!hasPermission()) {
                return """{"error":"Location permission is not granted. Enable the Location tool in Settings so the app can ask for permission."}"""
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return """{"error":"Location service is unavailable on this device."}"""
            if (!lm.isLocationEnabled) {
                return """{"error":"Location is turned off on this phone. Ask the user to turn it on, then try again."}"""
            }
            // A last-known fix is instant, but many phones have none (or only an
            // old one) unless another app asked recently; then ask for a fresh one.
            val known = bestLastKnown(lm)
            val location = known?.takeIf { System.currentTimeMillis() - it.time < MAX_AGE_MS }
                ?: currentFix(lm)
                ?: known
                ?: return """{"error":"Couldn't get a location fix. Make sure location is turned on, then try again."}"""

            val out = JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
                .put("provider", location.provider ?: "")
                .put("timestamp_ms", location.time)
            reverseGeocode(location.latitude, location.longitude)?.let { out.put("place", it) }
            out.toString()
        } catch (e: SecurityException) {
            """{"error":"Location permission is not granted."}"""
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Could not read the location").toString()
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Most recent last-known fix across the providers coarse permission allows. */
    @Suppress("MissingPermission") // permission is verified in execute() before this runs
    private fun bestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val loc = try {
                if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
            } catch (_: SecurityException) {
                null // provider needs finer permission than we hold; skip it
            } catch (_: Exception) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        return best
    }

    @Volatile private var pending: CancellationSignal? = null

    override fun cancelInFlight() {
        pending?.cancel()
    }

    /**
     * A fresh fix from the first provider that answers within [FIX_TIMEOUT_MS].
     * Blocks, but tools run off the main thread.
     */
    @Suppress("MissingPermission") // permission is verified in execute() before this runs
    private fun currentFix(lm: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        for (provider in providers) {
            val enabled = try { lm.isProviderEnabled(provider) } catch (_: Exception) { false }
            if (!enabled) continue
            val signal = CancellationSignal().also { pending = it }
            val latch = CountDownLatch(1)
            var fix: Location? = null
            try {
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                    fix = loc
                    latch.countDown()
                }
                if (!latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) signal.cancel()
            } catch (_: SecurityException) {
                // this provider needs finer permission than we hold; try the next
            } catch (_: Exception) {
            } finally {
                pending = null
            }
            if (signal.isCanceled && fix == null) return null // Stop, or the time ran out
            fix?.let { return it }
        }
        return null
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val a = addresses?.firstOrNull() ?: return null
            listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }
        } catch (_: Exception) {
            null // Geocoder needs network / a backend; best-effort only
        }
    }

    private companion object {
        /** A last-known fix older than this is replaced by a fresh one when possible. */
        const val MAX_AGE_MS = 10 * 60 * 1000L
        const val FIX_TIMEOUT_MS = 10_000L
    }
}

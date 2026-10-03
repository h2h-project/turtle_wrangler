package org.hopeturtles.wrangler.data

import android.content.Context
import org.hopeturtles.wrangler.ble.TurtleTelemetry
import org.json.JSONObject

/** The last snapshot of a turtle this phone was connected to. */
data class LastSeen(
    val address: String,
    val name: String,
    val seenAtMs: Long,
    val socPct: Int?,
    val lat: Double?,
    val lon: Double?,
)

/**
 * Per-turtle "last seen" cache on the phone (app Phase 1). Shown on the
 * "No turtles nearby" screen, always labelled with its time — never presented
 * as live data.
 */
class LastSeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("last_seen", Context.MODE_PRIVATE)

    fun save(address: String, t: TurtleTelemetry) {
        val o = JSONObject()
            .put("name", t.name ?: address)
            .put("seen", System.currentTimeMillis())
        t.power?.socPct?.let { o.put("soc", it) }
        t.position?.lat?.let { o.put("lat", it) }
        t.position?.lon?.let { o.put("lon", it) }
        prefs.edit().putString(address, o.toString()).apply()
    }

    fun all(): List<LastSeen> = prefs.all.mapNotNull { (address, v) ->
        try {
            val o = JSONObject(v as String)
            LastSeen(
                address = address,
                name = o.optString("name", address),
                seenAtMs = o.optLong("seen"),
                socPct = if (o.has("soc")) o.getInt("soc") else null,
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lon = if (o.has("lon")) o.getDouble("lon") else null,
            )
        } catch (_: Exception) {
            null
        }
    }.sortedByDescending { it.seenAtMs }
}

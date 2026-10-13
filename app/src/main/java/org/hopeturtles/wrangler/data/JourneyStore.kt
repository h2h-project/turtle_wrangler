package org.hopeturtles.wrangler.data

import android.content.Context
import org.hopeturtles.wrangler.ble.LatLon
import org.json.JSONObject

/**
 * Where each journey left from, as this phone saw it (app plan Phase 7b).
 * Contract v1 doesn't send the departure the turtle stamps, so when the app
 * starts a journey it records the turtle's position from the same moment.
 * Journeys started on the turtle's own screen aren't here.
 */
class JourneyStore(context: Context) {
    private val prefs = context.getSharedPreferences("journeys", Context.MODE_PRIVATE)

    /** journey_id → departure, for one turtle. */
    fun load(address: String): Map<Long, LatLon> = try {
        val o = JSONObject(prefs.getString(address, "{}")!!)
        o.keys().asSequence().associate { k ->
            val p = o.getJSONObject(k)
            k.toLong() to LatLon(p.getDouble("lat"), p.getDouble("lon"))
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /** Keeps the newest [MAX] per turtle. */
    fun add(address: String, journeyId: Long, departure: LatLon): Map<Long, LatLon> {
        val all = (load(address) + (journeyId to departure)).entries
            .sortedBy { it.key }.takeLast(MAX).associate { it.key to it.value }
        val o = JSONObject()
        all.forEach { (id, p) -> o.put(id.toString(), JSONObject().put("lat", p.lat).put("lon", p.lon)) }
        prefs.edit().putString(address, o.toString()).apply()
        return all
    }

    companion object {
        const val MAX = 50
    }
}

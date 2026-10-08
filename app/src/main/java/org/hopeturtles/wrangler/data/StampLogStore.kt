package org.hopeturtles.wrangler.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One tap of the GPS tab's Stamp button and what the turtle said.
 *
 * [turtleAt] is the turtle's clock (unix s) when it answered, estimated
 * from `device_now`; it is what [StampLogStore.markSent] compares with
 * `last_shore_sync`. [lat]/[lon] are the turtle's position from the latest
 * telemetry at that moment (the stamp itself doesn't echo a position).
 */
data class StampEntry(
    val atMs: Long,
    val turtleAt: Long?,
    val journeyId: Long?,
    val ok: Boolean,
    val hasPosition: Boolean,
    val stampsSession: Int?,
    val reason: String?,           // why it failed, in words; null when ok
    val lat: Double?,
    val lon: Double?,
    val sats: Int?,
    val sent: Boolean = false,
)

/**
 * The phone's record of manual stamps, per turtle (app plan Phase 4): it
 * persists so a field session can be reviewed afterwards, and entries carry
 * their journey so the GPS tab can group them.
 */
class StampLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("stamp_log", Context.MODE_PRIVATE)

    fun load(address: String): List<StampEntry> = try {
        val a = JSONArray(prefs.getString(address, "[]"))
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    /** Newest last; keeps the most recent [MAX] per turtle. */
    fun add(address: String, e: StampEntry): List<StampEntry> = save(address, (load(address) + e).takeLast(MAX))

    /**
     * Mark stamps delivered once the turtle's queue is empty and its last
     * successful POST came after them — like the OLED's Stamped → Sent.
     * Returns the updated list, or null when nothing changed.
     */
    fun markSent(address: String, lastShoreSync: Long?, queueCount: Int?): List<StampEntry>? {
        if (lastShoreSync == null || queueCount != 0) return null
        val all = load(address)
        var changed = false
        val out = all.map { e ->
            if (e.ok && !e.sent && e.turtleAt != null && lastShoreSync >= e.turtleAt) {
                changed = true
                e.copy(sent = true)
            } else e
        }
        return if (changed) save(address, out) else null
    }

    private fun save(address: String, list: List<StampEntry>): List<StampEntry> {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        prefs.edit().putString(address, a.toString()).apply()
        return list
    }

    private fun toJson(e: StampEntry) = JSONObject().apply {
        put("at", e.atMs)
        e.turtleAt?.let { put("tat", it) }
        e.journeyId?.let { put("j", it) }
        put("ok", e.ok)
        put("pos", e.hasPosition)
        e.stampsSession?.let { put("n", it) }
        e.reason?.let { put("why", it) }
        e.lat?.let { put("lat", it) }
        e.lon?.let { put("lon", it) }
        e.sats?.let { put("sats", it) }
        put("sent", e.sent)
    }

    private fun fromJson(o: JSONObject) = StampEntry(
        atMs = o.getLong("at"),
        turtleAt = if (o.has("tat")) o.getLong("tat") else null,
        journeyId = if (o.has("j")) o.getLong("j") else null,
        ok = o.getBoolean("ok"),
        hasPosition = o.optBoolean("pos"),
        stampsSession = if (o.has("n")) o.getInt("n") else null,
        reason = if (o.has("why")) o.getString("why") else null,
        lat = if (o.has("lat")) o.getDouble("lat") else null,
        lon = if (o.has("lon")) o.getDouble("lon") else null,
        sats = if (o.has("sats")) o.getInt("sats") else null,
        sent = o.optBoolean("sent"),
    )

    companion object {
        const val MAX = 500
    }
}

package org.hopeturtles.wrangler.data

import android.content.Context
import android.util.AtomicFile
import org.hopeturtles.wrangler.ble.LatLon
import org.hopeturtles.wrangler.ble.TrackPoint
import org.hopeturtles.wrangler.ble.TurtleJourney
import org.hopeturtles.wrangler.ui.Geo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One point of a journey's route: the turtle's position at turtle-clock
 * time [t] (unix s). [turtle]: from the turtle's own log (JOURNEY_TRACK);
 * otherwise this phone saw it in live telemetry.
 */
data class RoutePoint(val t: Long, val p: LatLon, val turtle: Boolean = false)

/**
 * One journey, merged from what this phone saw and what the turtle reports
 * (v2 plan Phase E). Times are the turtle's clock (unix s); [id] is the
 * contract's `journey_id`, which is also its start time.
 *
 * Facts the turtle stamps beat the phone's guesses: once [turtle] is true
 * the end time, arrival and destination are the turtle's (JOURNEY_LIST),
 * and [departureFromTurtle] says the departure is the one it stamped
 * (`0119` or JOURNEY_LIST). Before turtleOS 2.6.0 everything is the phone's:
 * [departure] is the turtle's position when this phone started the journey,
 * [destination] the active target seen while it was open.
 */
data class JourneyRecord(
    val id: Long,
    val departure: LatLon? = null,
    val destination: LatLon? = null,
    /** The route, oldest first: the phone's points and the turtle's, by time. */
    val track: List<RoutePoint> = emptyList(),
    /** Last time this phone saw the journey open. */
    val lastSeenAt: Long = id,
    /** When it ended; if not [endSeen], when this phone noticed it had. */
    val endedAt: Long? = null,
    /** [endedAt] is the end time: the phone saw it end, or the turtle said. */
    val endSeen: Boolean = false,
    /** Where it ended (the turtle's stamp once [turtle]; null = no fix). */
    val arrival: LatLon? = null,
    /** The turtle's own record of this journey has been merged in. */
    val turtle: Boolean = false,
    val departureFromTurtle: Boolean = false,
    /** The turtle's track has been fetched since the journey ended. */
    val turtleTrack: Boolean = false,
) {
    val ended get() = endedAt != null

    /** Where the route starts: the departure, else the first position seen. */
    val start get() = departure ?: track.firstOrNull()?.p

    /** Where the route ends: the arrival, else the last position seen. */
    val finish get() = arrival ?: track.lastOrNull()?.p

    /** Known exactly only when the end was seen; else see [minDurationS]. */
    val durationS: Long? get() = if (endSeen) endedAt?.let { it - id } else null

    /** It ran at least this long: up to the last time it was seen open. */
    val minDurationS get() = lastSeenAt - id

    /** Straight line from the start to the destination. */
    val plannedM get() = Geo.distanceM(start, destination)

    /** The route's points with the departure and arrival at its ends. */
    val route: List<LatLon> get() = listOfNotNull(departure) + track.map { it.p } + listOfNotNull(arrival)

    /** Along the route (gaps count as straight lines). */
    val travelledM: Long get() = route.zipWithNext { a, b -> Geo.distanceM(a, b) ?: 0 }.sum()

    /** How far from the destination it finished (null unless it has ended). */
    val missM get() = if (ended) Geo.distanceM(finish, destination) else null

    val turtlePoints get() = track.count { it.turtle }
    val phonePoints get() = track.count { !it.turtle }

    /**
     * A point this phone saw at [t], added if it is [stepM] or more from the
     * last point (GPS jitter at rest stays under that); null when not added.
     * A full track drops every other point, so the whole route stays, coarser.
     */
    fun withPoint(p: LatLon, t: Long, stepM: Long, max: Int): JourneyRecord? {
        val last = track.lastOrNull()
        if (last != null && (Geo.distanceM(last.p, p) ?: 0) < stepM) return null
        return copy(track = thin(track, max) + RoutePoint(t, p))
    }

    /**
     * The turtle's record of this (ended) journey merged in: its departure,
     * end time, arrival and destination replace the phone's guesses. A null
     * arrival from the turtle means it had no fix, so the phone's (if any)
     * stays. An unknown end time keeps the phone's estimate.
     */
    fun withTurtle(t: TurtleJourney): JourneyRecord {
        val end = t.endedAt ?: endedAt ?: lastSeenAt
        return copy(
            departure = t.departure ?: departure,
            departureFromTurtle = t.departure != null || departureFromTurtle,
            destination = t.destination ?: destination,
            endedAt = end,
            endSeen = t.endedAt != null || endSeen,
            lastSeenAt = maxOf(lastSeenAt, end),
            arrival = t.arrival ?: arrival,
            turtle = true,
        )
    }

    /**
     * The turtle's track points merged into the route: the union with what
     * the phone saw, by time. Points the turtle sent before are kept (they
     * leave its queue once posted to the server), duplicates dropped.
     */
    fun withTurtlePoints(points: List<TrackPoint>, max: Int): JourneyRecord {
        val known = track.filter { it.turtle }.map { it.t }.toHashSet()
        val add = points.filter { it.t !in known }.map { RoutePoint(it.t, LatLon(it.lat, it.lon), turtle = true) }
        if (add.isEmpty()) return this
        return copy(track = thin((track + add).sortedBy { it.t }, max + 1))
    }

    companion object {
        /** At most [max] points: drop every other one when full. */
        fun thin(track: List<RoutePoint>, max: Int): List<RoutePoint> {
            var t = track
            while (t.size >= max) t = t.filterIndexed { i, _ -> i % 2 == 0 }
            return t
        }
    }
}

/**
 * The phone's journey log, per turtle: one file per journey under
 * `files/journeys/<turtle>/`, so the open journey's track can be rewritten
 * without touching the others. Keeps the newest [MAX] per turtle.
 */
class JourneyStore(context: Context) {
    private val root = File(context.filesDir, "journeys")
    private val legacy = context.getSharedPreferences("journeys", Context.MODE_PRIVATE)

    private fun dir(address: String) = File(root, address.replace(":", ""))

    /** Newest first. Blocking (file reads); call off the main thread. */
    fun load(address: String): List<JourneyRecord> {
        migrate(address)
        val files = dir(address).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try { fromJson(JSONObject(String(AtomicFile(f).readFully()))) } catch (_: Exception) { null }
        }.sortedByDescending { it.id }
    }

    /** Blocking; call off the main thread. */
    fun save(address: String, r: JourneyRecord) {
        val d = dir(address).apply { mkdirs() }
        val f = AtomicFile(File(d, "${r.id}.json"))
        val out = f.startWrite()
        try {
            out.write(toJson(r).toString().toByteArray())
            f.finishWrite(out)
        } catch (_: Exception) {
            f.failWrite(out)
        }
    }

    /** Drop all but the newest [MAX]. Blocking. */
    fun prune(address: String, keep: Collection<Long>) {
        dir(address).listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            if (f.name.removeSuffix(".json").toLongOrNull() !in keep) f.delete()
        }
    }

    /** 0.2.6 kept only departures, in SharedPreferences: bring them over once. */
    private fun migrate(address: String) {
        val s = legacy.getString(address, null) ?: return
        try {
            val o = JSONObject(s)
            o.keys().forEach { k ->
                val p = o.getJSONObject(k)
                val id = k.toLong()
                if (!File(dir(address), "$id.json").exists())
                    save(address, JourneyRecord(id, departure = LatLon(p.getDouble("lat"), p.getDouble("lon"))))
            }
        } catch (_: Exception) {}
        legacy.edit().remove(address).apply()
    }

    private fun pt(p: LatLon) = JSONArray().put(p.lat).put(p.lon)
    private fun pt(a: JSONArray?) = a?.let { LatLon(it.getDouble(0), it.getDouble(1)) }

    private fun toJson(r: JourneyRecord) = JSONObject().apply {
        put("id", r.id)
        r.departure?.let { put("dep", pt(it)) }
        r.destination?.let { put("dest", pt(it)) }
        // [lat, lon, t, 1 = from the turtle]
        put("track", JSONArray().apply {
            r.track.forEach { put(JSONArray().put(it.p.lat).put(it.p.lon).put(it.t).put(if (it.turtle) 1 else 0)) }
        })
        put("seen", r.lastSeenAt)
        r.endedAt?.let { put("end", it) }
        put("endSeen", r.endSeen)
        r.arrival?.let { put("arr", pt(it)) }
        put("turtle", r.turtle)
        put("depT", r.departureFromTurtle)
        put("tTrack", r.turtleTrack)
    }

    private fun fromJson(o: JSONObject): JourneyRecord {
        val t = o.optJSONArray("track") ?: JSONArray()
        val id = o.getLong("id")
        val seen = o.optLong("seen", id)
        val n = t.length()
        return JourneyRecord(
            id = id,
            departure = pt(o.optJSONArray("dep")),
            destination = pt(o.optJSONArray("dest")),
            track = (0 until n).map { i ->
                val a = t.getJSONArray(i)
                // 0.2.7 points had no time: spread them over when it was seen open.
                val at = if (a.length() > 2) a.getLong(2) else id + (seen - id) * i / maxOf(1, n - 1)
                RoutePoint(at, LatLon(a.getDouble(0), a.getDouble(1)), a.optInt(3) == 1)
            },
            lastSeenAt = seen,
            endedAt = if (o.has("end")) o.getLong("end") else null,
            endSeen = o.optBoolean("endSeen"),
            arrival = pt(o.optJSONArray("arr")),
            turtle = o.optBoolean("turtle"),
            departureFromTurtle = o.optBoolean("depT"),
            turtleTrack = o.optBoolean("tTrack"),
        )
    }

    companion object {
        const val MAX = 50
    }
}

package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The trip being guided, kept so it can be offered again after the process was killed. Only the places are
 * stored (destination, the final destination behind a fuel stop, and the stops still ahead); the route is recomputed.
 */
data class ActiveTrip(val destination: Place, val finalDestination: Place?, val savedAtMs: Long, val stops: List<Place> = emptyList()) {
    fun toJson(): String = JSONObject()
        .put("dest", place(destination))
        .apply { finalDestination?.let { put("final", place(it)) } }
        .apply { if (stops.isNotEmpty()) put("stops", JSONArray().also { arr -> stops.forEach { arr.put(place(it)) } }) }
        .put("at", savedAtMs).toString()

    fun fresh(nowMs: Long): Boolean = nowMs - savedAtMs in 0..MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private fun place(p: Place) = JSONObject().put("n", p.name).put("d", p.detail).put("lat", p.point.lat).put("lon", p.point.lon)
        private fun place(o: JSONObject) = Place(o.getString("n"), o.optString("d"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")))

        fun fromJson(json: String?): ActiveTrip? = runCatching {
            val o = JSONObject(json ?: return null)
            val stops = o.optJSONArray("stops")?.let { arr -> (0 until arr.length()).map { place(arr.getJSONObject(it)) } }.orEmpty()
            ActiveTrip(place(o.getJSONObject("dest")), o.optJSONObject("final")?.let(::place), o.getLong("at"), stops)
        }.getOrNull()
    }
}

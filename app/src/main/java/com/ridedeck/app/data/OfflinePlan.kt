package com.ridedeck.app.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject

/** A downloaded map region: everything within [radiusKm] of [center]. */
data class OfflineArea(val name: String, val center: GeoPoint, val radiusKm: Int, val savedAtMs: Long) {
    fun contains(p: GeoPoint): Boolean = Geo.distance(center, p) <= radiusKm * 1000.0
}

/** Pure sizing and bookkeeping for offline maps; the MapLibre download lives in `OfflineMaps`. */
object OfflinePlan {
    const val MIN_ZOOM = 5.0
    const val MAX_ZOOM = 14.0
    /** Roughly what a dense OpenFreeMap vector tile weighs. */
    private const val KB_PER_TILE = 55.0
    val RADII_KM = listOf(15, 30, 60)

    /** South-west and north-east corners of the square around [center]. */
    fun corners(center: GeoPoint, radiusKm: Int): Pair<GeoPoint, GeoPoint> {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(center.lat)))
        return GeoPoint(center.lat - dLat, center.lon - dLon) to GeoPoint(center.lat + dLat, center.lon + dLon)
    }

    private fun tileX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * 2.0.pow(z)).toInt()
    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)).toInt()
    }

    fun tileCount(center: GeoPoint, radiusKm: Int, minZoom: Int = MIN_ZOOM.toInt(), maxZoom: Int = MAX_ZOOM.toInt()): Int {
        val (sw, ne) = corners(center, radiusKm)
        var total = 0
        for (z in minZoom..maxZoom) {
            val w = tileX(ne.lon, z) - tileX(sw.lon, z) + 1
            val h = tileY(sw.lat, z) - tileY(ne.lat, z) + 1
            total += w * h
        }
        return total
    }

    fun estimateMb(tiles: Int): Int = (tiles * KB_PER_TILE / 1024.0).toInt().coerceAtLeast(1)

    fun covered(areas: List<OfflineArea>, p: GeoPoint): Boolean = areas.any { it.contains(p) }

    fun toJson(areas: List<OfflineArea>): String = JSONArray().also { arr ->
        areas.forEach { arr.put(JSONObject().put("n", it.name).put("lat", it.center.lat).put("lon", it.center.lon).put("r", it.radiusKm).put("at", it.savedAtMs)) }
    }.toString()

    fun fromJson(json: String?): List<OfflineArea> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            OfflineArea(o.getString("n"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")), o.getInt("r"), o.getLong("at"))
        }
    }.getOrDefault(emptyList())
}

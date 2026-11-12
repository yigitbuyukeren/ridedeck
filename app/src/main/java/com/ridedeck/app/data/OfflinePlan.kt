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

/**
 * One user download (day + night styles) measured against the provider limits. The two styles
 * share their tile set, so [tiles] counts the set once instead of giving each style its own budget.
 */
data class OfflineBudget(val tiles: Int, val bytes: Long) {
    val fitsTiles: Boolean get() = tiles <= OfflinePlan.MAX_TILES_PER_DOWNLOAD
    val fitsBytes: Boolean get() = bytes <= OfflinePlan.MAX_BYTES_PER_DOWNLOAD
    val fits: Boolean get() = fitsTiles && fitsBytes
}

/** Why a download cannot start. The UI maps these to localized text; no request has been sent. */
sealed interface OfflineBlock {
    data class TileLimit(val tiles: Int, val limit: Int) : OfflineBlock
    data class ByteLimit(val bytes: Long, val limit: Long) : OfflineBlock
    /** MapLibre 11.13.5 exposes no offline concurrency cap, so the provider terms cannot be proven. */
    data object ProviderUnverified : OfflineBlock
}

/** Pure sizing and bookkeeping for offline maps; the MapLibre download lives in `OfflineMaps`. */
object OfflinePlan {
    const val MIN_ZOOM = 5.0
    const val MAX_ZOOM = 14.0
    /** Roughly what a dense OpenFreeMap vector tile weighs. Estimates never measure a real download. */
    private const val KB_PER_TILE = 55.0
    val RADII_KM = listOf(15, 30, 60)

    /**
     * OpenFreeMap bulk-download terms (terms page last updated 2026-10-14; requirements re-checked
     * 2026-11-11): at most 3,000 tiles OR 50 MB per download, at most 4 concurrent requests, and an
     * app-identifying User-Agent. Both size caps are enforced conservatively, and 50 MB is decimal
     * (50,000,000 bytes), not MiB.
     */
    const val MAX_TILES_PER_DOWNLOAD = 3_000
    const val MAX_BYTES_PER_DOWNLOAD = 50_000_000L
    const val MAX_CONCURRENT_REQUESTS = 4
    /** Day and night styles are one download; their shared tiles are budgeted once. */
    const val STYLES_PER_DOWNLOAD = 2

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

    fun estimateBytes(tiles: Int): Long = (tiles * KB_PER_TILE * 1024.0).toLong()

    /** Decimal MB, rounded up so the shown estimate stays on the conservative side of the 50 MB cap. */
    fun estimateMb(tiles: Int): Int = ((estimateBytes(tiles) + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1)

    /** Tiles and estimated bytes for the whole day+night download, sharing one provider budget. */
    fun budget(center: GeoPoint, radiusKm: Int): OfflineBudget {
        val tiles = tileCount(center, radiusKm)
        return OfflineBudget(tiles, estimateBytes(tiles))
    }

    /** Radii that stay within both provider size caps. */
    fun fittingRadii(center: GeoPoint): List<Int> = RADII_KM.filter { budget(center, it).fits }

    /**
     * The first guard that stops a download before MapLibre is touched. Size guards run first so an
     * oversized area gets a scope message. Otherwise the download fails closed: MapLibre 11.13.5 has
     * no public offline concurrency limit (its HTTP dispatcher allows 20 requests per host), and the
     * offline path's User-Agent cannot be verified before the first request. No request is sent.
     */
    fun blockFor(center: GeoPoint, radiusKm: Int): OfflineBlock? {
        val b = budget(center, radiusKm)
        return when {
            !b.fitsTiles -> OfflineBlock.TileLimit(b.tiles, MAX_TILES_PER_DOWNLOAD)
            !b.fitsBytes -> OfflineBlock.ByteLimit(b.bytes, MAX_BYTES_PER_DOWNLOAD)
            else -> OfflineBlock.ProviderUnverified
        }
    }

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

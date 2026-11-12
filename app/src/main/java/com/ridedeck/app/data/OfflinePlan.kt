package com.ridedeck.app.data

import kotlin.math.PI
import kotlin.math.abs
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

/**
 * Advisory result of comparing cached-route geometry with saved offline-region metadata.
 *
 * [BOUNDS_ONLY] means only that the route lies inside the lat/lon bounds that were requested for the
 * saved regions. It does not prove that any tile is stored: the saved area is written after MapLibre
 * reports completion, and that SDK status is not tile availability. No value here may be presented as
 * "offline ready", and downloads stay blocked by [OfflinePlan.blockFor].
 */
enum class CachedRouteMapCoverage {
    /** No usable saved regions or route geometry, or metadata outside the trusted download schema. */
    UNKNOWN,

    /** The whole route lies inside saved-region metadata bounds; tile presence is NOT verified. */
    BOUNDS_ONLY,

    /** At least one route segment lies outside every saved region's metadata bounds. */
    OUTSIDE_SAVED_BOUNDS;

    /** Saved-region metadata is never evidence that map tiles are actually stored. */
    val provesTileAvailability: Boolean get() = false
}

/**
 * Metadata-only bounds assessment for a cached route. Every polyline segment is clipped against the
 * rectangles [OfflinePlan.corners] requested for the saved areas, and a segment is accepted only when
 * those clipped intervals cover it completely across one or more areas. Checking vertices alone is not
 * enough: a straight segment between two areas can cross an uncovered gap.
 *
 * Anything that cannot be judged conservatively stays [CachedRouteMapCoverage.UNKNOWN]: empty or
 * malformed metadata, fewer than two route points, polar points, a bounds rectangle that wraps the
 * antimeridian, or a segment that itself crosses ±180°. Longitude is compared as a plain number there,
 * so such geometry is not silently treated as "outside" or as a long straight line.
 */
object CachedRouteCoverage {
    private const val EPS = 1e-9
    private const val MAX_LAT = 85.0

    fun assess(areas: List<OfflineArea>, shape: List<GeoPoint>): CachedRouteMapCoverage {
        if (shape.size < 2 || areas.isEmpty()) return CachedRouteMapCoverage.UNKNOWN
        if (shape.any { !trustedPoint(it) }) return CachedRouteMapCoverage.UNKNOWN
        if (shape.zipWithNext().any { (a, b) -> abs(a.lon - b.lon) > 180.0 }) return CachedRouteMapCoverage.UNKNOWN

        val rects = ArrayList<Pair<GeoPoint, GeoPoint>>(areas.size)
        for (area in areas) {
            // A region whose saved metadata is unusable may be the one covering the gap: make no claim.
            if (area.radiusKm <= 0 || !trustedPoint(area.center)) return CachedRouteMapCoverage.UNKNOWN
            val (sw, ne) = OfflinePlan.corners(area.center, area.radiusKm)
            if (!trustedPoint(sw) || !trustedPoint(ne)) return CachedRouteMapCoverage.UNKNOWN
            rects += sw to ne
        }

        for (i in 0 until shape.lastIndex) {
            if (!segmentCovered(shape[i], shape[i + 1], rects)) return CachedRouteMapCoverage.OUTSIDE_SAVED_BOUNDS
        }
        return CachedRouteMapCoverage.BOUNDS_ONLY
    }

    private fun trustedPoint(p: GeoPoint): Boolean =
        p.lat.isFinite() && p.lon.isFinite() && abs(p.lat) <= MAX_LAT && abs(p.lon) <= 180.0

    /** Whether the union of the saved bounds covers the whole a→b segment; interval overlap is exact. */
    private fun segmentCovered(a: GeoPoint, b: GeoPoint, rects: List<Pair<GeoPoint, GeoPoint>>): Boolean {
        val intervals = rects.mapNotNull { (sw, ne) -> clip(a, b, sw, ne) }.sortedBy { it.first }
        if (intervals.isEmpty()) return false
        var coveredUntil = 0.0
        for ((start, end) in intervals) {
            if (start > coveredUntil + EPS) return false
            if (end > coveredUntil) coveredUntil = end
            if (coveredUntil >= 1.0 - EPS) return true
        }
        return coveredUntil >= 1.0 - EPS
    }

    /** The part of segment a→b inside the lat/lon rectangle (sw, ne), as fractions of the segment. */
    private fun clip(a: GeoPoint, b: GeoPoint, sw: GeoPoint, ne: GeoPoint): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        fun edge(p: Double, d: Double, lo: Double, hi: Double): Boolean {
            if (d == 0.0) return p >= lo && p <= hi
            var enter = (lo - p) / d
            var exitT = (hi - p) / d
            if (enter > exitT) {
                val swap = enter
                enter = exitT
                exitT = swap
            }
            if (enter > t0) t0 = enter
            if (exitT < t1) t1 = exitT
            return t0 <= t1
        }
        if (!edge(a.lat, b.lat - a.lat, sw.lat, ne.lat)) return null
        if (!edge(a.lon, b.lon - a.lon, sw.lon, ne.lon)) return null
        return t0 to t1
    }
}

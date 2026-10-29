package com.ridedeck.app.data

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Decodes a Valhalla/Google-style encoded polyline with the given precision (Valhalla uses 6). */
    fun decodePolyline(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }

    /** Equirectangular distance in metres — accurate enough over the short spans used for route matching. */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(dLat, dLon) * EARTH_RADIUS_M
    }

    /** Result of projecting a point onto a segment: distance from the segment and the fraction along it (0..1). */
    data class Projection(val distanceM: Double, val fraction: Double)

    fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val cosLat = cos(Math.toRadians(a.lat))
        val ax = 0.0
        val ay = 0.0
        val bx = Math.toRadians(b.lon - a.lon) * cosLat * EARTH_RADIUS_M
        val by = Math.toRadians(b.lat - a.lat) * EARTH_RADIUS_M
        val px = Math.toRadians(p.lon - a.lon) * cosLat * EARTH_RADIUS_M
        val py = Math.toRadians(p.lat - a.lat) * EARTH_RADIUS_M
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * bx + (py - ay) * by) / len2))
        return Projection(hypot(px - t * bx, py - t * by), t)
    }

    /**
     * Splits [shape] at the matched position (segment [index], [fraction] along it) into the part
     * already ridden and the part still ahead; both include the split point so the two lines meet.
     */
    fun splitRoute(shape: List<GeoPoint>, index: Int, fraction: Double): Pair<List<GeoPoint>, List<GeoPoint>> {
        if (shape.size < 2) return shape to shape
        val i = index.coerceIn(0, shape.size - 2)
        val t = fraction.coerceIn(0.0, 1.0)
        val a = shape[i]
        val b = shape[i + 1]
        val at = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return (shape.subList(0, i + 1) + at) to (listOf(at) + shape.subList(i + 1, shape.size))
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = kotlin.math.sin(dLon) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * kotlin.math.sin(Math.toRadians(b.lat)) -
            kotlin.math.sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}

package com.ridedeck.app.data

data class TripPoint(val t: Long, val lat: Double, val lon: Double, val kmh: Int)

data class TripStats(val distanceM: Double, val movingMs: Long, val maxKmh: Int) {
    val avgKmh: Int
        get() = if (movingMs <= 0) 0 else (distanceM / (movingMs / 1000.0) * 3.6).let { Math.round(it).toInt() }
}

data class Trip(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val stats: TripStats,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

object TripMath {
    /** Gaps longer than this (GPS lost, tunnel) do not add distance. */
    private const val MAX_GAP_MS = 30_000L

    /** Faster than this between two fixes is a GPS jump, not driving (about 250 km/h). */
    private const val MAX_PLAUSIBLE_MPS = 70.0

    private const val MOVING_KMH = 2

    fun compute(points: List<TripPoint>): TripStats {
        var distance = 0.0
        var moving = 0L
        var max = 0
        for (i in points.indices) {
            val b = points[i]
            if (b.kmh > max) max = b.kmh
            if (i == 0) continue
            val a = points[i - 1]
            val dt = b.t - a.t
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            val d = Geo.distance(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon))
            if (d / (dt / 1000.0) > MAX_PLAUSIBLE_MPS) continue
            // Standing still, the GPS position still wobbles by a few metres; that is not distance travelled.
            if (maxOf(a.kmh, b.kmh) >= MOVING_KMH) distance += d
            if (b.kmh >= MOVING_KMH) moving += dt
        }
        return TripStats(distance, moving, max)
    }
}

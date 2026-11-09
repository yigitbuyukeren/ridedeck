package com.ridedeck.app.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** When to speak: distances follow the speed so a motorway exit is announced as early, in seconds, as a town turn. */
object PromptSchedule {
    /** Far warning ("turn right ahead"): about 45 s out, between 400 m and 2 km. */
    fun alertM(speedMps: Double): Double = (speedMps * 45).coerceIn(400.0, 2000.0)

    /** The full instruction: about 11 s out, between 100 m and 450 m. */
    fun nearM(speedMps: Double): Double = (speedMps * 11).coerceIn(100.0, 450.0)

    /** The far warning is skipped when it would land on top of the full instruction. */
    fun alertWorthIt(distToNextM: Double, speedMps: Double): Boolean = distToNextM > nearM(speedMps) + 50
}

/** Matching a GPS fix onto the route, and deciding when the rider has really left it. */
object RouteMatcher {
    /** Segments tried behind the last matched one, for a fix that lands a little back (GPS jitter, a fresh route). */
    const val BACK_MARGIN = 3
    private const val WRONG_WAY_PENALTY_M = 60.0
    private const val SIDEWAYS_PENALTY_M = 12.0
    /** Below this speed (m/s) the heading is noise and ignored. */
    const val MIN_HEADING_SPEED = 2.5

    data class Match(val index: Int, val projection: Geo.Projection)

    /** Smallest angle between two bearings, 0..180. */
    fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    fun match(shape: List<GeoPoint>, p: GeoPoint, from: Int, window: Int, bearing: Double?, speedMps: Double): Match {
        var bestIdx = from.coerceIn(0, shape.size - 2)
        var best = Geo.Projection(Double.MAX_VALUE, 0.0)
        var bestScore = Double.MAX_VALUE
        val start = max(0, from - BACK_MARGIN)
        val end = min(shape.size - 2, from + window)
        for (i in start..end) {
            val proj = Geo.project(p, shape[i], shape[i + 1])
            var score = proj.distanceM
            if (bearing != null && speedMps >= MIN_HEADING_SPEED && Geo.distance(shape[i], shape[i + 1]) > 3.0) {
                val diff = angleBetween(Geo.bearing(shape[i], shape[i + 1]), bearing)
                score += when {
                    diff > 120 -> WRONG_WAY_PENALTY_M
                    diff > 60 -> SIDEWAYS_PENALTY_M
                    else -> 0.0
                }
            }
            if (score < bestScore) {
                bestScore = score
                best = proj
                bestIdx = i
            }
        }
        return Match(bestIdx, best)
    }

    /** After this long without a usable fix the whole route is searched: the bike may be anywhere along it. */
    const val FULL_SEARCH_GAP_S = 30.0

    /**
     * How many segments ahead of [from] to search after [gapS] seconds without a match at [speedMps]: the distance
     * the bike could have covered (with half again as margin) turned into segments, never fewer than [minimum].
     * A tunnel in a city, where segments are 10–20 m, must not leave the match behind and force a re-route.
     */
    fun windowSegments(cumulative: DoubleArray, from: Int, gapS: Double, speedMps: Double, minimum: Int): Int {
        if (cumulative.size < 2) return minimum
        if (gapS >= FULL_SEARCH_GAP_S) return cumulative.size
        val aheadM = gapS.coerceAtLeast(0.0) * speedMps.coerceAtLeast(0.0) * 1.5
        if (aheadM <= 0.0) return minimum
        val start = from.coerceIn(0, cumulative.size - 2)
        var end = start
        while (end < cumulative.size - 2 && cumulative[end + 1] - cumulative[start] < aheadM) end++
        return max(minimum, end - start + 1)
    }

    /** Metres from the route that count as "off it": wider on a motorway and for a poor GPS fix. */
    fun offRouteM(speedMps: Double, accuracyM: Int?): Double =
        max(max(45.0, (accuracyM ?: 0) * 2.0), speedMps * 3.0).coerceAtMost(150.0)

    /** Consecutive off-route fixes needed; fewer when fast so the turn-around is not noticed late. */
    fun offRouteFixes(speedMps: Double): Int = if (speedMps >= 20) 3 else 4
}

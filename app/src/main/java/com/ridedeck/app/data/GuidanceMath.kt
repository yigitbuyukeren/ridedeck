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

    /** The short "now" cue: about 3 s out, between 20 m and 60 m. */
    fun nowM(speedMps: Double): Double = (speedMps * 3).coerceIn(20.0, 60.0)

    /** The "now" cue is dropped when the full instruction was spoken this recently; it would only stutter. */
    const val NOW_MIN_GAP_MS = 5_000L

    /** After a turn, "continue for 2 km" is only worth saying on a stretch at least this long. */
    const val POST_MIN_M = 400.0
}

/**
 * Arrival time that follows the drive. The routing service's time assumes free-flowing traffic; once a minute of
 * driving has been observed, the remaining time is taken from a blend of the planned speed over the rest of the
 * route and the speed actually driven lately (an exponential average, frozen while standing still so a red light
 * does not drag the estimate down).
 */
class EtaEstimator(
    private val tauS: Double = TAU_S,
    private val minObservedS: Double = MIN_OBSERVED_S,
    private val plannedWeight: Double = PLANNED_WEIGHT,
) {
    private var measuredMps = 0.0
    private var observedS = 0.0
    private var lastAtMs = 0L

    fun onFix(tMs: Long, speedMps: Double) {
        if (lastAtMs == 0L || tMs <= lastAtMs || tMs - lastAtMs > GAP_RESET_MS) { lastAtMs = tMs; return }
        val dt = (tMs - lastAtMs) / 1000.0
        lastAtMs = tMs
        if (speedMps < STILL_MPS) return // frozen while stopped
        val alpha = dt / (tauS + dt)
        measuredMps = if (observedS == 0.0) speedMps else measuredMps + alpha * (speedMps - measuredMps)
        observedS += dt
    }

    /** Seconds to go for [remainingM] when the plan says [plannedS]; the plan alone until enough driving was seen. */
    fun remainingS(remainingM: Double, plannedS: Double): Int {
        if (remainingM <= 0.0) return 0
        if (plannedS <= 0.0 || observedS < minObservedS || measuredMps < STILL_MPS) return plannedS.toInt()
        val plannedMps = remainingM / plannedS
        val blended = plannedWeight * plannedMps + (1 - plannedWeight) * measuredMps
        return (remainingM / blended).toInt()
    }

    /** True when the drive is so far off the plan (slower or faster) that the card should say so. */
    fun drifts(remainingM: Double, plannedS: Double): Boolean {
        if (plannedS <= 0.0) return false
        val ours = remainingS(remainingM, plannedS)
        return kotlin.math.abs(ours - plannedS) / plannedS > DRIFT_FRACTION
    }

    fun reset() { measuredMps = 0.0; observedS = 0.0; lastAtMs = 0L }

    companion object {
        const val TAU_S = 60.0
        const val MIN_OBSERVED_S = 60.0
        const val PLANNED_WEIGHT = 0.6
        const val DRIFT_FRACTION = 0.2
        const val STILL_MPS = 1.4
        const val GAP_RESET_MS = 30_000L
    }
}

/** Distances the way a person says them: rounded so the number is believable at driving speed. */
object SpokenDistance {
    /** 1 km and more to 100 m, 200 m and more to 50 m, under that to 10 m. */
    fun roundMetres(meters: Double): Int {
        val m = meters.coerceAtLeast(0.0)
        val step = when {
            m >= 1000 -> 100
            m >= 200 -> 50
            else -> 10
        }
        return (Math.round(m / step) * step).toInt().coerceAtLeast(step)
    }

    /**
     * @param text string lookup for the unit templates (metre, kilometre, feet, mile), each with one placeholder.
     * @param locale spoken decimal separator; guidance passes the app language so a per-app TR language
     *   still says "1,5" on an English system.
     */
    fun text(meters: Double, miles: Boolean, text: (Int) -> String, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        if (!miles) {
            val m = roundMetres(meters)
            return if (m >= 1000) String.format(locale, text(com.ridedeck.app.R.string.spoken_km), decimal(m / 1000.0, locale))
            else String.format(locale, text(com.ridedeck.app.R.string.spoken_m), m)
        }
        val feet = meters * 3.28084
        if (feet < 1000) {
            val f = (Math.round(feet / 50.0) * 50).toInt().coerceAtLeast(50)
            return String.format(locale, text(com.ridedeck.app.R.string.spoken_ft), f)
        }
        val mi = Math.round(meters / 1609.344 * 10) / 10.0
        return String.format(locale, text(com.ridedeck.app.R.string.spoken_mi), decimal(mi, locale))
    }

    /** "2" for a whole number, "1,5" / "1.5" (locale) otherwise. */
    private fun decimal(v: Double, locale: java.util.Locale): String =
        if (v == Math.floor(v)) v.toInt().toString() else String.format(locale, "%.1f", v)
}

/** Matching a GPS fix onto the route, and deciding when the driver has really left it. */
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

    /** After this long without a usable fix the whole route is searched: the vehicle may be anywhere along it. */
    const val FULL_SEARCH_GAP_S = 30.0

    /**
     * How many segments ahead of [from] to search after [gapS] seconds without a match at [speedMps]: the distance
     * the vehicle could have covered (with half again as margin) turned into segments, never fewer than [minimum].
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

/** Why a previewed route may not start guidance. */
enum class RouteStartBlock { NONE, OFF_START }

/** A usable start position on the route, or the reason the preview cannot start. */
data class RouteStartCheck(val block: RouteStartBlock, val startIndex: Int = 0)

/**
 * Start-position sanity for a freshly computed route.
 *
 * A route is computed from the then-current fix. If the driver has since moved off that road (or the
 * preview was left open for a long time), starting it would guide from a road the vehicle is not on.
 * Only the first [SEARCH_CORRIDOR_M] metres of the route are searched, and the projection must be
 * within the normal off-route distance and no further along than [MAX_ALONG_M]. Within that corridor
 * guidance starts at the matched segment, so a driver who rolled a few hundred metres along the same
 * preview route is not blocked. This is a pure function so tests need no GPS or network.
 */
object RouteStartGuard {
    /** The start (or a position within the first stretch) must be this close along the route. */
    const val MAX_ALONG_M = 500.0
    /** Only the beginning of the route is considered; a route that passes nearby later is not a start. */
    const val SEARCH_CORRIDOR_M = 500.0

    /**
     * A route is snapped to the nearest road, so a driver parked in a lot, courtyard or ferry deck can
     * legitimately be tens of metres from the route's first shape point. This allowance lets that
     * driver start without weakening the check for a route left behind far away.
     */
    const val ORIGIN_SNAP_ALLOWANCE_M = 120.0

    fun check(route: Route, fix: GeoPoint, speedMps: Double, accuracyM: Int?): RouteStartCheck {
        if (route.shape.size < 2) return RouteStartCheck(RouteStartBlock.OFF_START)
        var end = 0
        while (end < route.shape.size - 2 && route.cumulative[end + 1] <= SEARCH_CORRIDOR_M) end++
        val match = RouteMatcher.match(route.shape, fix, 0, end, null, speedMps)
        val alongM = route.cumulative[match.index] +
            match.projection.fraction * (route.cumulative[match.index + 1] - route.cumulative[match.index])
        val onRoad = match.projection.distanceM <= RouteMatcher.offRouteM(speedMps, accuracyM)
        // The provider snapped the route away from the driver's current parking spot. If the driver is
        // still at the route's origin, the distance to shape.first() is the snap gap, not a stale route.
        val atOrigin = match.index <= 1 && Geo.distance(fix, route.shape.first()) <= ORIGIN_SNAP_ALLOWANCE_M
        return if ((onRoad && alongM <= MAX_ALONG_M) || atOrigin) {
            RouteStartCheck(RouteStartBlock.NONE, match.index)
        } else {
            RouteStartCheck(RouteStartBlock.OFF_START)
        }
    }

    fun evaluate(route: Route, fix: GeoPoint, speedMps: Double, accuracyM: Int?): RouteStartBlock =
        check(route, fix, speedMps, accuracyM).block
}

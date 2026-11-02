package com.ridedeck.app.data

import kotlin.math.cos
import kotlin.random.Random

/**
 * Turns a [Route] into a stream of GPS fixes one second apart, as a bike would produce them: constant speed along
 * the shape, optional gaussian-ish noise, a gap (tunnel) and a detour. Pure, so tests and a debug replay share it.
 */
object RouteSimulator {
    data class Plan(
        val speedMps: Double = 14.0,
        val noiseM: Double = 0.0,
        val seed: Long = 1,
        /** Fixes are dropped for [gapSeconds] starting [gapAtM] metres into the route (tunnel). */
        val gapAtM: Double = -1.0,
        val gapSeconds: Int = 0,
        /** From [detourAtM] on, the rider is [detourOffsetM] metres to the east of the route for [detourSeconds]. */
        val detourAtM: Double = -1.0,
        val detourOffsetM: Double = 0.0,
        val detourSeconds: Int = 0,
        val startAtMs: Long = 1_000L,
    )

    fun fixes(route: Route, plan: Plan = Plan()): List<LocationState> {
        val out = ArrayList<LocationState>()
        val rnd = Random(plan.seed)
        var t = 0
        var travelled = 0.0
        var detourLeft = 0
        var gapLeft = 0
        var gapDone = false
        var detourDone = false
        val total = route.cumulative.last()
        while (travelled <= total) {
            val (p, bearing) = pointAt(route, travelled)
            if (!detourDone && plan.detourAtM >= 0 && travelled >= plan.detourAtM) { detourLeft = plan.detourSeconds; detourDone = true }
            if (!gapDone && plan.gapAtM >= 0 && travelled >= plan.gapAtM) { gapLeft = plan.gapSeconds; gapDone = true }
            var lat = p.lat
            var lon = p.lon
            if (detourLeft > 0) {
                lon += plan.detourOffsetM / (111_320.0 * cos(Math.toRadians(p.lat)))
                detourLeft--
            }
            if (plan.noiseM > 0) {
                lat += (rnd.nextDouble() - 0.5) * 2 * plan.noiseM / 111_320.0
                lon += (rnd.nextDouble() - 0.5) * 2 * plan.noiseM / (111_320.0 * cos(Math.toRadians(p.lat)))
            }
            if (gapLeft > 0) gapLeft-- else {
                out += LocationState(
                    speedKmh = (plan.speedMps * 3.6).toInt(), speedMps = plan.speedMps.toFloat(),
                    fixAtMs = plan.startAtMs + t * 1000L, lat = lat, lon = lon, bearing = bearing.toFloat(),
                    accuracyM = maxOf(5, plan.noiseM.toInt()),
                )
            }
            t++
            travelled += plan.speedMps
        }
        return out
    }

    /** Position and heading [metres] along the route. */
    fun pointAt(route: Route, metres: Double): Pair<GeoPoint, Double> {
        val c = route.cumulative
        val m = metres.coerceIn(0.0, c.last())
        var i = 0
        while (i < c.size - 2 && c[i + 1] < m) i++
        val seg = c[i + 1] - c[i]
        val f = if (seg == 0.0) 0.0 else (m - c[i]) / seg
        val a = route.shape[i]
        val b = route.shape[i + 1]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f) to Geo.bearing(a, b)
    }
}

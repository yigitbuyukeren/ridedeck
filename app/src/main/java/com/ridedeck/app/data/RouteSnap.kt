package com.ridedeck.app.data

/**
 * Keeps the drawn position marker on the road while the rider is actually on it: the smoothed fix is
 * projected onto the nearby route polyline, so a few metres of GPS noise no longer put the arrow
 * beside the line.
 *
 * This is display-only. Guidance, off-route detection and re-routing keep using the raw fix, and the
 * route progress split is not advanced by it. The marker is pulled onto the line only when the fix is
 * genuinely close and its heading agrees with that stretch of road; a real deviation or turn-off is
 * left where the GPS says, so the rider can see it.
 */
class RouteSnap {
    private var snapped = false
    private var routeRef: Route? = null

    data class Display(val point: GeoPoint, val onRoute: Boolean)

    /**
     * The point to draw for [fix], given the route and the last matched [progressIndex]/[progressFraction].
     * Call once per frame; [snapped] state is carried between calls for hysteresis and reset when [route]
     * changes or becomes null.
     */
    fun display(
        route: Route?,
        progressIndex: Int,
        progressFraction: Double,
        fix: GeoPoint,
        bearing: Double?,
        speedMps: Double,
    ): Display {
        if (route !== routeRef) {
            routeRef = route
            snapped = false
        }
        if (route == null || route.shape.size < 2) {
            snapped = false
            return Display(fix, false)
        }
        val shape = route.shape
        val cumulative = route.cumulative
        val from = progressIndex.coerceIn(0, shape.size - 2)
        val along = cumulative[from] + progressFraction.coerceIn(0.0, 1.0) * (cumulative[from + 1] - cumulative[from])
        val fromM = (along - LOOK_BEHIND_M).coerceAtLeast(0.0)
        val toM = (along + LOOK_AHEAD_M).coerceAtMost(cumulative.last())
        val snapDistanceM = if (snapped) RELEASE_M else ON_ROUTE_M

        // Only the road around the last match: a route that loops back must not attract the marker.
        var start = from
        while (start > 0 && cumulative[start] > fromM) start--
        var end = from
        while (end < shape.size - 2 && cumulative[end + 1] <= toM) end++

        var best: Geo.Projection? = null
        var bestIndex = -1
        var bestScore = Double.MAX_VALUE
        for (i in start..end) {
            val segmentEndM = cumulative[i + 1]
            if (segmentEndM < fromM) continue
            val proj = Geo.project(fix, shape[i], shape[i + 1])
            var score = proj.distanceM
            if (bearing != null && speedMps >= MIN_HEADING_SPEED_MPS && segmentEndM - cumulative[i] > 3.0) {
                val diff = RouteMatcher.angleBetween(Geo.bearing(shape[i], shape[i + 1]), bearing)
                // Heading across the road: the rider has turned off, however close the fix still is.
                if (diff > MAX_ANGLE_DEG) continue
                score += diff / 90.0
            }
            if (score < bestScore) {
                bestScore = score
                best = proj
                bestIndex = i
            }
        }
        val match = best
        if (match == null || match.distanceM > snapDistanceM) {
            snapped = false
            return Display(fix, false)
        }
        val a = shape[bestIndex]
        val b = shape[bestIndex + 1]
        val t = match.fraction
        snapped = true
        return Display(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), true)
    }

    fun reset() {
        snapped = false
        routeRef = null
    }

    private companion object {
        const val ON_ROUTE_M = 15.0
        const val RELEASE_M = 24.0
        /** Route searched around the last match: enough for one GPS gap and the dead-reckoned frames. */
        const val LOOK_BEHIND_M = 50.0
        const val LOOK_AHEAD_M = 150.0
        const val MAX_ANGLE_DEG = 60.0
        const val MIN_HEADING_SPEED_MPS = 2.5
    }
}

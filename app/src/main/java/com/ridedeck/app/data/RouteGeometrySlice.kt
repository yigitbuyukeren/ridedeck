package com.ridedeck.app.data

/**
 * Pure FEAT-004 remaining-route slice. The anchor is the actual controller-proven passed-stop
 * shape index, never an arbitrary GPS progress point. No instruction is synthesized.
 */
object RouteGeometrySlice {
    /**
     * Returns the route from the just-passed stop boundary to the destination, or null when any
     * shape/maneuver/stop invariant cannot be proven. Callers must invalidate rather than guess.
     */
    fun afterPassedStop(route: Route, allStops: List<Place>, stopsPassed: Int): Route? {
        if (stopsPassed <= 0 || stopsPassed > route.stopIndices.size) return null
        if (allStops.size != route.stopIndices.size) return null
        if (route.stopIndices.any { it !in route.shape.indices }) return null
        if (route.stopIndices.zipWithNext().any { (a, b) -> b <= a }) return null
        if (allStops.indices.any { i ->
                Geo.distance(allStops[i].point, route.shape[route.stopIndices[i]]) >
                    RouteGeometrySnapshot.MAX_ENDPOINT_LINK_M
            }
        ) return null

        // Actual stop boundary, not the current GPS progress.
        val anchor = route.stopIndices[stopsPassed - 1]
        if (anchor !in 0 until route.shape.lastIndex) return null
        // A normal turn at the anchor is not a passed-stop boundary.
        if (route.maneuvers.none { it.beginIndex == anchor && it.isStop }) return null

        val newShape = route.shape.drop(anchor)
        if (newShape.size < 2) return null

        val newManeuvers = route.maneuvers
            .filter { it.beginIndex >= anchor && !(it.beginIndex == anchor && it.isStop) }
            .map { it.copy(beginIndex = it.beginIndex - anchor) }
        if (newManeuvers.isEmpty() || newManeuvers.first().beginIndex != 0) return null
        if (newManeuvers.zipWithNext().any { (a, b) -> b.beginIndex < a.beginIndex }) return null

        val remainingStops = allStops.drop(stopsPassed)
        val remainingIndices = route.stopIndices.drop(stopsPassed).map { it - anchor }
        if (remainingStops.size != remainingIndices.size) return null
        if (remainingIndices.any { it <= 0 || it !in 0 until newShape.size }) return null
        if (remainingIndices.zipWithNext().any { (a, b) -> b <= a }) return null
        if (remainingIndices.any { index -> newManeuvers.none { it.isStop && it.beginIndex == index } }) return null
        if (remainingStops.indices.any { i ->
                Geo.distance(remainingStops[i].point, newShape[remainingIndices[i]]) >
                    RouteGeometrySnapshot.MAX_ENDPOINT_LINK_M
            }
        ) return null

        // Duration is the sum of the retained maneuver legs; never a linear distance guess.
        val duration = newManeuvers.sumOf { it.timeS }
        val distance = route.distanceM - route.cumulative[anchor]
        if (!duration.isFinite() || duration < 0 || !distance.isFinite() || distance <= 0) return null

        val sliced = Route(newShape, newManeuvers, distance, duration, remainingIndices)
        if (!sliced.isUsable()) return null
        return sliced
    }
}

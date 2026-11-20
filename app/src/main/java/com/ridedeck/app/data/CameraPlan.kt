package com.ridedeck.app.data

/** Chase camera that backs off with speed and leans in on an approaching manoeuvre. */
object CameraPlan {
    data class Pose(val zoom: Double, val tilt: Double)
    data class VerticalPadding(val top: Double, val bottom: Double)

    /**
     * MapLibre puts the target at the centre of the padded viewport. Keep the usual lower
     * target unless it would overlap the trip bar or the marker's clearance above it.
     */
    fun followPadding(viewHeight: Int, fraction: Double, bottomClearance: Int): VerticalPadding {
        if (viewHeight <= 0) return VerticalPadding(0.0, 0.0)
        val height = viewHeight.toDouble()
        val normal = height * fraction
        val lowestClearTarget = height - bottomClearance.coerceIn(0, viewHeight)
        // Unusually tall overlays still need a target above centre; keep a usable viewport.
        val offset = minOf(normal, 2 * lowestClearTarget - height).coerceAtLeast(-height / 2)
        return VerticalPadding(offset.coerceAtLeast(0.0), (-offset).coerceAtLeast(0.0))
    }

    fun pose(speedKmh: Int, distToNextM: Int, base: Pose = Pose(17.2, 56.0)): Pose {
        val s = speedKmh.coerceIn(20, 110)
        val speedT = (s - 20) / 90.0
        var zoom = base.zoom - 1.5 * speedT
        val tilt = base.tilt - 10.0 * speedT
        if (distToNextM in 1..250) zoom += 0.8 * (1.0 - distToNextM / 250.0)
        return Pose(zoom, tilt)
    }

    /** Moves [current] toward [target] by [rate] of the gap per call, so the zoom glides instead of jumping. */
    fun ease(current: Double, target: Double, rate: Double = 0.08): Double = current + (target - current) * rate
}

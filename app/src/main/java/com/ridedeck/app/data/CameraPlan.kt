package com.ridedeck.app.data

/** Chase camera that backs off with speed and leans in on an approaching manoeuvre. */
object CameraPlan {
    data class Pose(val zoom: Double, val tilt: Double)

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

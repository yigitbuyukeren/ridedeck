package com.ridedeck.app.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns the phone's rotation matrix into a compass heading in degrees (0 = north, clockwise).
 * Upright on a handlebar mount the heading is where the back of the phone faces; lying flat it is where the top
 * of the screen points. Pure maths, so it is unit tested without a sensor.
 */
object CompassMath {
    /** [r] is the row-major 3x3 matrix from `SensorManager.getRotationMatrixFromVector` (device to world: east, north, up). */
    fun heading(r: FloatArray, displayRotation: Int): Float? {
        if (r.size < 9) return null
        val flat = abs(r[8]) > FLAT_Z
        val east: Float
        val north: Float
        if (!flat) {
            east = -r[2]; north = -r[5]
        } else {
            when (displayRotation) {
                1 -> { east = r[0]; north = r[3] }
                2 -> { east = -r[1]; north = -r[4] }
                3 -> { east = -r[0]; north = -r[3] }
                else -> { east = r[1]; north = r[4] }
            }
        }
        if (sqrt(east * east + north * north) < MIN_HORIZONTAL) return null
        return ((Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360) % 360).toFloat()
    }

    /** Eases [current] toward [target] along the short way round the circle; [dtS] seconds since the last step. */
    fun ease(current: Float, target: Float, dtS: Float, tauS: Float = 0.25f): Float {
        val turn = ((target - current + 540f) % 360f) - 180f
        return ((current + turn * (1f - exp(-dtS / tauS)) + 360f) % 360f)
    }

    private const val FLAT_Z = 0.8f
    private const val MIN_HORIZONTAL = 0.15f
}

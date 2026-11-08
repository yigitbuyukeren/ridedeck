package com.ridedeck.app.data

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Signed angle [a,b] gives in degrees, in [-180, 180). */
internal fun shortestTurn(deltaDeg: Double): Double = ((deltaDeg % 360) + 540) % 360 - 180

/**
 * GPS gives one fix a second; drawn as is, the marker and camera hop once a second. This turns the fixes into a
 * position for every frame: it carries the last fix forward along its heading at its speed (dead reckoning) and
 * eases the shown position toward that estimate, so a new fix bends the path instead of snapping it.
 *
 * The camera heading gets the same treatment in one careful direction only: consecutive fixes give a turn rate
 * and between fixes the heading target keeps turning at that rate, so a curve reads as one continuous turn instead
 * of a jerk per fix ("tick tick"). The estimate never steers the drawn position and can never pull the camera
 * further than [MAX_HEADING_EXTRAP_DEG] away from the last fix's own bearing.
 *
 * Pure maths; the caller supplies a monotonic clock in nanoseconds.
 */
class PositionSmoother {
    data class Frame(val lat: Double, val lon: Double, val bearing: Double)

    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixSpeedMps = 0.0
    private var fixBearing: Double? = null
    private var fixAtNanos = 0L
    private var hasFix = false

    private var shownLat = 0.0
    private var shownLon = 0.0
    private var shownBearing = 0.0
    private var shownAtNanos = 0L
    private var started = false

    // Turn-rate estimate from the two most recent bearing fixes; camera heading only.
    private var prevFixBearing: Double? = null
    private var prevFixAtNanos = 0L
    private var turnRateDps = 0.0

    fun onFix(lat: Double, lon: Double, speedMps: Double, bearing: Float?, nowNanos: Long) {
        val nextBearing = bearing?.toDouble()
        val prevBearing = prevFixBearing
        val dtS = (nowNanos - prevFixAtNanos) / 1e9
        turnRateDps = if (nextBearing != null && prevBearing != null &&
            dtS in MIN_RATE_DT_S..MAX_RATE_DT_S
        ) {
            // Shortest-way turn over the gap between fixes; a wild reading is capped.
            shortestTurn(nextBearing - prevBearing)
                .coerceIn(-MAX_TURN_RATE_DPS * dtS, MAX_TURN_RATE_DPS * dtS) / dtS
        } else 0.0
        if (nextBearing != null) {
            prevFixBearing = nextBearing
            prevFixAtNanos = nowNanos
        } else prevFixBearing = null
        fixLat = lat
        fixLon = lon
        fixSpeedMps = speedMps
        fixBearing = nextBearing
        fixAtNanos = nowNanos
        hasFix = true
    }

    /** The position and heading to draw at [nowNanos], or null before the first fix. */
    fun step(nowNanos: Long, fallbackBearing: Double): Frame? {
        if (!hasFix) return null
        val heading = fixBearing
        val moving = heading != null && fixSpeedMps > MIN_MOVING_MPS
        // Position: dead reckoned straight along the last fix's own heading, exactly as before;
        // the turn-rate estimate steers only the camera, never the drawn position.
        val ahead = if (moving) fixSpeedMps * min((nowNanos - fixAtNanos) / 1e9, MAX_AHEAD_S) else 0.0
        val cosLat = cos(Math.toRadians(fixLat))
        val rad = Math.toRadians(heading ?: 0.0)
        val targetLat = fixLat + Math.toDegrees(ahead * cos(rad) / EARTH_RADIUS_M)
        val targetLon = fixLon + Math.toDegrees(ahead * sin(rad) / (EARTH_RADIUS_M * cosLat))
        // Camera heading: between fixes it keeps turning at the estimated rate, but never further than
        // MAX_HEADING_EXTRAP_DEG from the latest fix's own bearing, so a stale or wild estimate cannot
        // turn the camera away from what the GPS last said.
        val targetBearing = when {
            moving -> {
                val dev = shortestTurn(turnRateDps * min((nowNanos - fixAtNanos) / 1e9, MAX_AHEAD_S))
                    .coerceIn(-MAX_HEADING_EXTRAP_DEG, MAX_HEADING_EXTRAP_DEG)
                heading + dev
            }
            heading != null -> heading
            started -> shownBearing
            else -> fallbackBearing
        }

        if (!started) {
            shownLat = targetLat; shownLon = targetLon; shownBearing = targetBearing
            shownAtNanos = nowNanos
            started = true
            return Frame(shownLat, shownLon, shownBearing)
        }
        val dt = ((nowNanos - shownAtNanos) / 1e9).coerceIn(0.0, 0.25)
        shownAtNanos = nowNanos

        val jumpM = hypot(
            Math.toRadians(targetLat - shownLat) * EARTH_RADIUS_M,
            Math.toRadians(targetLon - shownLon) * EARTH_RADIUS_M * cosLat,
        )
        if (jumpM > SNAP_M) {
            shownLat = targetLat; shownLon = targetLon
        } else {
            val k = 1 - exp(-dt / POSITION_TAU_S)
            shownLat += (targetLat - shownLat) * k
            shownLon += (targetLon - shownLon) * k
        }
        val turn = shortestTurn(targetBearing - shownBearing)
        shownBearing = (shownBearing + turn * (1 - exp(-dt / BEARING_TAU_S)) + 360) % 360
        return Frame(shownLat, shownLon, shownBearing)
    }

    fun reset() {
        hasFix = false
        started = false
        prevFixBearing = null
        turnRateDps = 0.0
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val MIN_MOVING_MPS = 0.8
        const val MAX_AHEAD_S = 2.0
        const val SNAP_M = 120.0
        const val POSITION_TAU_S = 0.3
        const val BEARING_TAU_S = 0.45
        const val MIN_RATE_DT_S = 0.2
        const val MAX_RATE_DT_S = 3.0
        const val MAX_TURN_RATE_DPS = 90.0
        const val MAX_HEADING_EXTRAP_DEG = 15.0
    }
}

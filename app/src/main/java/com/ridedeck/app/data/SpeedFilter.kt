package com.ridedeck.app.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Display-only GNSS speed. Updates only on measurements, with time-based smoothing inside the reported noise
 * band. Large, credible changes follow immediately. No acceleration is inferred and no speed is extrapolated:
 * a steady measurement after a launch or brake must not make the gauge overshoot and then bounce back.
 * Suspect jumps need a consistent second measurement; rejected fixes never refresh the last usable speed.
 * Recording, navigation and safety retain the original measurements.
 */
class SpeedFilter {
    private var v = 0.0
    private var lastRaw = 0.0
    private var lastFixMs = 0L
    private var lastInputMs = 0L
    private var started = false
    private var standstill = true
    private var slowFixes = 0
    private var movingFixes = 0
    private var pending: Double? = null
    private var pendingSigma = 0.0

    var lastHeld = false
        private set

    /** [tMs] is the measurement's monotonic clock, never its delivery time. */
    fun onFix(tMs: Long, speedMps: Float, accuracyMps: Float? = null) {
        if (tMs < 0 || !speedMps.isFinite() || speedMps < 0 || (started && tMs <= lastInputMs)) return
        val z = speedMps.toDouble()
        val inputGap = (tMs - lastInputMs) / 1000.0
        val sigma = (accuracyMps?.takeIf { it.isFinite() && it >= 0f } ?: DEFAULT_SIGMA).toDouble()
        lastInputMs = tMs
        lastHeld = false
        if (!started || inputGap > GAP_RESET_S) {
            v = z; lastRaw = z; started = true; lastFixMs = tMs
            pending = null; standstill = true; slowFixes = 0; movingFixes = 0
            updateStandstill(z)
            return
        }

        val jump = abs(z - lastRaw)
        val rate = jump / inputGap
        val suspect = rate > IMPOSSIBLE_ACCEL ||
            (sigma >= POOR_SIGMA && rate > MAX_PLAUSIBLE_POOR_ACCEL && jump > max(3.0, 2 * sigma))
        val corroborated = pending?.let { abs(z - it) <= (2 * minOf(sigma, pendingSigma)).coerceIn(1.5, 3.0) } == true
        if (suspect && !corroborated) {
            pending = z; pendingSigma = sigma; lastHeld = true
            return
        }
        pending = null
        val dt = (tMs - lastFixMs) / 1000.0
        val residual = z - v
        // A convex update cannot overshoot either measurement. A real change outside the noise band follows now.
        val tau = (0.2 + 0.75 * sigma).coerceIn(0.2, 1.5)
        val alpha = if (corroborated || abs(residual) >= max(1.5, 2 * sigma)) 1.0 else 1 - exp(-dt / tau)
        v += alpha * residual
        lastRaw = z
        lastFixMs = tMs
        updateStandstill(z)
    }

    private fun updateStandstill(rawMps: Double) {
        if (standstill) {
            movingFixes = if (rawMps >= ENTER_STANDSTILL_MPS) movingFixes + 1 else 0
            if (rawMps > LEAVE_STANDSTILL_MPS || movingFixes >= 2) {
                standstill = false; slowFixes = 0; movingFixes = 0; v = rawMps
            }
        } else {
            slowFixes = if (rawMps < ENTER_STANDSTILL_MPS) slowFixes + 1 else 0
            if (slowFixes >= 2) { standstill = true; v = 0.0; movingFixes = 0 }
        }
    }

    /** Hold briefly through missing fixes, then show unknown instead of inventing a stop. */
    fun speedOrNull(nowMs: Long): Double? {
        if (!started || nowMs < lastFixMs) return null
        val age = (nowMs - lastFixMs) / 1000.0
        if (standstill) return if (age <= STANDSTILL_HOLD_S) 0.0 else null
        return if (age <= MOVING_HOLD_S) v else null
    }

    fun speedMps(nowMs: Long): Double = speedOrNull(nowMs) ?: 0.0
    fun speedKmh(nowMs: Long): Double = speedMps(nowMs) * 3.6
    fun speedKmhOrNull(nowMs: Long): Double? = speedOrNull(nowMs)?.times(3.6)

    /** Speeds and decisions only, without coordinates. */
    fun debugState(): String = "v=%.2f stand=%b held=%b".format(java.util.Locale.US, v, standstill, lastHeld)

    fun reset() {
        v = 0.0; lastRaw = 0.0; lastFixMs = 0L; lastInputMs = 0L; started = false
        standstill = true; slowFixes = 0; movingFixes = 0; pending = null; pendingSigma = 0.0; lastHeld = false
    }

    companion object {
        const val MOVING_HOLD_S = 3.0
        const val STANDSTILL_HOLD_S = 5.0
        private const val GAP_RESET_S = 3.0
        private const val DEFAULT_SIGMA = 0.5f
        private const val IMPOSSIBLE_ACCEL = 15.0
        private const val MAX_PLAUSIBLE_POOR_ACCEL = 9.0
        private const val POOR_SIGMA = 1.5
        private const val ENTER_STANDSTILL_MPS = 0.55
        private const val LEAVE_STANDSTILL_MPS = 1.0
    }
}

/**
 * Follows the measured speed once per frame with a short exponential transition (about 80 ms), and turns it into a
 * whole number that only changes when the value is clearly past the half, so it does not flicker between two digits.
 */
class SpeedSmoother {
    var value = 0.0
        private set
    private var shown = 0
    private var hadTarget = false

    /** Missing speed hides the readout; the next measured speed starts without a false sweep from zero. */
    fun update(targetKmh: Double?, dtS: Double): Double? {
        if (targetKmh == null || !targetKmh.isFinite() || targetKmh < 0) {
            hadTarget = false
            return null
        }
        step(targetKmh, if (hadTarget) dtS else 0.0)
        hadTarget = true
        return value
    }

    fun step(targetKmh: Double, dtS: Double): Double {
        if (!targetKmh.isFinite() || !dtS.isFinite() || targetKmh < 0) return value
        value = if (dtS <= 0.0 || abs(targetKmh - value) > SNAP_KMH) targetKmh
        else value + (targetKmh - value) * (1 - exp(-dtS / TIME_CONSTANT_S))
        return value
    }

    /** The digit to show for the current [value]: moves only once the value is [HYSTERESIS] past the shown number's half. */
    fun digit(): Int {
        if (abs(value - shown) >= 0.5 + HYSTERESIS) shown = value.roundToInt()
        if (value < 0.3) shown = 0
        return shown
    }

    companion object {
        const val TIME_CONSTANT_S = 0.08
        const val HYSTERESIS = 0.1
        private const val SNAP_KMH = 60.0
    }
}

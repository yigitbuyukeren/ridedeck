package com.ridedeck.app.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Display-only speed estimate: a low-gain alpha-beta filter over the 1 Hz GPS speed (state: speed and a slowly learned
 * acceleration) that is extrapolated by at most half a second between fixes. A fix that jumps further than a vehicle can,
 * or further than 3 m/s from the previous one while the chip admits a poor speed accuracy, is held back once (a drop
 * that braking can explain still pulls the estimate most of the way, so a real hard brake is not a second late); a
 * second one in a row is believed. When fixes stop arriving the last value is kept for a short while and then the
 * speed is unknown ([speedOrNull] is null), never shown as 0. Crash detection, trip recording and the limit warning
 * keep using the raw speed; this is only for what the rider reads.
 */
class SpeedFilter {
    private var v = 0.0
    private var a = 0.0
    private var lastFixMs = 0L
    private var lastRaw = 0.0
    private var started = false
    private var standstill = true
    private var slowFixes = 0
    private var outliers = 0

    /** True when the last fix was held back as an outlier (for the opt-in speed log). */
    var lastHeld = false
        private set

    /** A fix: [tMs] on any steady clock (elapsed realtime), [speedMps] the measured speed, [accuracyMps] its 1-sigma error if the chip reports it. */
    fun onFix(tMs: Long, speedMps: Float, accuracyMps: Float? = null) {
        val z = max(0.0, speedMps.toDouble())
        val gap = (tMs - lastFixMs) / 1000.0
        lastHeld = false
        if (!started || gap > GAP_RESET_S || gap <= 0.0) {
            // First fix, a long hole or a clock hiccup: start over from the measurement.
            v = z; a = 0.0; started = true; outliers = 0; lastRaw = z
            lastFixMs = tMs
            updateStandstill(z)
            return
        }
        val dt = min(gap, 2.0)
        val sigma = (accuracyMps ?: DEFAULT_SIGMA).toDouble().coerceAtLeast(MIN_SIGMA)
        val jump = abs(z - lastRaw)
        val dropped = z < lastRaw
        lastRaw = z
        if ((jump / dt > IMPOSSIBLE_ACCEL || (sigma >= POOR_SIGMA && jump > POOR_JUMP_MPS)) && outliers < 1) {
            outliers++
            lastHeld = true
            lastFixMs = tMs
            // A drop is more likely a real brake than a rise a real launch: braking that physics allows pulls most of the way.
            val pull = if (!dropped) 0.0 else if (jump / dt <= MAX_BRAKE) BRAKE_PULL else SPIKE_PULL
            v = max(0.0, v + pull * (z - v))
            updateStandstill(v)
            return
        }
        outliers = 0
        val pred = v + a * dt
        val residual = z - pred
        // Inside the noise band the measurement only nudges the estimate; a residual beyond it is a real change, so follow it.
        val follow = abs(residual) >= max(3 * sigma, FOLLOW_MIN_MPS)
        val alpha = if (follow) 1.0 else ALPHA
        val beta = if (follow) 0.5 else BETA_SCALE * alpha * alpha / (2 - alpha)
        v = max(0.0, pred + alpha * residual)
        a = (a + beta * residual / dt).coerceIn(-MAX_ACCEL, MAX_ACCEL)
        lastFixMs = tMs
        updateStandstill(z)
    }

    /** Hysteresis for the stop: two slow fixes in a row to be standing, a clearly higher speed to be moving again (no 0/1/0 at a red light). */
    private fun updateStandstill(rawMps: Double) {
        if (standstill) {
            if (rawMps > LEAVE_STANDSTILL_MPS) { standstill = false; slowFixes = 0; v = max(v, rawMps) }
        } else {
            if (rawMps < ENTER_STANDSTILL_MPS) {
                if (++slowFixes >= 2) { standstill = true; v = 0.0; a = 0.0 }
            } else slowFixes = 0
        }
    }

    /**
     * The estimated speed in m/s at [nowMs], or null when it is unknown: no fix yet, or none for [MOVING_HOLD_S] while
     * moving. A stop the fixes themselves reported stays 0 a little longer ([STANDSTILL_HOLD_S]); a stop with live
     * fixes shows 0 for as long as they keep coming, while lost fixes end in unknown, not in a made-up 0.
     * While moving the last value is extrapolated by at most [MAX_EXTRAPOLATE_S], then held.
     */
    fun speedOrNull(nowMs: Long): Double? {
        if (!started) return null
        val age = (nowMs - lastFixMs) / 1000.0
        if (standstill) return if (age <= STANDSTILL_HOLD_S) 0.0 else null
        if (age > MOVING_HOLD_S) return null
        return max(0.0, v + a * age.coerceIn(0.0, MAX_EXTRAPOLATE_S))
    }

    fun speedMps(nowMs: Long): Double = speedOrNull(nowMs) ?: 0.0

    fun speedKmh(nowMs: Long): Double = speedMps(nowMs) * 3.6

    fun speedKmhOrNull(nowMs: Long): Double? = speedOrNull(nowMs)?.times(3.6)

    /** The filter's internal state for the opt-in speed log: no position, only speeds. */
    fun debugState(): String = "v=%.2f a=%.2f stand=%b held=%b".format(java.util.Locale.US, v, a, standstill, lastHeld)

    fun reset() {
        v = 0.0; a = 0.0; started = false; standstill = true; slowFixes = 0; outliers = 0; lastFixMs = 0L; lastRaw = 0.0; lastHeld = false
    }

    companion object {
        const val MAX_ACCEL = 6.0
        const val MAX_EXTRAPOLATE_S = 0.5
        /** Moving: keep the last value this long without a fix (same as the gap after which a late fix restarts the filter). */
        const val MOVING_HOLD_S = 3.0
        /** Standing: a reported stop is believed this long without a fix (the view model drops the fix after 5 s). */
        const val STANDSTILL_HOLD_S = 5.0
        private const val GAP_RESET_S = 3.0
        private const val DEFAULT_SIGMA = 0.5f
        private const val MIN_SIGMA = 0.1
        private const val ALPHA = 0.5
        private const val BETA_SCALE = 0.1
        /** A speed change between two fixes no road vehicle makes (15 m/s per second, over 1.5 g): held once whatever the accuracy. */
        private const val IMPOSSIBLE_ACCEL = 15.0
        private const val POOR_SIGMA = 1.5
        private const val POOR_JUMP_MPS = 3.0
        /** Hardest braking a motorcycle manages (about 0.9 g): a held drop up to this is probably real and pulls 70% of the way, a bigger one 20%. */
        private const val MAX_BRAKE = 9.0
        private const val BRAKE_PULL = 0.7
        private const val SPIKE_PULL = 0.2
        private const val FOLLOW_MIN_MPS = 1.5
        private const val ENTER_STANDSTILL_MPS = 0.55
        private const val LEAVE_STANDSTILL_MPS = 1.0
    }
}

/**
 * Follows the filter's estimate once per frame with a short critically damped lag (about 80 ms), and turns it into a
 * whole number that only changes when the value is clearly past the half, so it does not flicker between two digits.
 */
class SpeedSmoother {
    var value = 0.0
        private set
    private var shown = 0

    fun step(targetKmh: Double, dtS: Double): Double {
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

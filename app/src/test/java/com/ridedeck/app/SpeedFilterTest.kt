package com.ridedeck.app

import com.ridedeck.app.data.SpeedFilter
import com.ridedeck.app.data.SpeedSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SpeedFilterTest {
    private fun feed(f: SpeedFilter, speeds: List<Float>, acc: Float? = 0.4f, startMs: Long = 1000L) {
        speeds.forEachIndexed { i, v -> f.onFix(startMs + i * 1000L, v, acc) }
    }

    @Test
    fun steadySpeedStaysSteady() {
        val f = SpeedFilter()
        feed(f, List(10) { 20f })
        assertEquals(72.0, f.speedKmh(1000L + 9 * 1000L), 1.0)
    }

    @Test
    fun noiseIsDampedBelowTheRawSpread() {
        val f = SpeedFilter()
        val noise = listOf(20.0f, 20.9f, 19.2f, 20.7f, 19.4f, 20.8f, 19.3f, 20.6f, 19.5f, 20.0f)
        feed(f, noise, acc = 1.0f)
        assertTrue(abs(f.speedMps(1000L + 9000L) - 20.0) < 0.9)
    }

    @Test
    fun rampIsLeadByTheExtrapolation() {
        val f = SpeedFilter()
        // 2 m/s per second from 10 m/s: the estimate half a second after a fix is ahead of that fix's raw speed.
        val ramp = List(8) { 10f + 2f * it }
        feed(f, ramp)
        val raw = ramp.last().toDouble()
        assertTrue(f.speedMps(1000L + 7000L + 500L) > f.speedMps(1000L + 7000L) + 0.3)
    }

    @Test
    fun hardBrakeIsBelievedAtOnce() {
        val f = SpeedFilter()
        feed(f, List(6) { 25f })
        f.onFix(1000L + 6000L, 12f, 0.4f)
        assertEquals(12.0, f.speedMps(1000L + 6000L), 2.5)
    }

    @Test
    fun aPoorOutlierIsHeldBackButARepeatIsAccepted() {
        val f = SpeedFilter()
        feed(f, List(6) { 15f })
        f.onFix(7000L, 40f, 5f)
        assertTrue(f.speedMps(7000L) < 20.0)
        f.onFix(8000L, 40f, 5f)
        assertTrue(f.speedMps(8000L) > 25.0)
    }

    @Test
    fun standstillJitterAtALightDoesNotFlicker() {
        val f = SpeedFilter()
        feed(f, List(5) { 10f })
        feed(f, listOf(3f, 0.2f, 0.1f), startMs = 1000L + 5000L)
        feed(f, listOf(0.6f, 0.2f, 0.7f, 0.1f), startMs = 1000L + 8000L)
        assertEquals(0.0, f.speedMps(1000L + 11000L), 0.0)
    }

    @Test
    fun smootherReachesTheTargetAndHoldsTheDigit() {
        val s = SpeedSmoother()
        repeat(60) { s.step(50.0, 1 / 60.0) }
        assertEquals(50.0, s.value, 1.0)
        assertEquals(50, s.digit())
        s.step(50.3, 1 / 60.0)
        assertEquals(50, s.digit())
    }

    /** Feeds [truth] (m/s per second) with seeded Gaussian noise and returns the mean |estimate - truth| in km/h and how often the shown digit changes per second. */
    private fun simulate(truth: List<Double>, sigma: Double, acc: Float, seed: Int = 7, drop: (Int) -> Boolean = { false }): Pair<Double, Double> {
        val rnd = Random(seed)
        val f = SpeedFilter()
        val sm = SpeedSmoother()
        var err = 0.0; var n = 0; var changes = 0; var prev = -1
        val fixes = truth.indices.filter { !drop(it) }
        var next = 0
        var t = 0L
        while (t < truth.size * 1000L) {
            while (next < fixes.size && fixes[next] * 1000L <= t) {
                val i = fixes[next++]
                val noise = (0 until 12).sumOf { rnd.nextDouble() } - 6.0   // ~N(0,1)
                f.onFix(i * 1000L, (truth[i] + sigma * noise).toFloat(), acc)
            }
            sm.step(f.speedKmh(t), 1 / 60.0)
            val d = sm.digit()
            if (prev >= 0 && d != prev) changes++
            prev = d
            if (t >= 3000) { err += abs(sm.value - truth[(t / 1000).toInt()] * 3.6); n++ }
            t += 17
        }
        return err / n to changes / truth.size.toDouble()
    }

    @Test
    fun noisySteadySpeedIsStable() {
        val (err, changes) = simulate(List(60) { 20.0 }, sigma = 1.2, acc = 2.0f)
        assertTrue("err=$err", err < 3.5)
        assertTrue("changes/s=$changes", changes < 3.0)
    }

    @Test
    fun accelerationIsTracked() {
        val (err, _) = simulate(List(60) { 8.0 + 1.5 * it }, sigma = 0.6, acc = 1.0f)
        assertTrue("err=$err", err < 3.5)
    }

    @Test
    fun brakingIsTrackedWithoutHoldingBackRealChanges() {
        val brake = List(20) { 25.0 } + List(20) { maxOf(0.0, 25.0 - 3.0 * it) } + List(20) { 0.0 }
        val (err, _) = simulate(brake, sigma = 0.15, acc = 0.4f)
        assertTrue("err=$err", err < 3.0)
    }

    @Test
    fun hardBrakeWithGoodAccuracyIsNotDelayed() {
        val f = SpeedFilter()
        feed(f, List(8) { 25f })
        f.onFix(9000L, 19f, 0.4f)
        f.onFix(10000L, 13f, 0.4f)
        assertEquals(13.0, f.speedMps(10000L), 3.0)
    }

    @Test
    fun poorAccuracyJumpIsHeldOnceThenAccepted() {
        val f = SpeedFilter()
        feed(f, List(8) { 15f }, acc = 0.5f)
        f.onFix(9000L, 28f, 4f)
        assertEquals(15.0, f.speedMps(9000L), 1.0)
        f.onFix(10000L, 28f, 4f)
        assertTrue(f.speedMps(10000L) > 20.0)
    }

    @Test
    fun impossibleJumpIsHeldEvenWithGoodAccuracy() {
        val f = SpeedFilter()
        feed(f, List(8) { 15f })
        f.onFix(9000L, 40f, 0.3f)
        assertEquals(15.0, f.speedMps(9000L), 1.0)
    }

    @Test
    fun signalLossWhileMovingIsUnknownNotZero() {
        val f = SpeedFilter()
        feed(f, List(8) { 20f })
        val last = 1000L + 7000L
        assertEquals(72.0, f.speedKmh(last + 2000L), 3.0)       // held a little while
        assertEquals(72.0, f.speedKmhOrNull(last + 2900L)!!, 3.0)
        assertNull(f.speedOrNull(last + 3100L))                 // then unknown
        assertNull(f.speedOrNull(last + 30000L))
        f.onFix(last + 8000L, 21f, 0.5f)                        // the next fix restarts from the measurement
        assertEquals(21.0, f.speedMps(last + 8000L), 0.5)
    }

    @Test
    fun noFixYetIsUnknown() {
        assertNull(SpeedFilter().speedOrNull(5000L))
    }

    @Test
    fun realStandstillStaysZeroWhileFixesKeepComingButSignalLossEndsUnknown() {
        val f = SpeedFilter()
        feed(f, List(5) { 12f })
        feed(f, listOf(6f, 1f, 0.1f, 0f, 0.1f), startMs = 1000L + 5000L)
        val last = 1000L + 9000L
        assertEquals(0.0, f.speedOrNull(last + 1000L)!!, 0.0)
        // live fixes at standstill: still 0 a minute later
        var t = last
        repeat(60) { t += 1000L; f.onFix(t, 0.1f, 0.5f) }
        assertEquals(0.0, f.speedOrNull(t + 500L)!!, 0.0)
        // the fixes stop: unknown, not an invented 0
        assertNull(f.speedOrNull(t + (SpeedFilter.STANDSTILL_HOLD_S * 1000).toLong() + 500L))
    }

    @Test
    fun missingFixesKeepASteadySpeedSteady() {
        val (err, changes) = simulate(List(60) { 20.0 }, sigma = 0.6, acc = 1.0f, drop = { it % 5 == 3 })
        assertTrue("err=$err", err < 2.5)
        assertTrue("changes/s=$changes", changes < 3.0)
    }

    @Test
    fun stopAndGoDoesNotLagTheRestart() {
        val f = SpeedFilter()
        feed(f, List(5) { 12f })
        feed(f, listOf(6f, 1f, 0.1f, 0f, 0.1f, 0f), startMs = 1000L + 5000L)
        assertEquals(0.0, f.speedMps(1000L + 11000L), 0.0)
        f.onFix(1000L + 11000L, 3f, 0.4f)
        assertEquals(3.0, f.speedMps(1000L + 11000L), 0.5)
    }

    /** Worst |displayed - true| in km/h while braking from 25 m/s at [decel] m/s², chip claiming [acc], noise [sigma]; fixes at 1 Hz. */
    private fun worstBrakeError(decel: Double, sigma: Double, acc: Float, seed: Int): Double {
        val truth = List(20) { 25.0 } + List(20) { maxOf(0.0, 25.0 - decel * it) } + List(20) { 0.0 }
        val rnd = Random(seed)
        val f = SpeedFilter(); val sm = SpeedSmoother()
        var worst = 0.0; var t = 0L; var next = 0
        while (t < 30_000L) {
            while (next * 1000L <= t) {
                f.onFix(next * 1000L, (truth[next] + sigma * ((0 until 12).sumOf { rnd.nextDouble() } - 6.0)).toFloat(), acc)
                next++
            }
            sm.step(f.speedKmh(t), 1 / 60.0)
            val i = (t / 1000).toInt()
            val now = truth[i] + (truth[i + 1] - truth[i]) * ((t % 1000) / 1000.0)
            if (t >= 20_000L) worst = maxOf(worst, abs(sm.value - now * 3.6))
            t += 17
        }
        return worst
    }

    @Test
    fun hardBrakeOnAPoorGpsIsNotHeldAFullSecond() {
        // Before: ~43 km/h worst case (a whole fix held back); the floor with 1 Hz fixes is ~22 km/h.
        val mean = (0 until 20).map { worstBrakeError(6.0, 1.2, 2.0f, it) }.average()
        assertTrue("worst=$mean", mean < 33.0)
    }

    @Test
    fun spikesOnAPoorGpsStayContained() {
        val rnd = Random(3)
        val f = SpeedFilter()
        var worst = 0.0
        for (i in 0 until 60) {
            val spike = i > 5 && i % 9 == 0
            val z = 20.0 + (if (spike) 11.0 else 0.0) + 0.3 * ((0 until 12).sumOf { rnd.nextDouble() } - 6.0)
            f.onFix(i * 1000L, z.toFloat(), if (spike) 4f else 0.6f)
            if (i > 5) worst = maxOf(worst, abs(f.speedMps(i * 1000L) - 20.0) * 3.6)
        }
        assertTrue("worst=$worst", worst < 15.0)
    }
}

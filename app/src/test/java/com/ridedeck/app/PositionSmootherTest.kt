package com.ridedeck.app

import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.PositionSmoother
import com.ridedeck.app.data.shortestTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PositionSmootherTest {
    private val frameNs = 16_666_667L

    @Test
    fun nothingBeforeTheFirstFix() {
        assertNull(PositionSmoother().step(0L, 0.0))
    }

    @Test
    fun movesEveryFrameBetweenFixes() {
        val s = PositionSmoother()
        // 20 m/s due north, one fix at t=0.
        s.onFix(41.0, 29.0, 20.0, 0f, 0L)
        var last = s.step(0L, 0.0)!!
        var moved = 0
        for (i in 1..60) {
            val f = s.step(i * frameNs, 0.0)!!
            if (f.lat > last.lat) moved++
            last = f
        }
        assertEquals(60, moved)
    }

    @Test
    fun stepsStaySmallComparedWithAFixHop() {
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 20.0, 0f, 0L)
        var last = s.step(0L, 0.0)!!
        var biggest = 0.0
        for (i in 1..59) last = s.step(i * frameNs, 0.0)!!
        for (sec in 1..5) {
            val fixNs = sec * 1_000_000_000L
            s.onFix(41.0 + 20.0 * sec / 111_195.0, 29.0, 20.0, 0f, fixNs)
            for (i in 1..60) {
                val f = s.step(fixNs + i * frameNs, 0.0)!!
                biggest = maxOf(biggest, Geo.distance(GeoPoint(last.lat, last.lon), GeoPoint(f.lat, f.lon)))
                last = f
            }
        }
        // A raw fix hop is 20 m; per-frame moves must be near 20/60 m.
        assertTrue("biggest step $biggest", biggest < 1.0)
    }

    @Test
    fun standingStillDoesNotDrift() {
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 0.0, 90f, 0L)
        val a = s.step(0L, 0.0)!!
        val b = s.step(2_000_000_000L, 0.0)!!
        assertEquals(a.lat, b.lat, 1e-12)
        assertEquals(a.lon, b.lon, 1e-12)
    }

    @Test
    fun bearingTurnsTheShortWay() {
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 10.0, 350f, 0L)
        s.step(0L, 0.0)
        s.onFix(41.0, 29.0, 10.0, 10f, frameNs)
        val f = s.step(2 * frameNs, 0.0)!!
        assertTrue("bearing ${f.bearing}", f.bearing > 350.0 || f.bearing < 10.0)
    }

    @Test
    fun negativeHeadingDiffsTurnTheShortWay() {
        // Kotlin's % keeps the dividend's sign; the shortest-angle helper must handle negative
        // differences (counterclockwise) and both directions across the 0/360 seam.
        assertEquals(-20.0, shortestTurn(10.0 - 30.0), 1e-9)
        assertEquals(20.0, shortestTurn(30.0 - 10.0), 1e-9)
        assertEquals(20.0, shortestTurn(-340.0), 1e-9)
        assertEquals(-20.0, shortestTurn(340.0), 1e-9)
        assertEquals(-180.0, shortestTurn(180.0), 1e-9)
        assertEquals(-180.0, shortestTurn(-180.0), 1e-9)        // Fix-estimator style inputs across the seam either way.
        assertEquals(20.0, shortestTurn(10.0 - 350.0), 1e-9)
        assertEquals(-20.0, shortestTurn(350.0 - 10.0), 1e-9)

        // 8 then 352 is a 16 deg counterclockwise turn through 0: the heading must fall short-way.
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 10.0, 8f, 0L)
        s.step(0L, 0.0)
        s.onFix(41.0, 29.0, 10.0, 352f, 1_000_000_000L)
        val f = s.step(2_000_000_000L, 0.0)!!
        assertTrue(
            "bearings 8 to 352 must fall short-way through 0, got ${f.bearing}",
            f.bearing in 348.0..356.0,
        )
    }

    /**
     * Feeds 1 Hz fixes with the given headings while stepping 60 fps frames in between, exactly as
     * the app does, and hands every drawn frame to [onFrame]. If [framesAfterLast] > 0 the fixes end
     * after the list and the frames keep running from the stale last fix, as with lost GPS.
     */
    private fun runCurve(
        bearings: List<Double>,
        speedMps: Double,
        framesAfterLast: Int = 0,
        onFrame: (Long, PositionSmoother.Frame) -> Unit,
    ) {
        val s = PositionSmoother()
        var t = 0L
        for ((i, b) in bearings.withIndex()) {
            if (i == 0) {
                s.onFix(41.0, 29.0, speedMps, b.toFloat(), t)
                onFrame(t, s.step(t, 0.0)!!)
                continue
            }
            repeat(60) {
                t += frameNs
                onFrame(t, s.step(t, 0.0)!!)
            }
            t = i * 1_000_000_000L
            s.onFix(41.0, 29.0, speedMps, b.toFloat(), t)
        }
        repeat(framesAfterLast) {
            t += frameNs
            onFrame(t, s.step(t, 0.0)!!)
        }
    }

    @Test
    fun curveBearingsTurnAtASteadyRateNotInAJerkPerFix() {
        // 10 m/s round a 15 deg/s bend: fixes at 0, 15, 30, ... degrees. Frames with both ends inside
        // the steady part of the curve (between the 4th and 6th fix) must turn at an even pace — not
        // fast just after each fix and dying before the next one, which is the "tick tick" feel.
        val deltas = mutableListOf<Double>()
        var last: PositionSmoother.Frame? = null
        var firstBearing = Double.NaN
        runCurve(bearings = listOf(0.0, 15.0, 30.0, 45.0, 60.0, 75.0), speedMps = 10.0) { t, f ->
            if (t >= 3_500_000_000L && t <= 4_950_000_000L) {
                val prev = last
                if (prev == null) firstBearing = f.bearing
                else deltas.add(shortestTurn(f.bearing - prev.bearing))
                last = f
            }
        }
        val biggest = deltas.max()!!
        val smallest = deltas.min()!!
        assertTrue("choppiest frame ${biggest}deg vs quietest ${smallest}deg", biggest < smallest * 2.0)
        val turned = shortestTurn(last!!.bearing - firstBearing)
        assertTrue("turned $turned deg in 1.45 s of a 15 deg/s curve", turned in 17.0..27.0)
    }

    @Test
    fun wildHeadingJumpsDoNotSpinTheCamera() {
        // Fixes 1 s apart demanding a 240 deg/s swing: the estimated rate is capped and the
        // extrapolated target may only sit MAX_HEADING_EXTRAP_DEG past the last fix's own bearing,
        // so however wild the readings the camera cannot be spun away from the GPS.
        var swung = 0.0
        var before = Double.NaN
        runCurve(bearings = listOf(0.0, 240.0, 480.0, 720.0), speedMps = 10.0) { t, f ->
            if (t == 2_000_000_000L) before = f.bearing
            if (t == 3_000_000_000L) swung = shortestTurn(f.bearing - before)
        }
        assertTrue("swung $swung deg in one second", swung < 20.0)
    }

    @Test
    fun staleGpsKeepsTheCameraWithinTheDeviationCap() {
        // Two fixes establish a 45 deg/s rate, then GPS dies at the 90 deg fix. Dead reckoning keeps
        // turning at the rate but never more than MAX_HEADING_EXTRAP_DEG past the last fix's bearing,
        // and stops entirely once the 2 s horizon runs out. Samples use > bounds because the frame
        // cursor never lands exactly on a second boundary.
        var rotTo3 = Double.NaN
        var rotTo4 = Double.NaN
        var rotTo5 = Double.NaN
        runCurve(bearings = listOf(0.0, 45.0, 90.0), speedMps = 10.0, framesAfterLast = 180) { t, f ->
            if (t >= 3_000_000_000L && rotTo3.isNaN()) rotTo3 = shortestTurn(f.bearing - 90.0)
            if (t >= 4_000_000_000L && rotTo4.isNaN()) rotTo4 = shortestTurn(f.bearing - 90.0)
            if (t >= 5_000_000_000L && rotTo5.isNaN()) rotTo5 = shortestTurn(f.bearing - 90.0)
        }
        assertTrue("rotated $rotTo3 deg past 90 one second after the fix", rotTo3 in 5.0..21.0)
        assertTrue("rotated $rotTo4 deg past 90 with 2 s stale GPS", rotTo4 <= 21.0)
        assertEquals(
            "no further rotation once the 2 s horizon is exhausted",
            rotTo4,
            rotTo5,
            2.0,
        )
    }

    @Test
    fun turningBackStopsTheOldExtrapolationWithinOneFix() {
        // 0, 30, 60 at 30 deg/s, then the rider turns back: next fix at 30. The forward rate may hold
        // until that reverse fix arrives, but bounded by the deviation cap, and the heading must come
        // back down promptly afterwards instead of circling on.
        var reverseArrived = false
        var maxAfterTurn = 0.0
        var bearingAt4 = Double.NaN
        runCurve(bearings = listOf(0.0, 30.0, 60.0, 30.0), speedMps = 10.0, framesAfterLast = 60) { t, f ->
            if (!reverseArrived && t >= 3_000_000_000L) reverseArrived = true
            if (!reverseArrived && t > 2_000_000_000L) maxAfterTurn = maxOf(maxAfterTurn, f.bearing)
            if (t >= 4_000_000_000L && bearingAt4.isNaN()) bearingAt4 = f.bearing
        }
        assertTrue("peak $maxAfterTurn after the reverse fix", maxAfterTurn < 80.0)
        assertTrue("only $bearingAt4 one second after the reverse fix", bearingAt4 < 25.0)
    }

    @Test
    fun headingCrossesZeroTheShortWayWithoutDivingBackwards() {
        // Fixes at 350 then 10 establish a 20 deg/s rate through the 0/360 seam. The eased heading
        // must keep rising through 0 towards the rate-rotated target and never dive back over the seam.
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 10.0, 350f, 0L)
        s.step(0L, 0.0)
        var t = 0L
        var fed = false
        var bearingHalf = Double.NaN
        var bearingLate = Double.NaN
        var worstBackstep = 0.0
        var last = Double.NaN
        while (t <= 3_000_000_000L) {
            if (!fed && t >= 1_000_000_000L) {
                s.onFix(41.0, 29.0, 10.0, 10f, 1_000_000_000L)
                fed = true
            }
            val f = s.step(t, 0.0)!!
            t += frameNs
            if (!fed) {
                // Still on the first fix: the heading must hold near 350, not dive the long way.
                assertTrue("heading left its fix on the long way home: ${f.bearing}", f.bearing in 330.0..351.0)
                continue
            }
            val cur = f.bearing
            if (!last.isNaN() && t <= 2_900_000_000L) {
                // Rising: any change away from the rise must be tiny, never a dive across the seam.
                worstBackstep = maxOf(worstBackstep, -shortestTurn(cur - last))
            }
            last = cur
            if (t >= 1_500_000_000L && t < 1_500_000_000L + frameNs) bearingHalf = cur
            if (t >= 2_900_000_000L) bearingLate = cur
        }
        assertTrue(
            "at +0.5 s past the second fix the heading is $bearingHalf, still easing across the seam upward",
            bearingHalf in 2.0..20.0,
        )
        assertTrue("at +1.9 s the heading is $bearingLate, tracking the rate rotation", bearingLate in 22.0..43.0)
        assertTrue("worst backwards step $worstBackstep deg while rising through the seam", worstBackstep < 0.5)
    }

    @Test
    fun turnRateNeverSteersThePosition() {
        // A bend (heading rising 45 -> 75 across one gap) must not bend the drawn position: the
        // marker keeps dead reckoning straight along the last fix's own heading, exactly as before.
        val latStart = 41.0
        val lonStart = 29.0
        val cosLat = cos(Math.toRadians(latStart))
        var off = 0.0
        var travelled = 0.0
        val s = PositionSmoother()
        var fed = false
        var t = 0L
        s.onFix(latStart, lonStart, 10.0, 45f, 0L)
        s.step(0L, 0.0)
        while (t <= 1_000_000_000L) {
            if (!fed && t >= 1_000_000_000L) {
                s.onFix(latStart, lonStart, 10.0, 75f, 1_000_000_000L)
                fed = true
            }
            if (t < 1_000_000_000L) {
                val f = s.step(t, 0.0)!!
                val x = Math.toRadians(f.lon - lonStart) * 6_371_000.0 * cosLat // metres east
                val y = Math.toRadians(f.lat - latStart) * 6_371_000.0          // metres north
                val d = hypot(x, y)
                travelled = maxOf(travelled, d)
                // On the 45 deg NE ray from the fix, x equals y, however far it has eased along it.
                off = maxOf(off, abs(x - y) / maxOf(d, 1.0))
            } else {
                s.step(t, 0.0)
            }
            t += frameNs
        }
        assertTrue("only eased $travelled m along the heading before the next fix", travelled > 2.0)
        assertTrue("position bent off the last fix's heading by $off relative", off < 0.05)
    }

    @Test
    fun resetClearsTheEstimatedTurnRate() {
        val s = PositionSmoother()
        s.onFix(41.0, 29.0, 10.0, 90f, 0L)
        s.onFix(41.0, 29.0, 10.0, 105f, 1_000_000_000L)
        s.reset()
        s.onFix(41.0, 29.0, 10.0, 90f, 0L)
        val a = s.step(0L, 0.0)!!
        val b = s.step(1_000_000_000L, 0.0)!!
        val crept = abs(shortestTurn(b.bearing - a.bearing))
        assertTrue("bearing crept $crept deg after reset", crept < 1.0)
    }
}

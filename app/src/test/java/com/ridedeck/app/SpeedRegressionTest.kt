package com.ridedeck.app

import com.ridedeck.app.data.SpeedFilter
import com.ridedeck.app.data.SpeedSmoother
import org.junit.Assert.*
import org.junit.Test

class SpeedRegressionTest {
    @Test fun constantFixesAfterAccelerationNeverInventMoreSpeed() {
        val filter = SpeedFilter()
        for (i in 1..6) filter.onFix(i * 1000L, 20f, 0.3f)
        filter.onFix(7000, 24f, 0.3f)
        for (t in 7000L..7900L step 100) {
            assertTrue("Unmeasured acceleration at $t: ${filter.speedMps(t)}", filter.speedMps(t) <= 24.001)
        }
        for (i in 8..15) {
            filter.onFix(i * 1000L, 24f, 0.3f)
            assertTrue("Overshoot after steady fix", filter.speedMps(i * 1000L + 500) <= 24.001)
        }
    }

    @Test fun speedCannotChangeWithoutANewMeasurement() {
        val filter = SpeedFilter()
        filter.onFix(1000, 15f, 0.3f)
        filter.onFix(2000, 20f, 0.3f)
        val measured = filter.speedOrNull(2000)!!
        assertEquals(measured, filter.speedOrNull(2500)!!, 0.0)
        assertEquals(measured, filter.speedOrNull(3900)!!, 0.0)
    }

    @Test fun oppositeSpikesDoNotConfirmEachOther() {
        val filter = SpeedFilter()
        for (i in 1..6) filter.onFix(i * 1000L, 20f, 0.3f)
        filter.onFix(7000, 50f, 4f)
        filter.onFix(8000, 0f, 4f)
        assertEquals(72.0, filter.speedKmhOrNull(8000)!!, 1.0)
        filter.onFix(9000, 20f, 0.3f)
        assertEquals(72.0, filter.speedKmhOrNull(9000)!!, 1.0)
    }

    @Test fun rejectedFixesCannotKeepAnOldSpeedAlive() {
        val filter = SpeedFilter()
        filter.onFix(1000, 20f, 0.3f)
        for (i in 2..5) filter.onFix(i * 1000L, if (i % 2 == 0) 80f else 0f, 4f)
        assertNull(filter.speedOrNull(5000))
    }

    @Test fun sustainedSlowRollingIsNotPermanentlyZero() {
        val filter = SpeedFilter()
        filter.onFix(1000, 0f, 0.2f)
        for (i in 2..6) filter.onFix(i * 1000L, 0.8f, 0.2f)
        assertEquals(2.88, filter.speedKmhOrNull(6000)!!, 0.3)
    }

    @Test fun brakingCannotReboundWithoutANewHigherMeasurement() {
        val filter = SpeedFilter()
        for (i in 1..6) filter.onFix(i * 1000L, 25f, 0.3f)
        filter.onFix(7000, 19f, 0.3f)
        filter.onFix(8000, 19f, 0.3f)
        assertEquals(19.0, filter.speedOrNull(8500)!!, 0.01)
    }

    @Test fun unknownSpeedRecoversAtTheMeasurementWithoutAnArtificialLaunch() {
        val smoother = SpeedSmoother()
        assertEquals(72.0, smoother.update(72.0, 0.016)!!, 0.0)
        assertNull(smoother.update(null, 0.016))
        assertEquals(65.0, smoother.update(65.0, 0.016)!!, 0.0)
        assertEquals(65, smoother.digit())
    }

    @Test fun nonFiniteScreenInputsCannotPoisonTheNextReadout() {
        val smoother = SpeedSmoother()
        smoother.update(72.0, 0.016)
        assertNull(smoother.update(Double.NaN, 0.016))
        assertEquals(65.0, smoother.update(65.0, 0.016)!!, 0.0)
        smoother.step(60.0, Double.NaN)
        assertTrue(smoother.value.isFinite())
    }

    @Test fun impossibleSpikesWithHugeUncertaintyStillNeedConsistentConfirmation() {
        val filter = SpeedFilter()
        filter.onFix(1000, 20f, 0.3f)
        filter.onFix(2000, 80f, 100f)
        filter.onFix(3000, 0f, 100f)
        assertEquals(72.0, filter.speedKmhOrNull(3000)!!, 0.1)
    }

    @Test fun irregularFixIntervalsCannotMakeASteadySpeedOscillate() {
        val filter = SpeedFilter()
        var time = 1000L
        filter.onFix(time, 15f, 0.3f)
        for (gap in listOf(250, 700, 1400, 500, 2000, 900)) {
            time += gap
            filter.onFix(time, 15f, 0.3f)
            assertEquals(54.0, filter.speedKmhOrNull(time + 200)!!, 0.0)
        }
    }

    @Test fun negativeAndFutureClockReadoutsAreNotSpeeds() {
        val filter = SpeedFilter()
        filter.onFix(1000, -1f)
        assertNull(filter.speedOrNull(1000))
        filter.onFix(2000, 20f)
        assertNull(filter.speedOrNull(1999))
    }
}

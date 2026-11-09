package com.ridedeck.app

import com.ridedeck.app.data.ThermalMath
import com.ridedeck.app.data.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTest {
    private val none = ThermalState.NO_OVERRIDE

    @Test
    fun lostGpsOnAHotPhoneDoesNotCountAsStandingStill() {
        var s = ThermalMath.next(ThermalState(), 44f, null, 1_000L, true)
        s = ThermalMath.next(s, 44f, null, 1_000L + 120_000L, true)
        assertTrue(s.brightness > ThermalMath.IDLE_BRIGHTNESS)
        assertEquals(0L, s.stoppedSinceMs)
    }

    @Test
    fun coolPhoneIsNeverTouched() {
        var s = ThermalState()
        // Standing still for ten minutes at 35 degrees: no dimming at all.
        for (t in 0..600 step 10) s = ThermalMath.next(s, 35f, 0, t * 1000L + 1, true)
        assertEquals(none, s.brightness, 0f)
        assertFalse(s.lowPower)
        assertFalse(s.warn)
    }

    @Test
    fun brightnessFallsLinearlyBetween42And46Degrees() {
        assertEquals(1f, ThermalMath.brightnessCap(41f), 1e-6f)
        assertEquals(1f, ThermalMath.brightnessCap(42f), 1e-6f)
        assertEquals(0.675f, ThermalMath.brightnessCap(44f), 1e-3f)
        assertEquals(ThermalMath.MIN_BRIGHTNESS, ThermalMath.brightnessCap(46f), 1e-6f)
        assertEquals(ThermalMath.MIN_BRIGHTNESS, ThermalMath.brightnessCap(50f), 1e-6f)
    }

    @Test
    fun hotPhoneWhileRidingDimsToTheCapAndSlowsTheMap() {
        val s = ThermalMath.next(ThermalState(), 44f, 60, 1_000L, true)
        assertEquals(0.675f, s.brightness, 1e-3f)
        assertTrue(s.lowPower)
    }

    @Test
    fun hotPhoneGoesNearlyDarkAfterAMinuteStopped() {
        var s = ThermalMath.next(ThermalState(), 43f, 0, 1_000L, true)
        assertTrue(s.brightness > ThermalMath.IDLE_BRIGHTNESS)
        s = ThermalMath.next(s, 43f, 0, 1_000L + 59_000L, true)
        assertTrue(s.brightness > ThermalMath.IDLE_BRIGHTNESS)
        s = ThermalMath.next(s, 43f, 0, 1_000L + 60_000L, true)
        assertEquals(ThermalMath.IDLE_BRIGHTNESS, s.brightness, 0f)
    }

    @Test
    fun movingBringsTheScreenBack() {
        var s = ThermalMath.next(ThermalState(), 43f, 0, 1_000L, true)
        s = ThermalMath.next(s, 43f, 0, 70_000L, true)
        assertEquals(ThermalMath.IDLE_BRIGHTNESS, s.brightness, 0f)
        s = ThermalMath.next(s, 43f, 25, 71_000L, true)
        assertEquals(ThermalMath.brightnessCap(43f), s.brightness, 1e-6f)
        assertEquals(0L, s.stoppedSinceMs)
    }

    @Test
    fun warningStartsAt45AndClearsBelow43() {
        var s = ThermalMath.next(ThermalState(), 44.9f, 50, 1L, true)
        assertFalse(s.warn)
        s = ThermalMath.next(s, 45f, 50, 2L, true)
        assertTrue(s.warn)
        s = ThermalMath.next(s, 43.5f, 50, 3L, true)
        assertTrue(s.warn)
        s = ThermalMath.next(s, 42.9f, 50, 4L, true)
        assertFalse(s.warn)
    }

    @Test
    fun disabledGuardResetsEverything() {
        val hot = ThermalMath.next(ThermalState(), 47f, 0, 1L, true)
        assertTrue(hot.warn)
        assertEquals(ThermalState(), ThermalMath.next(hot, 47f, 0, 2L, false))
    }
}

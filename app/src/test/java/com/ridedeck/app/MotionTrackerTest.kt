package com.ridedeck.app

import com.ridedeck.app.data.Motion
import com.ridedeck.app.data.MotionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {
    @Test
    fun oneNoisyFixAtAStandstillDoesNotLock() {
        val t = MotionTracker()
        t.update(0.0, 1); t.update(0.0, 2)
        assertEquals(Motion.STOPPED, t.motion)
        assertEquals(Motion.STOPPED, t.update(6.0, 3))
        assertEquals(Motion.STOPPED, t.update(0.0, 4))
        assertFalse(MotionTracker.inputLocked(false, t.motion))
    }

    @Test
    fun twoMovingFixesLockAndTwoSlowOnesUnlock() {
        val t = MotionTracker()
        t.update(0.0, 1); t.update(0.0, 2)
        t.update(12.0, 3)
        assertEquals(Motion.STOPPED, t.motion)
        assertEquals(Motion.MOVING, t.update(14.0, 4))
        assertTrue(MotionTracker.inputLocked(false, t.motion))
        t.update(3.0, 5)
        assertEquals(Motion.MOVING, t.motion)
        assertEquals(Motion.STOPPED, t.update(0.0, 6))
    }

    @Test
    fun lostSignalIsUnknownAndKeepsTheLockAsItWas() {
        val t = MotionTracker()
        t.update(40.0, 1); t.update(40.0, 2)
        assertEquals(Motion.UNKNOWN, t.update(null, 3))
        assertTrue(MotionTracker.inputLocked(true, t.motion))
        assertFalse(MotionTracker.inputLocked(false, t.motion))
        // Back from the tunnel: the first fix decides.
        assertEquals(Motion.MOVING, t.update(50.0, 4))
    }

    @Test
    fun theSameFixSeenTwiceCountsOnce() {
        val t = MotionTracker()
        t.update(0.0, 1); t.update(0.0, 2)
        t.update(20.0, 3); t.update(20.0, 3); t.update(20.0, 3)
        assertEquals(Motion.STOPPED, t.motion)
    }
}

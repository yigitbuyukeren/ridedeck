package com.ridedeck.app

import com.ridedeck.app.data.FrameThrottle
import org.junit.Assert.*
import org.junit.Test

class FrameThrottleTest {
    @Test fun lowPowerMiniMapDoesNotUpdateAtDisplayRefreshRate() {
        val throttle = FrameThrottle(15)
        val frames = (0 until 120).count { throttle.shouldDraw(it * 8_333_334L) }
        assertEquals(15, frames)
    }

    @Test fun firstFrameAndResumeAreDrawnImmediately() {
        val throttle = FrameThrottle(30)
        assertTrue(throttle.shouldDraw(1_000_000))
        assertFalse(throttle.shouldDraw(2_000_000))
        assertTrue(throttle.shouldDraw(60_000_000_000))
    }
}

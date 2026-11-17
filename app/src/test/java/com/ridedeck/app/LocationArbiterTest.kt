package com.ridedeck.app

import com.ridedeck.app.data.LocationArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen, a ride service and the overlay may all need the one process GPS at the same time. */
class LocationArbiterTest {
    private var starts = 0
    private var stops = 0
    private val arbiter = LocationArbiter({ starts++ }, { stops++ })
    private val screen = Any()
    private val service = Any()

    @Test
    fun twoOwnersSubscribeOnce() {
        arbiter.acquire(screen)
        arbiter.acquire(service)
        assertTrue(arbiter.held)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test
    fun releaseOrderDoesNotStopWhileAnyOwnerRemains() {
        arbiter.acquire(screen)
        arbiter.acquire(service)
        arbiter.release(screen)
        assertTrue(arbiter.held)
        assertEquals(0, stops)
        arbiter.release(service)
        assertFalse(arbiter.held)
        assertEquals(1, stops)
    }

    @Test
    fun duplicateAcquireAndUnknownReleaseAreNoOps() {
        arbiter.acquire(screen)
        arbiter.acquire(screen)
        assertEquals(1, starts)
        arbiter.release(service)
        assertEquals(0, stops)
        assertTrue(arbiter.held)
    }

    @Test
    fun retryRestartsForAnUnchangedOwnerButNeverAfterTheLastRelease() {
        arbiter.acquire(screen)
        arbiter.retry()
        assertEquals(2, starts)
        arbiter.release(screen)
        arbiter.retry()
        assertEquals(2, starts)
        assertEquals(1, stops)
    }
}

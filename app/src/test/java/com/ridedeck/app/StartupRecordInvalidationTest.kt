package com.ridedeck.app

import com.ridedeck.app.data.RouteChoiceReason
import com.ridedeck.app.data.StartupRecordInvalidation
import com.ridedeck.app.data.StartupTripStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupRecordInvalidationTest {
    @Test
    fun stopClearsCurrentRecordSynchronouslyEvenIfNoLongerStartupRecord() {
        val trips = FakeTrips("new-active-trip")
        var geometryClears = 0
        val invalidator = StartupRecordInvalidation(trips) { geometryClears++ }
        invalidator.startupJson = "old-startup-trip"

        invalidator.onRouteChoice(RouteChoiceReason.STOP)

        assertNull(trips.stored)
        assertEquals(1, geometryClears)
    }

    @Test
    fun ordinaryChoiceClearsOnlyStillPendingStartupRecord() {
        val pending = FakeTrips("old-startup-trip")
        var pendingClears = 0
        StartupRecordInvalidation(pending) { pendingClears++ }.apply {
            startupJson = "old-startup-trip"
            onRouteChoice(RouteChoiceReason.DESTINATION)
        }
        assertNull(pending.stored)
        assertEquals(1, pendingClears)

        val newer = FakeTrips("new-active-trip")
        var newerClears = 0
        StartupRecordInvalidation(newer) { newerClears++ }.apply {
            startupJson = "old-startup-trip"
            onRouteChoice(RouteChoiceReason.OPTIONS)
        }
        assertEquals("new-active-trip", newer.stored)
        assertEquals(0, newerClears)
    }

    @Test
    fun startAndCancelSemanticsAreExplicit() {
        val trips = FakeTrips("saved-trip")
        var clears = 0
        val invalidator = StartupRecordInvalidation(trips) { clears++ }
        invalidator.startupJson = "saved-trip"

        invalidator.onRouteChoice(RouteChoiceReason.START)
        assertEquals("saved-trip", trips.stored)
        assertEquals(0, clears)

        invalidator.onRouteChoice(RouteChoiceReason.STOP)
        assertNull(trips.stored)
        assertEquals(1, clears)
    }

    private class FakeTrips(var stored: String?) : StartupTripStore {
        override fun read(): String? = stored
        override fun clearIf(expected: String) {
            if (stored == expected) stored = null
        }
    }
}

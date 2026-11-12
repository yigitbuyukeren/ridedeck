package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.ActiveTripPersistence
import com.ridedeck.app.data.ActiveTripSnapshot
import com.ridedeck.app.data.ActiveTripWrite
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.NavigationTermination
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTripPersistenceTest {
    private val dest = Place("Ev", "Kadıköy", GeoPoint(40.99, 29.03))
    private val finalDest = Place("İş", "", GeoPoint(41.0, 29.0))
    private val s1 = Place("S1", "", GeoPoint(41.01, 29.0))
    private val s2 = Place("S2", "", GeoPoint(41.02, 29.0))
    private val s3 = Place("S3", "", GeoPoint(41.03, 29.0))

    @Test
    fun snapshotKeepsOnlyStopsStillAheadWithTheirOrder() {
        val snapshot = ActiveTripSnapshot.from(
            NavUi(navigating = true, destination = dest, finalDestination = null, stops = listOf(s1, s2, s3), stopsPassed = 1),
            RouteOptions(avoidHighways = true),
        )

        assertEquals(listOf(s2, s3), snapshot.stopsAhead)
        assertEquals(RouteOptions(avoidHighways = true), snapshot.options)
    }

    @Test
    fun activeGuidanceSavesActualOptionsAndFinalDetourState() {
        val snapshot = ActiveTripSnapshot(
            guiding = true,
            destination = Place("Opet", "", GeoPoint(41.005, 29.0)),
            finalDestination = finalDest,
            stopsAhead = listOf(s1, s2),
            options = RouteOptions(avoidTolls = true),
        )

        val write = ActiveTripPersistence.next(wasGuiding = false, snapshot = snapshot, nowMs = 5_000)
        assertTrue(write is ActiveTripWrite.Save)
        val trip = (write as ActiveTripWrite.Save).trip
        assertEquals(finalDest, trip.finalDestination)
        assertEquals(listOf(s1, s2), trip.stops)
        assertEquals(RouteOptions(avoidTolls = true), trip.options)
        assertEquals(5_000L, trip.savedAtMs)
    }

    @Test
    fun invalidStopCountIsClearedInsteadOfBeingSilentlyTruncated() {
        val snapshot = ActiveTripSnapshot(true, dest, null, listOf(s1, s2, s3, Place("S4", "", GeoPoint(41.04, 29.0))), RouteOptions())
        assertEquals(ActiveTripWrite.Clear, ActiveTripPersistence.next(false, snapshot, 1))
    }

    @Test
    fun optionChangeDuringGuidanceProducesANewSaveWithTheNewChoices() {
        val base = ActiveTripSnapshot(true, dest, null, listOf(s1), RouteOptions())
        val updated = base.copy(options = RouteOptions(avoidTolls = true))

        val first = (ActiveTripPersistence.next(false, base, 1) as ActiveTripWrite.Save).trip
        val second = (ActiveTripPersistence.next(true, updated, 2) as ActiveTripWrite.Save).trip
        assertEquals(RouteOptions(), first.options)
        assertEquals(RouteOptions(avoidTolls = true), second.options)
    }

    @Test
    fun endingGuidanceClearsButIdleRefreshDoesNot() {
        val idle = ActiveTripSnapshot(false, null, null, emptyList(), RouteOptions())
        assertEquals(ActiveTripWrite.None, ActiveTripPersistence.next(wasGuiding = false, snapshot = idle, nowMs = 1))
        assertEquals(ActiveTripWrite.Clear, ActiveTripPersistence.next(wasGuiding = true, snapshot = idle, nowMs = 1))
    }

    @Test
    fun cancelledPreviewClearsEvenThoughNoGuidanceStarted() {
        val cancelled = ActiveTripSnapshot(
            guiding = false,
            destination = null,
            finalDestination = null,
            stopsAhead = emptyList(),
            options = RouteOptions(),
            termination = NavigationTermination.USER_CANCELLED,
        )
        assertEquals(ActiveTripWrite.Clear, ActiveTripPersistence.next(wasGuiding = false, snapshot = cancelled, nowMs = 1))
    }

    @Test
    fun restoreGateRechecksAgeAfterTheGpsWait() {
        val saved = ActiveTrip(dest, null, 1_000, listOf(s1), RouteOptions())
        val json = saved.toJson()
        assertTrue(gate(json, json, saved, nowMs = 1_000 + 60_000))
        assertFalse(gate(json, json, saved, nowMs = 1_000 + ActiveTrip.MAX_AGE_MS + 1))
    }

    @Test
    fun restoreGateNeverResurrectsAfterARiderChoiceEvenWhenDestinationIsNull() {
        val saved = ActiveTrip(dest, null, 1_000, listOf(s1), RouteOptions())
        val json = saved.toJson()
        assertFalse(gate(json, json, saved, routeChoicesAtStart = 0, routeChoicesNow = 1))
        assertFalse(gate(json, json, saved, destinationPresent = true))
        assertFalse(gate(json, json, saved, navigating = true))
        assertFalse(gate(json, "newer-record", saved, nowMs = 1_000))
        assertFalse(gate(null, null, null, nowMs = 1_000))
    }

    private fun gate(
        originalJson: String?,
        storedJson: String?,
        saved: ActiveTrip?,
        nowMs: Long = 1_000,
        routeChoicesAtStart: Long = 0,
        routeChoicesNow: Long = 0,
        destinationPresent: Boolean = false,
        navigating: Boolean = false,
    ): Boolean = com.ridedeck.app.data.ActiveTripRestoreGate.shouldRestore(
        originalJson = originalJson,
        storedJson = storedJson,
        saved = saved,
        nowMs = nowMs,
        routeChoicesAtStart = routeChoicesAtStart,
        routeChoicesNow = routeChoicesNow,
        destinationPresent = destinationPresent,
        navigating = navigating,
    )

    @Test
    fun expiredOrMalformedRecordsAreNotRestoredAtStartup() {
        val trip = ActiveTrip(dest, null, 1_000, listOf(s1), RouteOptions())
        assertNull(ActiveTrip.restore(trip.toJson(), 1_000 + ActiveTrip.MAX_AGE_MS + 1))
        assertNull(ActiveTrip.restore("broken", 1_000))
    }
}

package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.CachedRetryPolicy
import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometryReject
import com.ridedeck.app.data.RouteGeometryRestoreResult
import com.ridedeck.app.data.RouteGeometrySnapshot
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.StartupGeometrySource
import com.ridedeck.app.data.StartupNavigationPort
import com.ridedeck.app.data.StartupRestoreCoordinator
import com.ridedeck.app.data.StartupRestoreOutcome
import com.ridedeck.app.data.StartupTripStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRestoreCoordinatorTest {
    private val shape = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.002, 0.0))
    private val route = Route(
        shape,
        listOf(Maneuver(1, "Go", "", "Go", 100.0, 0), Maneuver(4, "Arrive", "", "Arrive", 0.0, 2)),
        200.0,
        30.0,
    )
    private val dest = Place("D", "", shape.last())
    private val options = RouteOptions()
    private val saved = ActiveTrip(dest, null, 1_000, emptyList(), options)
    private val savedJson = saved.toJson()
    private val snapshot = RouteGeometrySnapshot(dest, null, emptyList(), options, 1_000, route)

    @Test
    fun pendingSelectionBeforeStartupDoesNotReadGeometryOrRoute() = runBlocking {
        val trips = FakeTrips(null)
        val geometry = FakeGeometry(RouteGeometryRestoreResult.Restored(snapshot))
        val nav = FakeNav()
        val coordinator = coordinator(trips, geometry, nav)

        assertEquals(StartupRestoreOutcome.ABORTED, coordinator.restore(savedJson, saved, 0))
        assertEquals(0, geometry.calls)
        assertEquals(0, nav.networkCalls)
    }

    @Test
    fun actionOrNewActiveTripDuringIoWinsAndCannotOverwrite() = runBlocking {
        val trips = FakeTrips(savedJson)
        val release = CompletableDeferred<Unit>()
        val geometry = FakeGeometry(RouteGeometryRestoreResult.Restored(snapshot), release)
        val nav = FakeNav()
        val coordinator = coordinator(trips, geometry, nav)
        var outcome: StartupRestoreOutcome? = null
        val job = launch { outcome = coordinator.restore(savedJson, saved, 0) }
        geometry.entered.await()

        nav.routeChoices = 1
        trips.stored = null // owner callback clears old pending record synchronously
        release.complete(Unit)
        job.join()

        assertEquals(StartupRestoreOutcome.ABORTED, outcome)
        assertEquals(0, nav.cachedCalls)
        assertEquals(0, nav.networkCalls)
    }

    @Test
    fun newerActiveTripDuringIoIsNotClearedOrOverwritten() = runBlocking {
        val newer = ActiveTrip(Place("New", "", GeoPoint(1.0, 1.0)), null, 2_000, emptyList(), options)
        val trips = FakeTrips(savedJson)
        val release = CompletableDeferred<Unit>()
        val geometry = FakeGeometry(RouteGeometryRestoreResult.Restored(snapshot), release)
        val nav = FakeNav()
        val coordinator = coordinator(trips, geometry, nav)
        var outcome: StartupRestoreOutcome? = null
        val job = launch { outcome = coordinator.restore(savedJson, saved, 0) }
        geometry.entered.await()

        trips.stored = newer.toJson()
        release.complete(Unit)
        job.join()

        assertEquals(StartupRestoreOutcome.ABORTED, outcome)
        assertEquals(newer.toJson(), trips.stored)
        assertEquals(0, trips.clears)
        assertEquals(0, nav.networkCalls)
    }

    @Test
    fun ttlCrossingDuringIoRejectsTheCachedRecord() = runBlocking {
        val trips = FakeTrips(savedJson)
        val release = CompletableDeferred<Unit>()
        val geometry = FakeGeometry(RouteGeometryRestoreResult.Restored(snapshot), release)
        val nav = FakeNav()
        var now = 1_000L
        val coordinator = StartupRestoreCoordinator(trips, geometry, nav, { ConnectivityStatus.OFFLINE }, { now })
        var outcome: StartupRestoreOutcome? = null
        val job = launch { outcome = coordinator.restore(savedJson, saved, 0) }
        geometry.entered.await()

        now = 1_000L + ActiveTrip.MAX_AGE_MS + 1
        release.complete(Unit)
        job.join()

        assertEquals(StartupRestoreOutcome.ABORTED, outcome)
        assertEquals(0, nav.cachedCalls)
        assertEquals(0, nav.networkCalls)
    }

    @Test
    fun missingGeometryUsesNetworkOnlyWhenValidated() = runBlocking {
        val validatedTrips = FakeTrips(savedJson)
        val validatedNav = FakeNav()
        val validated = coordinator(
            validatedTrips,
            FakeGeometry(RouteGeometryRestoreResult.Missing),
            validatedNav,
            ConnectivityStatus.VALIDATED,
        )
        assertEquals(StartupRestoreOutcome.NETWORK_PREVIEW, validated.restore(savedJson, saved, 0))
        assertEquals(1, validatedNav.networkCalls)
        assertEquals(0, validatedNav.cachedCalls)

        val offlineTrips = FakeTrips(savedJson)
        val offlineNav = FakeNav()
        val offline = coordinator(
            offlineTrips,
            FakeGeometry(RouteGeometryRestoreResult.Rejected(RouteGeometryReject.MALFORMED)),
            offlineNav,
            ConnectivityStatus.OFFLINE,
        )
        assertEquals(StartupRestoreOutcome.NEEDS_NETWORK, offline.restore(savedJson, saved, 0))
        assertEquals(0, offlineNav.networkCalls)
        assertEquals(1, offlineNav.needsNetworkCalls)
    }

    @Test
    fun restoredCacheNeverRoutesRegardlessOfConnectivity() = runBlocking {
        val nav = FakeNav()
        val coordinator = coordinator(
            FakeTrips(savedJson),
            FakeGeometry(RouteGeometryRestoreResult.Restored(snapshot)),
            nav,
            ConnectivityStatus.OFFLINE,
        )

        assertEquals(StartupRestoreOutcome.CACHED_PREVIEW, coordinator.restore(savedJson, saved, 0))
        assertEquals(1, nav.cachedCalls)
        assertEquals(0, nav.networkCalls)
        assertTrue(nav.cachedOk)
    }

    @Test
    fun retryGateUsesExistingMotionLockAndOtherChecks() {
        assertTrue(CachedRetryPolicy.allowed(true, ConnectivityStatus.VALIDATED, false, true, true, true))
        assertTrue(!CachedRetryPolicy.allowed(true, ConnectivityStatus.VALIDATED, true, true, true, true))
        assertTrue(!CachedRetryPolicy.allowed(true, ConnectivityStatus.OFFLINE, false, true, true, true))
        assertTrue(!CachedRetryPolicy.allowed(true, ConnectivityStatus.VALIDATED, false, false, true, true))
        assertTrue(!CachedRetryPolicy.allowed(false, ConnectivityStatus.VALIDATED, false, true, true, true))
    }

    private fun coordinator(
        trips: FakeTrips,
        geometry: FakeGeometry,
        nav: FakeNav,
        connectivity: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
    ) = StartupRestoreCoordinator(trips, geometry, nav, { connectivity }, { 1_000L })

    private class FakeTrips(var stored: String?) : StartupTripStore {
        var clears = 0
        override fun read(): String? = stored
        override fun clearIf(expected: String) {
            if (stored == expected) { stored = null; clears++ }
        }
    }

    private class FakeGeometry(
        private val result: RouteGeometryRestoreResult,
        private val release: CompletableDeferred<Unit>? = null,
    ) : StartupGeometrySource {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        override suspend fun restore(activeTrip: ActiveTrip, nowMs: Long): RouteGeometryRestoreResult {
            calls++
            entered.complete(Unit)
            release?.await()
            return result
        }
    }

    private class FakeNav : StartupNavigationPort {
        var routeChoices = 0L
        var destination = false
        var navigating = false
        var cachedCalls = 0
        var networkCalls = 0
        var needsNetworkCalls = 0
        var cachedOk = true

        override fun routeChoiceCount(): Long = routeChoices
        override fun hasDestination(): Boolean = destination
        override fun isNavigating(): Boolean = navigating
        override fun restoreCachedPreview(snapshot: RouteGeometrySnapshot): Boolean {
            cachedCalls++
            return cachedOk
        }
        override fun restoreNetworkTrip(activeTrip: ActiveTrip) { networkCalls++ }
        override fun markNeedsNetwork() { needsNetworkCalls++ }
    }
}

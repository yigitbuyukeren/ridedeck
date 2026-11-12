package com.ridedeck.app

import com.ridedeck.app.data.DetourContext
import com.ridedeck.app.data.DetourQuoteController
import com.ridedeck.app.data.DetourQuoteSnapshot
import com.ridedeck.app.data.DetourUnavailableReason
import com.ridedeck.app.data.DirectQuoteRequestGate
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourQuoteControllerTest {
    private val origin = GeoPoint(41.0, 29.0)
    private val final = Place("Final", "", GeoPoint(41.01, 29.01))
    private val station = Place("Fuel", "", GeoPoint(41.005, 29.005))

    @Test fun snapshotUsesExistingNavLocationAndOptionsWithoutGpsReads() {
        val nav = NavUi(
            destination = final, route = route(), navigating = true,
            stops = listOf(Place("Stop", "", GeoPoint(41.002, 29.002))),
        )
        val location = LocationState(
            lat = origin.lat, lon = origin.lon, fixAtMs = 1_000L,
            elapsedRealtimeMs = 1_000L, bearing = 90f,
            gpsProviderEnabled = true, locationPermissionGranted = true,
        )
        val snapshot = DetourQuoteSnapshot.from(
            nav = nav, location = location, options = RouteOptions(),
            contextGeneration = 7L, maxStops = 3,
            nowMs = 1_000L, elapsedMs = 1_000L,
        )

        assertTrue(snapshot != null)
        assertEquals(7L, snapshot!!.contextGeneration)
        assertEquals(final, snapshot.final)
        assertEquals(1, snapshot.remainingStops.size)
        assertTrue(snapshot.freshFix)
    }

    @Test fun snapshotFailsClosedWhenFinePermissionOrFreshFixIsUnknown() {
        val nav = NavUi(destination = final, route = route(), navigating = true)
        val location = LocationState(
            lat = origin.lat, lon = origin.lon, fixAtMs = 1_000L,
            elapsedRealtimeMs = 1_000L, gpsProviderEnabled = true,
            locationPermissionGranted = null,
        )
        val snapshot = DetourQuoteSnapshot.from(
            nav = nav, location = location, options = RouteOptions(),
            contextGeneration = 1L, maxStops = 3,
            nowMs = 1_000L, elapsedMs = 1_000L,
        )
        assertTrue(snapshot != null)
        assertFalse(snapshot!!.freshFix)
    }

    @Test fun oneSelectedQuoteAtATimeRejectsSecondActionWithoutOverwritingRunningUi() = runTest {
        val routing = HangingRouting()
        val controller = controller(routing)
        val context = context()

        assertTrue(controller.request(context, station))
        routing.started.await()
        assertFalse(controller.request(context, station))
        assertTrue("running quote UI must stay visible", controller.ui.value.loading)
        assertNull("busy rejection must not replace the running quote", controller.ui.value.quote)
        assertEquals(1, routing.calls)

        controller.close()
    }

    @Test fun motionChangeDuringCallCancelsAndIgnoresTheResult() = runTest {
        var locked = false
        val routing = HangingRouting()
        val controller = controller(routing, motion = { locked })
        assertTrue(controller.request(context(), station))
        routing.started.await()

        locked = true
        controller.onMotionLockChanged(locked)
        advanceUntilIdle()

        assertTrue(routing.cancelled)
        assertEquals(DetourUnavailableReason.MOTION_LOCKED, controller.ui.value.quote?.reason)
    }

    @Test fun selectingStationBWhileAIsInFlightCancelsAAndNeverPublishesAAfterB() = runTest {
        val routing = TwoCallRouting()
        val controller = controller(routing)
        val a = Place("A", "", GeoPoint(41.003, 29.003))
        val b = Place("B", "", GeoPoint(41.004, 29.004))

        assertTrue(controller.request(context(), a))
        routing.startedA.await()
        assertTrue(controller.request(context(), b))
        routing.startedB.await()
        assertEquals(b, controller.ui.value.station)
        assertEquals(true, controller.ui.value.loading)

        routing.gateA.complete(Result.success(routeFor(listOf(origin, a.point, final.point), 1_000.0)))
        advanceUntilIdle()
        assertEquals(b, controller.ui.value.station)
        assertNull("late A result must not publish", controller.ui.value.quote)

        routing.gateB.complete(Result.success(routeFor(listOf(origin, b.point, final.point), 1_300.0)))
        advanceUntilIdle()
        assertEquals(b, controller.ui.value.station)
        assertTrue(controller.ui.value.quote?.available == true)
    }

    @Test fun closeCancelsTheInFlightQuote() = runTest {
        val routing = HangingRouting()
        val controller = controller(routing)
        assertTrue(controller.request(context(), station))
        routing.started.await()

        controller.close()
        advanceUntilIdle()
        assertTrue(routing.cancelled)
        assertNull(controller.ui.value.quote)
    }

    @Test fun invalidatingRouteOrOptionsCancelsAndMarksStale() = runTest {
        val routing = HangingRouting()
        val controller = controller(routing)
        assertTrue(controller.request(context(), station))
        routing.started.await()

        controller.invalidate()
        advanceUntilIdle()
        assertTrue(routing.cancelled)
        assertEquals(DetourUnavailableReason.STALE_CONTEXT, controller.ui.value.quote?.reason)
    }

    @Test fun disposeCancelsWithoutStartingAnotherRequest() = runTest {
        val routing = HangingRouting()
        val controller = controller(routing)
        assertTrue(controller.request(context(), station))
        routing.started.await()

        controller.dispose()
        advanceUntilIdle()
        assertTrue(routing.cancelled)
        assertFalse(controller.request(context(), station))
    }

    private fun CoroutineScope.controller(
        routing: Routing,
        motion: () -> Boolean = { false },
    ) = DetourQuoteController(
        scope = this,
        routing = routing,
        currentContext = { context() },
        motionLocked = motion,
        requestGate = DirectQuoteRequestGate,
        wallNowMs = { 1_000L },
    )

    private fun context() = DetourContext(
        origin = origin,
        fixAtMs = 1_000L,
        final = final,
        remainingStops = emptyList(),
        options = RouteOptions(),
        headingDeg = 90.0,
        language = "tr",
        miles = false,
        maxStops = 3,
        contextGeneration = 1L,
        freshFix = true,
    )

    private fun route(distance: Double = 1_000.0): Route = Route(
        shape = listOf(origin, final.point),
        maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = 10.0)),
        distanceM = distance,
        durationS = 10.0,
    )

    private class TwoCallRouting : Routing {
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val gateA = CompletableDeferred<Result<Route>>()
        val gateB = CompletableDeferred<Result<Route>>()
        private var calls = 0

        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> =
            error("not used")

        override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> =
            Result.success(emptyList())

        override suspend fun routeVia(
            points: List<GeoPoint>,
            options: RouteOptions,
            headingDeg: Double?,
        ): Result<Route> {
            calls++
            return when (calls) {
                1 -> { startedA.complete(Unit); gateA.await() }
                else -> { startedB.complete(Unit); gateB.await() }
            }
        }
    }

    private fun routeFor(points: List<GeoPoint>, distance: Double): Route {
        val stops = (1 until points.size - 1).toList()
        return Route(
            shape = points,
            maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = 10.0)) +
                stops.map { Maneuver(0, "", "", "", 0.0, it, timeS = 10.0, isStop = true) },
            distanceM = distance,
            durationS = 10.0,
            stopIndices = stops,
        )
    }

    private class HangingRouting : Routing {
        val started = CompletableDeferred<Unit>()
        var calls = 0
            private set
        var cancelled = false
            private set

        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> =
            error("not used")

        override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> =
            Result.success(emptyList())

        override suspend fun routeVia(
            points: List<GeoPoint>,
            options: RouteOptions,
            headingDeg: Double?,
        ): Result<Route> {
            calls++
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }
}

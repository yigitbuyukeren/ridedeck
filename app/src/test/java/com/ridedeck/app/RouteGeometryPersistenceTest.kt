package com.ridedeck.app

import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.NavigationTermination
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometryDecision
import com.ridedeck.app.data.RouteGeometryPersistence
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.StartupGeometryFallback
import com.ridedeck.app.data.StartupGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryPersistenceTest {
    private val shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.001, 29.0), GeoPoint(41.002, 29.0))
    private val maneuvers = listOf(
        Maneuver(1, "Go", "", "Go", 100.0, 0),
        Maneuver(4, "Arrive", "", "Arrive", 0.0, 2),
    )
    private val route = Route(shape, maneuvers, 200.0, 30.0)
    private val dest = Place("D", "", shape.last())
    private val options = RouteOptions()

    private fun navigating(route: Route = this.route, stops: List<Place> = emptyList(), passed: Int = 0) =
        NavUi(
            destination = dest,
            route = route,
            navigating = true,
            stops = stops,
            stopsPassed = passed,
            progressIndex = 1,
            progressFraction = 0.4,
            remainingM = 100,
            remainingS = 10,
        )

    @Test
    fun pointProgressChangesDoNotCreateANewSaveDecision() {
        val a = RouteGeometryPersistence.decide(navigating(), options)
        val b = RouteGeometryPersistence.decide(
            navigating().copy(progressIndex = 2, progressFraction = 0.9, remainingM = 5, remainingS = 1, maneuverIndex = 1),
            options,
        )
        assertEquals(a, b)
        assertTrue(a is RouteGeometryDecision.Save)
    }

    @Test
    fun passedStopIndexMismatchInvalidatesInsteadOfGuessing() {
        val stopA = Place("A", "", shape[0])
        val stopB = Place("B", "", shape[1])
        val withStops = route.copy(
            maneuvers = listOf(maneuvers[0], Maneuver(4, "Stop", "", "Stop", 0.0, 1, isStop = true), maneuvers[1]),
            stopIndices = listOf(1, 2),
        )
        val decision = RouteGeometryPersistence.decide(navigating(withStops, listOf(stopA, stopB), passed = 1), options)
        assertEquals(RouteGeometryDecision.Clear, decision)
    }

    @Test
    fun previewFuelCancelAndFinalArrivalDoNotKeepGeometry() {
        assertEquals(RouteGeometryDecision.None, RouteGeometryPersistence.decide(NavUi(destination = dest, route = route), options))
        assertEquals(
            RouteGeometryDecision.Clear,
            RouteGeometryPersistence.decide(navigating().copy(finalDestination = Place("F", "", shape[0])), options),
        )
        assertEquals(
            RouteGeometryDecision.Clear,
            RouteGeometryPersistence.decide(navigating().copy(termination = NavigationTermination.USER_CANCELLED), options),
        )
        assertEquals(
            RouteGeometryDecision.Clear,
            RouteGeometryPersistence.decide(navigating().copy(termination = NavigationTermination.ARRIVED), options),
        )
    }

    @Test
    fun passedStopDecisionSavesSlicedRouteAndPreservesClock() {
        val shape = (0..4).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        val stop = Place("S", "", shape[2])
        val stopRoute = Route(
            shape,
            listOf(
                Maneuver(1, "Go", "", "Go", 100.0, 0, timeS = 10.0),
                Maneuver(4, "Stop", "", "Stop", 0.0, 2, timeS = 0.0, isStop = true),
                Maneuver(1, "Go2", "", "Go2", 100.0, 2, timeS = 10.0),
                Maneuver(4, "Arrive", "", "Arrive", 0.0, 4, timeS = 0.0),
            ),
            400.0,
            20.0,
            listOf(2),
        )
        val ui = navigating(stopRoute, listOf(stop), passed = 1)
        val previous = RouteGeometryPersistence.decide(
            navigating(stopRoute, listOf(stop), passed = 0),
            options,
        ) as RouteGeometryDecision.Save
        val sliced = RouteGeometryPersistence.decide(ui, options) as RouteGeometryDecision.Save

        assertTrue(sliced.key.derived)
        assertEquals(3, sliced.key.route.shape.size)
        assertTrue(sliced.key.stopsAhead.isEmpty())
        assertEquals(1_000L, RouteGeometryPersistence.createdAtMs(previous.key, 1_000L, sliced.key, 5_000L))
    }

    @Test
    fun startupFallbackOnlyUsesNetworkWhenConnectivityIsValidated() {
        assertTrue(StartupGeometryPolicy.fallback(false, ConnectivityStatus.UNKNOWN) == StartupGeometryFallback.NEEDS_NETWORK)
        assertTrue(StartupGeometryPolicy.fallback(false, ConnectivityStatus.UNVALIDATED) == StartupGeometryFallback.NEEDS_NETWORK)
        assertTrue(StartupGeometryPolicy.fallback(false, ConnectivityStatus.OFFLINE) == StartupGeometryFallback.NEEDS_NETWORK)
        assertTrue(StartupGeometryPolicy.fallback(false, ConnectivityStatus.VALIDATED) == StartupGeometryFallback.NETWORK_FALLBACK)
        assertTrue(StartupGeometryPolicy.fallback(true, ConnectivityStatus.OFFLINE) == StartupGeometryFallback.NONE)
        assertTrue(StartupGeometryPolicy.fallback(true, ConnectivityStatus.UNKNOWN) == StartupGeometryFallback.NONE)
    }

    @Test
    fun optionsOnlyKeepsOriginalCreationClockButNewRouteDoesNot() {
        val first = (RouteGeometryPersistence.decide(navigating(), options) as RouteGeometryDecision.Save).key
        val changedOptions = (RouteGeometryPersistence.decide(navigating(), RouteOptions(avoidTolls = true)) as RouteGeometryDecision.Save).key
        assertEquals(1_000L, RouteGeometryPersistence.createdAtMs(first, 1_000L, changedOptions, 5_000L))

        val newRoute = (RouteGeometryPersistence.decide(navigating(route.copy(distanceM = 201.0)), options) as RouteGeometryDecision.Save).key
        assertEquals(5_000L, RouteGeometryPersistence.createdAtMs(first, 1_000L, newRoute, 5_000L))
    }
}

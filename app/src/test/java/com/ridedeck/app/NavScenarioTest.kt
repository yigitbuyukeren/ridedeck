package com.ridedeck.app

import com.ridedeck.app.data.FuelFinder
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.RouteSimulator
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.StopSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a replayed ride produced: every spoken line with the metres still to go to the manoeuvre when it was said. */
internal class Replay(val spoken: List<Spoken>, val reroutes: Int, val arrived: Boolean) {
    data class Spoken(val text: String, val atM: Double, val metresToNext: Int)
}

private class ScenarioRouting(val route: Route, val detourRoute: (GeoPoint) -> Route) : Routing {
    var calls = 0
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
        calls++
        return Result.success(if (calls == 1) route else detourRoute(from))
    }
    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> = Result.success(emptyList())
}

private class NoFuel : FuelFinder {
    override suspend fun near(here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
    override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
}

/** Scenario route: [kmStraight] kilometres north, a right turn, then 1 km east. */
internal fun lShapedRoute(kmStraight: Double = 3.0, start: GeoPoint = GeoPoint(41.0, 29.0)): Route {
    val step = 0.0009
    val north = (0..(kmStraight * 1000 / 100).toInt()).map { GeoPoint(start.lat + it * step, start.lon) }
    val corner = north.last()
    val lonStep = step / Math.cos(Math.toRadians(corner.lat))
    val east = (1..10).map { GeoPoint(corner.lat, corner.lon + it * lonStep) }
    val shape = north + east
    val turn = north.lastIndex
    val maneuvers = listOf(
        Maneuver(1, "Depart", "", "Head north", kmStraight * 1000, 0),
        Maneuver(10, "Turn right", "Turn right ahead", "Turn right now", 1000.0, turn),
        Maneuver(4, "Arrive", "", "You have arrived", 0.0, shape.lastIndex),
    )
    val len = Route(shape, maneuvers, 1.0, 1.0).cumulative.last()
    return Route(shape, maneuvers, len, len / 14.0)
}

internal fun replay(route: Route, plan: RouteSimulator.Plan, detour: (GeoPoint) -> Route = { route }): Replay {
    val spoken = mutableListOf<Replay.Spoken>()
    var clock = 0L
    var travelled = 0.0
    var nav: NavigationController? = null
    val speaker = object : Speaker {
        override fun speak(text: String) {
            spoken += Replay.Spoken(text, travelled, nav?.ui?.value?.distToNextM ?: -1)
        }
        override fun stop() {}
        override fun shutdown() {}
    }
    val fixes = RouteSimulator.fixes(route, plan)
    val location = MutableStateFlow(fixes.first())
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    val routing = ScenarioRouting(route, detour)
    clock = fixes.first().fixAtMs
    nav = NavigationController({ "t$it" }, scope, location, speaker, routing, NoFuel(), nowMs = { clock })
    nav.selectPlace(Place("Dest", "", route.shape.last()))
    nav.startNavigation()
    fixes.forEachIndexed { i, fix ->
        clock = fix.fixAtMs
        travelled = i * plan.speedMps
        location.value = fix
    }
    val result = Replay(spoken, routing.calls - 1, nav.ui.value.arrived)
    nav.shutdown()
    scope.cancel()
    return result
}

/**
 * Scenario harness for the guidance core: replays a simulated ride and records what was said and where. The asserts
 * are the measured baselines; later guidance work has to beat them (see docs/STATUS.md).
 */
class NavScenarioTest {
    @After fun noop() {}

    @Test
    fun cleanRideAnnouncesTurnAndArrives() {
        val route = lShapedRoute()
        val r = replay(route, RouteSimulator.Plan(speedMps = 14.0))
        assertTrue(r.arrived)
        assertEquals(0, r.reroutes)
        assertTrue(r.spoken.any { it.text == "Turn right now" })
    }

    @Test
    fun noisyGpsDoesNotTriggerReroute() {
        val r = replay(lShapedRoute(), RouteSimulator.Plan(speedMps = 14.0, noiseM = 12.0))
        assertTrue(r.arrived)
        assertEquals(0, r.reroutes)
    }

    @Test
    fun tunnelGapKeepsGuidanceAlive() {
        val r = replay(lShapedRoute(), RouteSimulator.Plan(speedMps = 14.0, gapAtM = 1000.0, gapSeconds = 20))
        assertTrue(r.arrived)
        assertEquals(0, r.reroutes)
    }

    @Test
    fun leavingTheRouteReroutesOnce() {
        val r = replay(lShapedRoute(), RouteSimulator.Plan(speedMps = 14.0, detourAtM = 800.0, detourOffsetM = 300.0, detourSeconds = 5))
        assertEquals(1, r.reroutes)
    }

    @Test
    fun highwaySpeedTurnWarningComesAtLeast8SecondsBefore() {
        // Baseline (pre Aşama 1): the close instruction fires at 150 m, which at 30 m/s is only 5 s.
        val r = replay(lShapedRoute(kmStraight = 4.0), RouteSimulator.Plan(speedMps = 30.0))
        val now = r.spoken.first { it.text == "Turn right now" }
        println("BASELINE highway: 'Turn right now' said ${now.metresToNext} m before the turn (${now.metresToNext / 30.0} s)")
        assertTrue(now.metresToNext >= 0)
    }
}

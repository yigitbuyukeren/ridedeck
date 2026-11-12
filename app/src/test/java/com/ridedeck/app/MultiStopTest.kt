package com.ridedeck.app

import com.ridedeck.app.data.FuelFinder
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.StopSource
import com.ridedeck.app.data.TripParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStopTest {

    @Test
    fun legsAreJoinedIntoOneRouteWithStopIndices() {
        // Two legs: (41.000,29.0)->(41.001,29.0) and ->(41.002,29.0), polyline6 built by the app's own decoder inverse.
        fun enc(points: List<GeoPoint>): String {
            val sb = StringBuilder(); var pl = 0; var pn = 0
            fun v(x: Int) { var n = if (x < 0) (x shl 1).inv() else x shl 1; while (n >= 0x20) { sb.append((((n and 0x1f) or 0x20) + 63).toChar()); n = n shr 5 }; sb.append((n + 63).toChar()) }
            for (p in points) { val la = Math.round(p.lat * 1e6).toInt(); val lo = Math.round(p.lon * 1e6).toInt(); v(la - pl); v(lo - pn); pl = la; pn = lo }
            return sb.toString()
        }
        val a = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0005, 29.0), GeoPoint(41.001, 29.0))
        val b = listOf(GeoPoint(41.001, 29.0), GeoPoint(41.0015, 29.0), GeoPoint(41.002, 29.0))
        fun man(type: Int, begin: Int) = JSONObject().put("type", type).put("instruction", "i").put("length", 0.1).put("begin_shape_index", begin).put("time", 10)
        val trip = JSONObject().put("summary", JSONObject().put("length", 0.22).put("time", 40)).put("legs", org.json.JSONArray()
            .put(JSONObject().put("shape", enc(a)).put("maneuvers", org.json.JSONArray().put(man(1, 0)).put(man(5, 2))))
            .put(JSONObject().put("shape", enc(b)).put("maneuvers", org.json.JSONArray().put(man(1, 0)).put(man(4, 2)))))
        val route = TripParser.parse(trip, 1000.0)
        assertEquals(5, route.shape.size)
        assertEquals(listOf(2), route.stopIndices)
        assertEquals(listOf(0, 2, 2, 4), route.maneuvers.map { it.beginIndex })
        assertEquals(listOf(true, false), route.maneuvers.filter { it.type in 4..5 }.map { it.isStop })
        assertTrue(route.isUsable())
    }

    private class ViaRouting(private val offerAlternative: Boolean = false) : Routing {
        val vias = mutableListOf<List<GeoPoint>>()
        val optionsSeen = mutableListOf<RouteOptions>()
        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
            optionsSeen += options
            return Result.success(lShapedRoute(start = from))
        }
        override suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<List<Route>> =
            if (offerAlternative) Result.success(listOf(lShapedRoute(kmStraight = 1.5, start = from))) else Result.success(emptyList())
        override suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions): Result<Route> {
            vias += points
            optionsSeen += options
            val base = lShapedRoute(start = points.first())
            return Result.success(base.copy(stopIndices = listOf(base.shape.size / 2)))
        }
        override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
    }

    private val silent = object : Speaker {
        val said = mutableListOf<String>()
        override fun speak(text: String) { said += text }
        override fun stop() {}
        override fun shutdown() {}
    }
    private val noFuel = object : FuelFinder {
        override suspend fun near(here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
        override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
    }

    @Test
    fun stopsCanBeAddedInPreviewAndWhileGuidingAndRemoved() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 40, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.selectPlace(dest)
        nav.addStop(Place("S1", "", GeoPoint(41.01, 29.0)))
        assertEquals(1, nav.ui.value.stops.size)
        assertEquals(3, routing.vias.last().size)
        assertEquals(dest.point, routing.vias.last().last())
        assertNotNull(nav.ui.value.route)
        nav.startNavigation()
        nav.addStop(Place("S2", "", GeoPoint(41.02, 29.0)))
        assertEquals(listOf("S1", "S2"), nav.ui.value.stops.map { it.name })
        assertEquals(4, routing.vias.last().size)
        assertTrue(nav.ui.value.navigating)
        nav.removeStop(0)
        assertEquals(listOf("S2"), nav.ui.value.stops.map { it.name })
        assertEquals(3, routing.vias.last().size)
        // Never more than three stops ahead.
        repeat(5) { nav.addStop(Place("X$it", "", GeoPoint(41.0 + it * 0.001, 29.0))) }
        assertEquals(3, nav.ui.value.stops.size)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun roadPreferenceChangeInPreviewKeepsTheStops() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 40, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.selectPlace(dest)
        nav.addStop(Place("S1", "", GeoPoint(41.01, 29.0)))
        nav.addStop(Place("S2", "", GeoPoint(41.02, 29.0)))
        nav.setOptions(RouteOptions(avoidHighways = true))
        assertEquals(listOf("S1", "S2"), nav.ui.value.stops.map { it.name })
        // start, S1, S2, destination: the stops went into the new request too.
        assertEquals(4, routing.vias.last().size)
        assertEquals(dest.point, routing.vias.last().last())
        assertNotNull(nav.ui.value.route)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun aFuelDetourKeepsThePlannedStopsForTheLegAfterIt() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 40, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.selectPlace(dest)
        nav.addStop(Place("S1", "", GeoPoint(41.01, 29.0)))
        nav.addStop(Place("S2", "", GeoPoint(41.02, 29.0)))
        nav.startNavigation()
        val pump = Place("Opet", "", GeoPoint(41.005, 29.0))
        nav.detourVia(pump)
        var ui = nav.ui.value
        assertEquals(pump, ui.destination)
        assertEquals(dest, ui.finalDestination)
        assertEquals(listOf("S1", "S2"), ui.stops.map { it.name })
        assertEquals(0, ui.stopsPassed)
        // Nothing can be added to the pump leg; the stops are edited once the trip continues.
        nav.addStop(Place("X", "", GeoPoint(41.004, 29.0)))
        assertEquals(listOf("S1", "S2"), nav.ui.value.stops.map { it.name })
        // Ride the pump leg to its end (fixes carry the test clock), continue: the route goes start -> S1 -> S2 -> D.
        ui.route!!.shape.forEach { pt -> loc.value = LocationState(speedKmh = 30, fixAtMs = 5, lat = pt.lat, lon = pt.lon) }
        assertTrue(nav.ui.value.arrived)
        nav.continueToFinal()
        ui = nav.ui.value
        assertTrue(ui.navigating)
        assertEquals(dest, ui.destination)
        assertEquals(listOf("S1", "S2"), ui.stops.map { it.name })
        assertEquals(4, routing.vias.last().size)
        assertEquals(dest.point, routing.vias.last().last())
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun aRestoredTripRoutesThroughItsRemainingStops() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.restoreTrip(dest, null, listOf(Place("S2", "", GeoPoint(41.02, 29.0))))
        assertEquals(listOf("S2"), nav.ui.value.stops.map { it.name })
        assertEquals(3, routing.vias.last().size)
        assertNotNull(nav.ui.value.route)
        assertTrue(!nav.ui.value.navigating)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun restoredTripAppliesSavedChoicesWithoutWritingGlobalPreferences() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val changed = mutableListOf<RouteOptions>()
        val nav = NavigationController(
            { "t$it" }, scope, loc, silent, routing, noFuel,
            initialOptions = RouteOptions(avoidHighways = false, avoidTolls = false),
            onOptionsChanged = { changed += it },
            nowMs = { 5 },
        )
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        val saved = RouteOptions(avoidHighways = true, avoidTolls = true)
        nav.restoreTrip(dest, null, listOf(Place("S2", "", GeoPoint(41.02, 29.0))), saved)
        assertEquals(saved, nav.options.value)
        assertEquals(saved, routing.optionsSeen.last())
        assertTrue(changed.isEmpty())
        assertFalse(nav.ui.value.navigating)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun legacyRestoredTripKeepsCurrentChoicesWhenNoneWereSaved() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val changed = mutableListOf<RouteOptions>()
        val current = RouteOptions(avoidTolls = true)
        val nav = NavigationController(
            { "t$it" }, scope, loc, silent, routing, noFuel,
            initialOptions = current,
            onOptionsChanged = { changed += it },
            nowMs = { 5 },
        )
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.restoreTrip(dest, null, listOf(Place("S2", "", GeoPoint(41.02, 29.0))), options = null)
        assertEquals(current, nav.options.value)
        assertEquals(current, routing.optionsSeen.last())
        assertTrue(changed.isEmpty())
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun restoredFuelDetourPreviewsTheFinalDestinationAndRemainingStops() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val pump = Place("Opet", "", GeoPoint(41.005, 29.0))
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        val stop = Place("S2", "", GeoPoint(41.02, 29.0))
        val saved = RouteOptions(avoidHighways = true)

        nav.restoreTrip(pump, dest, listOf(stop), saved)

        // A fuel stop is not re-planned; the post-pump leg is previewed with its remaining stops.
        assertEquals(dest, nav.ui.value.destination)
        assertEquals(null, nav.ui.value.finalDestination)
        assertEquals(listOf(stop), nav.ui.value.stops)
        assertEquals(listOf(GeoPoint(41.0, 29.0), stop.point, dest.point), routing.vias.last())
        assertEquals(saved, routing.optionsSeen.last())
        assertFalse(nav.ui.value.navigating)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun restoreTripRejectsActiveGuidanceWithoutChangingOptions() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 40, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val initial = RouteOptions(avoidTolls = true)
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, initialOptions = initial, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        nav.selectPlace(dest)
        nav.startNavigation()

        nav.restoreTrip(Place("X", "", GeoPoint(41.04, 29.02)), null, emptyList(), RouteOptions(avoidHighways = true))

        assertEquals(initial, nav.options.value)
        assertEquals(dest, nav.ui.value.destination)
        assertTrue(nav.ui.value.navigating)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun restoreTripWithoutAFixDoesNotChangeOptions() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 0, lat = 41.0, lon = 29.0))
        val routing = ViaRouting()
        val initial = RouteOptions(avoidTolls = true)
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, initialOptions = initial, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))

        nav.restoreTrip(dest, null, listOf(Place("S2", "", GeoPoint(41.02, 29.0))), RouteOptions(avoidHighways = true))

        assertEquals(initial, nav.options.value)
        assertEquals(null, nav.ui.value.destination)
        assertFalse(nav.ui.value.navigating)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun allExplicitRouteCommandsAreCountedButRestoreIsNotAChoice() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        val routing = ViaRouting(offerAlternative = true)
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { 5 })
        val dest = Place("D", "", GeoPoint(41.03, 29.01))
        val stop = Place("S1", "", GeoPoint(41.01, 29.0))
        assertEquals(0L, nav.userRouteChoiceCount())

        // A delayed process-death restore is not the rider's choice.
        nav.restoreTrip(dest, null)
        assertEquals(0L, nav.userRouteChoiceCount())

        // An accepted alternative pick is a choice (the preview offers the main route plus one).
        assertTrue(nav.ui.value.choices.size >= 2)
        nav.chooseRoute(1)
        assertEquals(1L, nav.userRouteChoiceCount())

        nav.addStop(stop)
        assertEquals(2L, nav.userRouteChoiceCount())
        nav.removeStop(0)
        assertEquals(3L, nav.userRouteChoiceCount())
        nav.setOptions(RouteOptions(avoidTolls = true))
        assertEquals(4L, nav.userRouteChoiceCount())
        nav.startNavigation()
        assertEquals(5L, nav.userRouteChoiceCount())
        nav.stopNavigation()
        assertEquals(6L, nav.userRouteChoiceCount())

        // After Finish there is nothing left to pick or edit: these no-ops must not count.
        nav.chooseRoute(0)
        nav.addStop(stop)
        nav.removeStop(0)
        nav.setOptions(RouteOptions(avoidTolls = true))
        assertEquals(6L, nav.userRouteChoiceCount())

        nav.detourVia(stop)
        assertEquals(7L, nav.userRouteChoiceCount())
        nav.continueToFinal()
        assertEquals(8L, nav.userRouteChoiceCount())
        nav.selectPlace(dest)
        assertEquals(9L, nav.userRouteChoiceCount())
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun aStopLeftFarBehindAfterAGpsJumpCountsAsPassed() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 50, fixAtMs = 1, lat = 41.0, lon = 29.0))
        var clock = 1L
        val base = lShapedRoute(kmStraight = 2.0)
        val stopIdx = 10
        val maneuvers = listOf(
            com.ridedeck.app.data.Maneuver(1, "d", "", "go", 1000.0, 0),
            com.ridedeck.app.data.Maneuver(4, "a", "", "arrived", 0.0, stopIdx, isStop = true),
            com.ridedeck.app.data.Maneuver(1, "d", "", "go2", 1000.0, stopIdx),
            com.ridedeck.app.data.Maneuver(4, "a", "", "arrived", 0.0, base.shape.lastIndex),
        )
        val route = base.copy(maneuvers = maneuvers, stopIndices = listOf(stopIdx))
        val routing = object : Routing {
            override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions) = Result.success(route)
            override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
        }
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { clock })
        nav.selectPlace(Place("D", "", route.shape.last()))
        nav.addStop(Place("S", "", route.shape[stopIdx]))
        nav.startNavigation()
        for (i in 1..3) { clock++; loc.value = LocationState(speedKmh = 50, fixAtMs = clock, lat = route.shape[i].lat, lon = route.shape[i].lon) }
        // Just past the stop without ever having been within 40 m of it: not passed yet.
        clock++; loc.value = LocationState(speedKmh = 50, fixAtMs = clock, lat = route.shape[stopIdx + 1].lat, lon = route.shape[stopIdx + 1].lon)
        assertEquals(0, nav.ui.value.stopsPassed)
        // 400 m beyond it: clearly skipped.
        clock++; loc.value = LocationState(speedKmh = 50, fixAtMs = clock, lat = route.shape[stopIdx + 4].lat, lon = route.shape[stopIdx + 4].lon)
        assertEquals(1, nav.ui.value.stopsPassed)
        nav.shutdown(); scope.cancel()
    }

    @Test
    fun reachingAStopSaysSoAndCountsItAsPassed() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(LocationState(speedKmh = 50, fixAtMs = 1, lat = 41.0, lon = 29.0))
        var clock = 1L
        val base = lShapedRoute(kmStraight = 2.0)
        val stopIdx = 10
        val maneuvers = listOf(
            com.ridedeck.app.data.Maneuver(1, "d", "", "go", 1000.0, 0),
            com.ridedeck.app.data.Maneuver(4, "a", "", "arrived", 0.0, stopIdx, isStop = true),
            com.ridedeck.app.data.Maneuver(1, "d", "", "go2", 1000.0, stopIdx),
            com.ridedeck.app.data.Maneuver(4, "a", "", "arrived", 0.0, base.shape.lastIndex),
        )
        val route = base.copy(maneuvers = maneuvers, stopIndices = listOf(stopIdx))
        val routing = object : Routing {
            override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions) = Result.success(route)
            override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
        }
        val nav = NavigationController({ "t$it" }, scope, loc, silent, routing, noFuel, nowMs = { clock })
        nav.selectPlace(Place("D", "", route.shape.last()))
        nav.addStop(Place("S", "", route.shape[stopIdx]))
        nav.startNavigation()
        for (i in 1..stopIdx + 2) { clock++; loc.value = LocationState(speedKmh = 50, fixAtMs = clock, lat = route.shape[i].lat, lon = route.shape[i].lon) }
        assertTrue(silent.said.toString(), silent.said.contains("t${R.string.stop_approaching}"))
        assertTrue(silent.said.toString(), silent.said.contains("t${R.string.stop_arrived}"))
        // Approaching is said first, reached only at the stop itself.
        assertTrue(silent.said.indexOf("t${R.string.stop_approaching}") < silent.said.indexOf("t${R.string.stop_arrived}"))
        assertEquals(1, nav.ui.value.stopsPassed)
        nav.shutdown(); scope.cancel()
    }
}

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

    private class ViaRouting : Routing {
        val vias = mutableListOf<List<GeoPoint>>()
        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions) = Result.success(lShapedRoute(start = from))
        override suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions): Result<Route> {
            vias += points
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
        assertTrue(silent.said.toString(), silent.said.contains("t${R.string.stop_arrived}"))
        assertEquals(1, nav.ui.value.stopsPassed)
        nav.shutdown(); scope.cancel()
    }
}

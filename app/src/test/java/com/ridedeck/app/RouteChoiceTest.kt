package com.ridedeck.app

import com.ridedeck.app.data.CameraPlan
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChoiceTest {
    private class AltRouting(val main: Route, val alts: List<Route>) : Routing {
        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions) = Result.success(main)
        override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
        override suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions) = Result.success(listOf(main) + alts)
    }

    private val silent = object : Speaker {
        override fun speak(text: String) {}
        override fun stop() {}
        override fun shutdown() {}
    }

    private fun nav(routing: Routing, scope: CoroutineScope): NavigationController {
        val loc = MutableStateFlow(LocationState(speedKmh = 0, fixAtMs = 5, lat = 41.0, lon = 29.0))
        return NavigationController({ "t$it" }, scope, loc, silent, routing, object : com.ridedeck.app.data.FuelFinder {
            override suspend fun near(here: GeoPoint, sources: List<com.ridedeck.app.data.StopSource>) = Result.success(emptyList<com.ridedeck.app.data.FuelStation>())
            override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<com.ridedeck.app.data.StopSource>) = Result.success(emptyList<com.ridedeck.app.data.FuelStation>())
        }, nowMs = { 5 })
    }

    private fun shifted(r: Route, lon: Double, extraM: Double) =
        Route(r.shape.map { GeoPoint(it.lat, it.lon + lon) }, r.maneuvers, r.distanceM + extraM, r.durationS + 60)

    @Test
    fun alternativesArePreviewedAndSelectable() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val main = lShapedRoute()
        val alt = shifted(main, 0.01, 800.0)
        val n = nav(AltRouting(main, listOf(alt, main)), scope)
        n.selectPlace(Place("D", "", main.shape.last()))
        val ui = n.ui.value
        assertEquals(2, ui.choices.size) // duplicate of the main route is dropped
        assertSame(main, ui.route)
        n.chooseRoute(1)
        assertSame(alt, n.ui.value.route)
        n.startNavigation()
        assertTrue(n.ui.value.navigating)
        assertTrue(n.ui.value.choices.isEmpty())
        n.chooseRoute(0) // ignored while guiding
        assertSame(alt, n.ui.value.route)
        n.shutdown(); scope.cancel()
    }

    @Test
    fun noChoicesWhenServiceHasNone() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val main = lShapedRoute()
        val n = nav(AltRouting(main, emptyList()), scope)
        n.selectPlace(Place("D", "", main.shape.last()))
        assertTrue(n.ui.value.choices.isEmpty())
        n.shutdown(); scope.cancel()
    }

    @Test
    fun cameraBacksOffWithSpeedAndLeansInOnTurns() {
        val slow = CameraPlan.pose(30, 0)
        val fast = CameraPlan.pose(110, 0)
        assertTrue(fast.zoom < slow.zoom)
        assertTrue(fast.tilt < slow.tilt)
        assertTrue(CameraPlan.pose(60, 50).zoom > CameraPlan.pose(60, 0).zoom)
        assertEquals(CameraPlan.pose(60, 0).zoom, CameraPlan.pose(60, 400).zoom, 1e-9)
        assertFalse(CameraPlan.ease(15.0, 17.0) >= 17.0)
        assertTrue(CameraPlan.ease(15.0, 17.0) > 15.0)
    }
}

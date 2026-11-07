package com.ridedeck.app

import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteSnap
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSnapTest {
    private fun straight(): Route {
        val shape = (0..10).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        return Route(shape, emptyList(), 1000.0, 100.0)
    }

    /** [metres] east of the route line at [lat] (positive is right of a northbound route). */
    private fun east(lat: Double, metres: Double): GeoPoint =
        GeoPoint(lat, 29.0 + metres / (111_320.0 * cos(Math.toRadians(lat))))

    @Test
    fun nearFixIsDrawnOnTheRoute() {
        val route = straight()
        val fix = east(41.0003, 8.0)
        val shown = RouteSnap().display(route, 0, 0.0, fix, bearing = 0.0, speedMps = 10.0)
        assertTrue(shown.onRoute)
        assertEquals(29.0, shown.point.lon, 1e-9)
        assertEquals(fix.lat, shown.point.lat, 1e-9)
        assertTrue(Geo.distance(fix, shown.point) > 7.0)
    }

    @Test
    fun realDeviationStaysWhereTheGpsSays() {
        val route = straight()
        val fix = east(41.0003, 42.0)
        val shown = RouteSnap().display(route, 0, 0.0, fix, bearing = 0.0, speedMps = 10.0)
        assertFalse(shown.onRoute)
        assertEquals(fix, shown.point)
    }

    @Test
    fun markerDoesNotFlickerAtTheEdge() {
        val route = straight()
        val fix = east(41.0003, 18.0)
        // A fresh marker at 18 m is outside the snap-on band.
        assertFalse(RouteSnap().display(route, 0, 0.0, fix, bearing = 0.0, speedMps = 10.0).onRoute)

        // Once on the line it is kept until the wider release band, so noise around 15 m cannot bounce it.
        val snap = RouteSnap()
        assertTrue(snap.display(route, 0, 0.0, east(41.0002, 5.0), bearing = 0.0, speedMps = 10.0).onRoute)
        assertTrue(snap.display(route, 0, 0.0, fix, bearing = 0.0, speedMps = 10.0).onRoute)
        assertFalse(snap.display(route, 0, 0.0, east(41.0004, 26.0), bearing = 0.0, speedMps = 10.0).onRoute)
    }

    @Test
    fun turningOffTheRouteIsNotHidden() {
        val route = straight()
        // Still only 6 m from the line, but the rider now heads east: that is a real turn, not GPS jitter.
        val fix = east(41.0003, 6.0)
        val shown = RouteSnap().display(route, 0, 0.0, fix, bearing = 90.0, speedMps = 10.0)
        assertFalse(shown.onRoute)
        assertEquals(fix, shown.point)
    }

    @Test
    fun turningWithTheRouteStillSnaps() {
        val shape = listOf(
            GeoPoint(41.0, 29.0),
            GeoPoint(41.001, 29.0),
            GeoPoint(41.001, 29.0004),
            GeoPoint(41.001, 29.001),
        )
        val route = Route(shape, emptyList(), 200.0, 20.0)
        // Just after the right turn, 5 m south of the eastbound stretch, heading east.
        val fix = GeoPoint(41.00095, 29.0003)
        val shown = RouteSnap().display(route, 1, 0.0, fix, bearing = 90.0, speedMps = 10.0)
        assertTrue(shown.onRoute)
        assertEquals(41.001, shown.point.lat, 1e-9)
        assertEquals(fix.lon, shown.point.lon, 1e-9)
    }

    @Test
    fun routeThatLoopsBackDoesNotAttractTheMarker() {
        val shape = listOf(
            GeoPoint(41.0, 29.0),
            GeoPoint(41.009, 29.0),
            GeoPoint(41.009, 29.0004),
            GeoPoint(41.009, 29.001),
            GeoPoint(41.0, 29.001),
        )
        val route = Route(shape, emptyList(), 2100.0, 200.0)
        // 2 m from the southbound return leg, but that leg is more than 150 m ahead of the last match.
        val fix = GeoPoint(41.003, 29.00098)
        val shown = RouteSnap().display(route, 0, 0.0, fix, bearing = 180.0, speedMps = 10.0)
        assertFalse(shown.onRoute)
        assertEquals(fix, shown.point)
    }

    @Test
    fun aDifferentRouteReleasesTheOldSnap() {
        val routeA = straight()
        val snap = RouteSnap()
        assertTrue(snap.display(routeA, 0, 0.0, east(41.0002, 5.0), bearing = 0.0, speedMps = 10.0).onRoute)

        // Same geometry but a new route object (a re-route): the wider release band must not leak over.
        val routeB = Route(routeA.shape, emptyList(), 1000.0, 100.0)
        assertFalse(snap.display(routeB, 0, 0.0, east(41.0003, 18.0), bearing = 0.0, speedMps = 10.0).onRoute)
    }

    @Test
    fun aFixJustBehindTheLastMatchStillSnaps() {
        val route = straight()
        // About 40 m behind the last matched point (index 5), within the look-behind margin.
        val fix = east(41.0 + 460.0 / 111_195.0, 5.0)
        val shown = RouteSnap().display(route, 5, 0.0, fix, bearing = 0.0, speedMps = 10.0)
        assertTrue(shown.onRoute)
        assertEquals(29.0, shown.point.lon, 1e-9)
    }

    @Test
    fun noRouteIsDrawnRaw() {
        val fix = GeoPoint(41.0003, 29.0001)
        assertFalse(RouteSnap().display(null, 0, 0.0, fix, bearing = 0.0, speedMps = 10.0).onRoute)
    }
}

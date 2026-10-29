package com.ridedeck.app

import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun decodesGooglePolylineExample() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
        assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test
    fun distanceOfOneDegreeLatitudeIsAbout111km() {
        val d = Geo.distance(GeoPoint(41.0, 29.0), GeoPoint(42.0, 29.0))
        assertEquals(111_195.0, d, 300.0)
    }

    @Test
    fun projectionOntoSegmentMidpoint() {
        val a = GeoPoint(41.0, 29.0)
        val b = GeoPoint(41.0, 29.01)
        val p = GeoPoint(41.0005, 29.005)
        val proj = Geo.project(p, a, b)
        assertEquals(0.5, proj.fraction, 0.01)
        assertEquals(55.6, proj.distanceM, 2.0)
    }

    @Test
    fun splitRouteMeetsAtTheRidersPosition() {
        val shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0, 29.01), GeoPoint(41.0, 29.02))
        val (behind, ahead) = Geo.splitRoute(shape, 1, 0.5)
        assertEquals(3, behind.size)
        assertEquals(2, ahead.size)
        assertEquals(29.015, behind.last().lon, 1e-9)
        assertEquals(behind.last(), ahead.first())
        assertEquals(shape.last(), ahead.last())
    }

    @Test
    fun splitRouteAtStartLeavesNothingBehind() {
        val shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0, 29.01))
        val (behind, ahead) = Geo.splitRoute(shape, 0, 0.0)
        assertEquals(1, behind.distinct().size)
        assertEquals(shape, ahead)
    }
}

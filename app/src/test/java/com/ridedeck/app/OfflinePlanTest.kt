package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflinePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlanTest {
    private val istanbul = GeoPoint(41.0, 29.0)

    @Test
    fun biggerRadiusNeedsMoreTilesAndStaysUnderTheLimit() {
        val a = OfflinePlan.tileCount(istanbul, 15)
        val b = OfflinePlan.tileCount(istanbul, 30)
        val c = OfflinePlan.tileCount(istanbul, 60)
        assertTrue(a < b && b < c)
        assertTrue("60 km needs $c tiles", c < 200_000)
        assertTrue(OfflinePlan.estimateMb(c) > OfflinePlan.estimateMb(a))
    }

    @Test
    fun cornersSpanTwiceTheRadius() {
        val (sw, ne) = OfflinePlan.corners(istanbul, 30)
        assertEquals(60000.0, com.ridedeck.app.data.Geo.distance(sw.copy(lon = istanbul.lon), ne.copy(lon = istanbul.lon)), 1000.0)
        assertTrue(sw.lon < istanbul.lon && ne.lon > istanbul.lon)
    }

    @Test
    fun coverageAndPersistence() {
        val area = OfflineArea("x", istanbul, 30, 5L)
        assertTrue(OfflinePlan.covered(listOf(area), GeoPoint(41.1, 29.1)))
        assertFalse(OfflinePlan.covered(listOf(area), GeoPoint(39.9, 32.8)))
        assertEquals(listOf(area), OfflinePlan.fromJson(OfflinePlan.toJson(listOf(area))))
        assertTrue(OfflinePlan.fromJson("broken").isEmpty())
    }
}

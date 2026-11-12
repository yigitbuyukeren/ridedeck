package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometryKey
import com.ridedeck.app.data.RouteGeometryPersistence
import com.ridedeck.app.data.RouteGeometrySlice
import com.ridedeck.app.data.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometrySliceTest {
    private val shape = (0..10).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
    private val stops = listOf(
        Place("S1", "", shape[3]),
        Place("S2", "", shape[6]),
        Place("S3", "", shape[9]),
    )
    private val maneuvers = listOf(
        Maneuver(1, "Go1", "", "Go1", 300.0, 0, timeS = 10.0),
        Maneuver(4, "Stop1", "", "Stop1", 0.0, 3, timeS = 0.0, isStop = true),
        Maneuver(1, "Go2", "", "Go2", 300.0, 3, timeS = 10.0),
        Maneuver(4, "Stop2", "", "Stop2", 0.0, 6, timeS = 0.0, isStop = true),
        Maneuver(1, "Go3", "", "Go3", 300.0, 6, timeS = 10.0),
        Maneuver(4, "Stop3", "", "Stop3", 0.0, 9, timeS = 0.0, isStop = true),
        Maneuver(1, "Go4", "", "Go4", 300.0, 9, timeS = 10.0),
        Maneuver(4, "Arrive", "", "Arrive", 0.0, 10, timeS = 0.0),
    )
    private val route = Route(shape, maneuvers, 1_000.0, 40.0, listOf(3, 6, 9))

    @Test
    fun slicesAfterOneTwoAndThreePassedStops() {
        val afterOne = RouteGeometrySlice.afterPassedStop(route, stops, 1)
        assertNotNull(afterOne)
        assertEquals(8, afterOne!!.shape.size)
        assertEquals(listOf(3, 6), afterOne.stopIndices)
        assertEquals(route.distanceM - route.cumulative[3], afterOne.distanceM, 0.0)
        assertEquals(30.0, afterOne.durationS, 0.0)
        assertEquals(1, afterOne.maneuvers.count { it.beginIndex == 0 })
        assertEquals(maneuvers[2].copy(beginIndex = 0), afterOne.maneuvers.first())
        assertEquals("Go2", afterOne.maneuvers.first().instruction)
        assertEquals("Go2", afterOne.maneuvers.first().verbal)
        assertEquals(maneuvers[2].alert, afterOne.maneuvers.first().alert)

        val afterTwo = RouteGeometrySlice.afterPassedStop(route, stops, 2)
        assertNotNull(afterTwo)
        assertEquals(5, afterTwo!!.shape.size)
        assertEquals(listOf(3), afterTwo.stopIndices)
        assertEquals(route.distanceM - route.cumulative[6], afterTwo.distanceM, 0.0)
        assertEquals(20.0, afterTwo.durationS, 0.0)

        val afterThree = RouteGeometrySlice.afterPassedStop(route, stops, 3)
        assertNotNull(afterThree)
        assertEquals(2, afterThree!!.shape.size)
        assertTrue(afterThree.stopIndices.isEmpty())
        assertEquals(route.distanceM - route.cumulative[9], afterThree.distanceM, 0.0)
        assertEquals(10.0, afterThree.durationS, 0.0)
    }

    @Test
    fun rejectsMissingOrAmbiguousAnchorsAndStopLinkage() {
        assertNull(RouteGeometrySlice.afterPassedStop(route, stops, 0))
        assertNull(RouteGeometrySlice.afterPassedStop(route, stops, 4))
        assertNull(RouteGeometrySlice.afterPassedStop(route, stops.take(2), 1))

        val noAnchorManeuver = route.copy(maneuvers = maneuvers.filter { it.beginIndex != 3 })
        assertNull(RouteGeometrySlice.afterPassedStop(noAnchorManeuver, stops, 1))

        val normalTurnAtAnchor = route.copy(
            maneuvers = maneuvers.map { if (it.beginIndex == 3) it.copy(isStop = false) else it },
        )
        assertNull(RouteGeometrySlice.afterPassedStop(normalTurnAtAnchor, stops, 1))

        val noStopManeuver = route.copy(
            maneuvers = maneuvers.map { if (it.beginIndex == 6) it.copy(isStop = false) else it },
        )
        assertNull(RouteGeometrySlice.afterPassedStop(noStopManeuver, stops, 1))

        val duplicateStopIndices = route.copy(stopIndices = listOf(3, 3), maneuvers = maneuvers)
        assertNull(RouteGeometrySlice.afterPassedStop(duplicateStopIndices, listOf(stops[0], stops[1]), 1))

        val farPassedPrefix = listOf(stops[0].copy(point = GeoPoint(42.0, 29.0)), stops[1], stops[2])
        assertNull(RouteGeometrySlice.afterPassedStop(route, farPassedPrefix, 1))

        val farRemainingStop = listOf(stops[0], stops[1].copy(point = GeoPoint(42.0, 29.0)), stops[2])
        assertNull(RouteGeometrySlice.afterPassedStop(route, farRemainingStop, 1))
    }

    @Test
    fun derivedSlicePreservesOriginalCreationClockAcrossRepeatedSlices() {
        val fullKey = RouteGeometryKey(route, Place("D", "", shape.last()), null, stops, RouteOptions())
        val slice1 = RouteGeometrySlice.afterPassedStop(route, stops, 1)!!
        val slice2 = RouteGeometrySlice.afterPassedStop(route, stops, 2)!!
        val key1 = RouteGeometryKey(slice1, fullKey.destination, null, stops.drop(1), RouteOptions(), derived = true)
        val key2 = RouteGeometryKey(slice2, fullKey.destination, null, stops.drop(2), RouteOptions(), derived = true)
        assertEquals(1_000L, RouteGeometryPersistence.createdAtMs(fullKey, 1_000L, key1, 5_000L))
        assertEquals(1_000L, RouteGeometryPersistence.createdAtMs(key1, 1_000L, key2, 9_000L))
    }
}

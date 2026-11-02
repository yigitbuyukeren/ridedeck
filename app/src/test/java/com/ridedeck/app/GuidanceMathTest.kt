package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.PromptSchedule
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceMathTest {
    @Test
    fun promptDistancesGrowWithSpeedWithinBounds() {
        assertEquals(100.0, PromptSchedule.nearM(2.0), 0.01)
        assertEquals(450.0, PromptSchedule.nearM(50.0), 0.01)
        assertTrue(PromptSchedule.nearM(30.0) / 30.0 > 10.0) // at least ~10 s on a motorway
        assertEquals(400.0, PromptSchedule.alertM(5.0), 0.01)
        assertEquals(2000.0, PromptSchedule.alertM(50.0), 0.01)
    }

    @Test
    fun farAlertSkippedWhenItWouldBumpIntoTheInstruction() {
        assertFalse(PromptSchedule.alertWorthIt(180.0, 14.0))
        assertTrue(PromptSchedule.alertWorthIt(500.0, 14.0))
    }

    // Two parallel roads 30 m apart running north (a flyover above an avenue), the route on the east one.
    private val east = (0..10).map { GeoPoint(41.0 + it * 0.0009, 29.00036) }

    @Test
    fun headingBreaksTieOnParallelRoadsGoingTheOtherWay() {
        // A reversed shape: the rider heads north but the nearest-by-distance segment runs south.
        val shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.003, 29.0), GeoPoint(41.003, 29.00005), GeoPoint(41.0, 29.00005))
        val p = GeoPoint(41.0015, 29.00003) // between the up leg and the down leg, nearer to the down leg
        val withHeading = RouteMatcher.match(shape, p, 0, 10, bearing = 0.0, speedMps = 10.0)
        val without = RouteMatcher.match(shape, p, 0, 10, bearing = null, speedMps = 10.0)
        assertEquals(0, withHeading.index)
        assertEquals(2, without.index)
    }

    @Test
    fun headingIgnoredWhenSlow() {
        val shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.003, 29.0), GeoPoint(41.003, 29.00005), GeoPoint(41.0, 29.00005))
        val p = GeoPoint(41.0015, 29.00003)
        assertEquals(2, RouteMatcher.match(shape, p, 0, 10, bearing = 0.0, speedMps = 1.0).index)
    }

    @Test
    fun matchAllowsASmallStepBack() {
        val m = RouteMatcher.match(east, GeoPoint(41.0 + 4.5 * 0.0009, 29.00036), from = 6, window = 10, bearing = null, speedMps = 0.0)
        assertEquals(4, m.index)
    }

    @Test
    fun offRouteLimitWidensWithSpeedAndPoorFix() {
        assertEquals(45.0, RouteMatcher.offRouteM(5.0, 5), 0.01)
        assertEquals(90.0, RouteMatcher.offRouteM(30.0, 5), 0.01)
        assertEquals(80.0, RouteMatcher.offRouteM(5.0, 40), 0.01)
        assertEquals(150.0, RouteMatcher.offRouteM(80.0, null), 0.01)
        assertEquals(3, RouteMatcher.offRouteFixes(25.0))
        assertEquals(4, RouteMatcher.offRouteFixes(10.0))
    }

    @Test
    fun remainingTimeUsesManeuverTimes() {
        val shape = (0..20).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        val ms = listOf(
            Maneuver(1, "", "", "", 1000.0, 0, timeS = 200.0),   // 1 km in 200 s (slow town)
            Maneuver(10, "", "", "", 1000.0, 10, timeS = 50.0),  // 1 km in 50 s (motorway)
            Maneuver(4, "", "", "", 0.0, 20),
        )
        val r = Route(shape, ms, 2000.0, 250.0)
        val half = r.cumulative[5]
        assertEquals(100.0 + 50.0, r.remainingTimeS(half), 3.0)
        assertEquals(25.0, r.remainingTimeS(r.cumulative[15]), 3.0)
        // Without manoeuvre times it falls back to the proportional estimate.
        val flat = Route(shape, ms.map { it.copy(timeS = 0.0) }, 2000.0, 250.0)
        assertEquals(125.0, flat.remainingTimeS(r.cumulative[10]), 3.0)
    }
}

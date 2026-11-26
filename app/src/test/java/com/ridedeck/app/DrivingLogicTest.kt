package com.ridedeck.app

import com.ridedeck.app.data.CallerNamePolicy
import com.ridedeck.app.data.DtmfController
import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavCue
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** A two-point route due north; the latitude step converts [northM] metres at the equator's degree length. */
private fun straightRoute(northM: Double): Route {
    val start = GeoPoint(41.0, 29.0)
    val end = GeoPoint(41.0 + northM / 111_194.9266, 29.0)
    val shape = listOf(start, end)
    val maneuvers = listOf(
        Maneuver(1, "Depart", "", "Head north", northM, 0),
        Maneuver(4, "Arrive", "", "You have arrived", 0.0, 1),
    )
    val distance = Route(shape, maneuvers, northM, northM / 14.0).cumulative.last()
    return Route(shape, maneuvers, distance, distance / 14.0)
}

class CallerNamePolicyTest {
    @Test
    fun resolvedFallsThroughEveryBlankCandidate() {
        assertEquals("Ayşe", CallerNamePolicy.resolved("Ayşe", "Carrier", "+90555"))
        assertEquals("Carrier", CallerNamePolicy.resolved(" \t", "Carrier", "+90555"))
        assertEquals("+90555", CallerNamePolicy.resolved("", " \n", "+90555"))
        assertNull(CallerNamePolicy.resolved(" ", "\t", ""))
        assertNull(CallerNamePolicy.resolved(null, null, null))
    }

    @Test
    fun contactLookupRequiresEveryPreconditionBelowApi30() {
        for (sdk in listOf(26, 29)) {
            assertTrue("API $sdk", CallerNamePolicy.needsContactLookup(sdk, null, "+90555", true))
            assertFalse("platform already named", CallerNamePolicy.needsContactLookup(sdk, "Ayşe", "+90555", true))
            assertFalse("blank number", CallerNamePolicy.needsContactLookup(sdk, null, " ", true))
            assertFalse("no permission", CallerNamePolicy.needsContactLookup(sdk, null, "+90555", false))
        }
    }

    @Test
    fun platformDetailsAlreadyCarryTheContactFromApi30() {
        for (sdk in listOf(30, 33, 36)) {
            assertFalse("API $sdk", CallerNamePolicy.needsContactLookup(sdk, null, "+90555", true))
        }
    }
}

class NavCueWaveformTest {
    @Test
    fun theCueIsTwoShortPulsesAndCallersGetACopy() {
        val waveform = NavCue.waveform()
        assertEquals(listOf(0L, 120L, 90L, 120L), waveform.toList())
        assertEquals("off/on pairs", 0, waveform.size % 2)
        waveform[1] = 999L
        assertEquals("the shared pattern must not leak", 120L, NavCue.waveform()[1])
    }
}

class RouteSimulatorTest {
    @Test
    fun fixesUseThePlannedSpeedAndOneSecondTimestamps() {
        val route = straightRoute(500.0)
        val plan = RouteSimulator.Plan(speedMps = 10.0, startAtMs = 7_000L)
        val fixes = RouteSimulator.fixes(route, plan)
        val expected = floor(route.cumulative.last() / plan.speedMps).toInt() + 1
        assertEquals(expected, fixes.size)
        assertEquals(7_000L, fixes.first().fixAtMs)
        assertEquals(8_000L, fixes[1].fixAtMs)
        assertEquals(36, fixes.first().speedKmh)
        assertEquals(10f, fixes.first().speedMps, 0f)
        assertEquals(5, fixes.first().accuracyM)
        assertTrue(fixes.all { it.lon == 29.0 })
        assertEquals(0f, fixes.first().bearing!!, 1e-4f)
    }

    @Test
    fun pointAtClampsToTheRouteAndInterpolatesTheMiddle() {
        val route = straightRoute(1_000.0)
        val start = route.shape.first()
        val end = route.shape.last()
        assertEquals(0.0, Geo.distance(start, RouteSimulator.pointAt(route, -100.0).first), 1e-6)
        assertEquals(0.0, Geo.distance(end, RouteSimulator.pointAt(route, route.cumulative.last() + 1_000.0).first), 1e-6)
        val (mid, bearing) = RouteSimulator.pointAt(route, route.cumulative.last() / 2)
        assertTrue(mid.lat > start.lat && mid.lat < end.lat)
        assertEquals(0.0, bearing, 1e-9)
    }

    @Test
    fun pointAtReportsTheBearingOfTheSegment() {
        val route = Route(
            shape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0, 29.01)),
            maneuvers = listOf(Maneuver(1, "", "", "", 0.0, 0)),
            distanceM = 1.0,
            durationS = 1.0,
        )
        val bearing = RouteSimulator.pointAt(route, 10.0).second
        assertEquals(Geo.bearing(route.shape.first(), route.shape.last()), bearing, 1e-9)
        assertEquals(90.0, bearing, 0.01)
    }

    @Test
    fun theTunnelDropsExactlyTheConfiguredFixes() {
        val route = straightRoute(1_000.0)
        val clean = RouteSimulator.fixes(route, RouteSimulator.Plan(speedMps = 10.0, startAtMs = 0L))
        val tunnel = RouteSimulator.fixes(
            route,
            RouteSimulator.Plan(speedMps = 10.0, gapAtM = 200.0, gapSeconds = 3, startAtMs = 0L),
        )
        assertEquals(clean.size - 3, tunnel.size)
        val times = tunnel.map { it.fixAtMs }.toSet()
        assertFalse(times.contains(20_000L))
        assertFalse(times.contains(21_000L))
        assertFalse(times.contains(22_000L))
        assertEquals(1, tunnel.zipWithNext().count { (a, b) -> b.fixAtMs - a.fixAtMs == 4_000L })
    }

    @Test
    fun theDetourShiftsOnlyTheConfiguredFixesEast() {
        val route = straightRoute(1_000.0)
        val clean = RouteSimulator.fixes(route, RouteSimulator.Plan(speedMps = 10.0))
        val detour = RouteSimulator.fixes(
            route,
            RouteSimulator.Plan(speedMps = 10.0, detourAtM = 200.0, detourOffsetM = 300.0, detourSeconds = 2),
        )
        assertEquals(clean.size, detour.size)
        assertEquals(clean[19].lon, detour[19].lon, 0.0)
        assertTrue(detour[20].lon > clean[20].lon)
        assertTrue(detour[21].lon > clean[21].lon)
        assertEquals(clean[22].lon, detour[22].lon, 0.0)
        assertEquals(clean[20].lat, detour[20].lat, 0.0)
        val expected = 300.0 / (111_320.0 * cos(Math.toRadians(clean[20].lat)))
        assertEquals(expected, detour[20].lon - clean[20].lon, 1e-12)
    }

    @Test
    fun noiseIsSeededAndStaysInsideTheRequestedRadius() {
        val route = straightRoute(500.0)
        val plan = RouteSimulator.Plan(speedMps = 10.0, noiseM = 10.0, seed = 42L)
        val first = RouteSimulator.fixes(route, plan)
        assertEquals("same seed, same ride", first, RouteSimulator.fixes(route, plan))
        assertNotEquals("another seed must move the fixes", first, RouteSimulator.fixes(route, plan.copy(seed = 7L)))
        first.forEachIndexed { i, fix ->
            val clean = RouteSimulator.pointAt(route, i * plan.speedMps).first
            assertTrue(Geo.distance(clean, GeoPoint(fix.lat, fix.lon)) <= 10.0 * sqrt(2.0) + 1e-6)
        }
        assertEquals(10, first.first().accuracyM)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DtmfControllerEdgeTest {
    @Test
    fun anUnsupportedKeyDoesNotCutTheRunningToneShort() = runTest {
        var stopped = 0
        val tones = DtmfController<String>(backgroundScope, { _, _ -> }, { stopped++ })
        assertTrue(tones.send("call", true, '1'))
        runCurrent()
        advanceTimeBy(100)
        assertFalse(tones.send("call", true, 'A'))
        advanceTimeBy(49)
        runCurrent()
        assertEquals(0, stopped)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, stopped)
    }

    @Test
    fun aRejectedPlayLeavesNothingToStopLater() = runTest {
        var stopped = 0
        val tones = DtmfController<String>(
            backgroundScope,
            { _, _ -> throw SecurityException("no call audio") },
            { stopped++ },
        )
        assertFalse(tones.send("call", true, '5'))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, stopped)
    }

    @Test
    fun aNewKeyStopsThePreviousCallBeforePlayingTheNext() = runTest {
        val events = mutableListOf<String>()
        val tones = DtmfController<String>(
            backgroundScope,
            { call, key -> events += "play:$call:$key" },
            { call -> events += "stop:$call" },
        )
        assertTrue(tones.send("old", true, '1'))
        runCurrent()
        assertTrue(tones.send("new", true, '2'))
        runCurrent()
        assertEquals(listOf("play:old:1", "stop:old", "play:new:2"), events)
        advanceTimeBy(150)
        runCurrent()
        assertEquals("stop:new", events.last())
    }

    @Test
    fun stopIsIdempotentAndACrashingToneStopIsContained() = runTest {
        var stopped = 0
        val tones = DtmfController<String>(
            backgroundScope,
            { _, _ -> },
            { stopped++; throw SecurityException("gone") },
        )
        assertTrue(tones.send("call", true, '9'))
        runCurrent()
        tones.stop()
        tones.stop()
        assertEquals(1, stopped)
    }
}

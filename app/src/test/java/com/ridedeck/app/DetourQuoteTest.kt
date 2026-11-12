package com.ridedeck.app

import com.ridedeck.app.data.DetourContext
import com.ridedeck.app.data.DetourMath
import com.ridedeck.app.data.DetourQuote
import com.ridedeck.app.data.DetourQuoteCache
import com.ridedeck.app.data.DetourRequestKey
import com.ridedeck.app.data.DetourStatus
import com.ridedeck.app.data.DetourUnavailableReason
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourQuoteTest {
    private val origin = GeoPoint(41.0, 29.0)
    private val final = Place("Final", "", GeoPoint(41.01, 29.01))
    private val station = Place("Fuel", "", GeoPoint(41.005, 29.005))

    @Test fun routedDeltaIsReportedOnlyWhenBothRoutesAreUsable() {
        val quote = DetourMath.quote(route(1000.0, 100.0), route(1300.0, 140.0))
        assertEquals(DetourStatus.AVAILABLE, quote.status)
        assertEquals(300.0, quote.extraDistanceM, 0.001)
        assertEquals(40.0, quote.extraTimeS, 0.001)
    }

    @Test fun tinyNegativeRoundingIsClampedButMaterialNegativeIsUnavailable() {
        val rounded = DetourMath.quote(route(1000.0, 100.0), route(999.5, 99.5))
        assertTrue(rounded.available)
        assertEquals(0.0, rounded.extraDistanceM, 0.001)
        assertEquals(0.0, rounded.extraTimeS, 0.001)

        val material = DetourMath.quote(route(1000.0, 100.0), route(900.0, 100.0))
        assertEquals(DetourStatus.UNAVAILABLE, material.status)
        assertEquals(DetourUnavailableReason.NEGATIVE_DELTA, material.reason)
    }

    @Test fun unusableOrMismatchedRoutesAreNeverPresentedAsQuotes() {
        val invalid = DetourMath.quote(route(1000.0, 100.0).copy(distanceM = 0.0), route(1200.0, 120.0))
        assertEquals(DetourUnavailableReason.INVALID_ROUTE, invalid.reason)

        val negativeDuration = DetourMath.quote(route(1000.0, 100.0).copy(durationS = -1.0), route(1200.0, 120.0))
        assertEquals(DetourUnavailableReason.INVALID_ROUTE, negativeDuration.reason)

        val mismatched = DetourMath.quote(route(1000.0, 100.0), route(1200.0, 120.0, end = 29.02))
        assertEquals(DetourUnavailableReason.CONTEXT_MISMATCH, mismatched.reason)
    }

    @Test fun roadSnappedEndpointsWithinBoundedToleranceAreAcceptedButWrongRoadIsNot() {
        val near = DetourMath.quote(
            route(1000.0, 100.0),
            route(1200.0, 120.0, start = GeoPoint(41.0001, 29.0)),
        )
        assertTrue("a small road-snap offset must not look like a different route", near.available)

        val wrongRoad = DetourMath.quote(
            route(1000.0, 100.0),
            route(1200.0, 120.0, start = GeoPoint(41.001, 29.0)),
        )
        assertEquals(DetourUnavailableReason.CONTEXT_MISMATCH, wrongRoad.reason)
    }

    @Test fun cacheUsesTheSuppliedMonotonicClockAndCachesOnlySuccessfulQuotes() {
        val key = DetourRequestKey(context(), station)
        val cache = DetourQuoteCache(maxEntries = 4, ttlMs = 1_000)
        val quote = DetourMath.quote(route(1000.0, 100.0), route(1200.0, 120.0))
        cache.put(key, quote, nowMs = 1_000)
        // A wall-clock rollback would be 999; the monotonic injected clock stays at 1_001.
        assertEquals(quote, cache.get(key, nowMs = 1_001))

        val unavailable = DetourQuote.unavailable(DetourUnavailableReason.VIA_FAILED)
        cache.put(DetourRequestKey(context().copy(contextGeneration = 9), station), unavailable, nowMs = 1_000)
        assertNull(cache.get(DetourRequestKey(context().copy(contextGeneration = 9), station), nowMs = 1_001))
    }

    @Test fun cacheIsBoundedAndExpiresAtTheExplicitTtl() {
        val key = DetourRequestKey(context(), station)
        val key2 = DetourRequestKey(context().copy(contextGeneration = 2), station)
        val key3 = DetourRequestKey(context().copy(contextGeneration = 3), station)
        val cache = DetourQuoteCache(maxEntries = 2, ttlMs = 1_000)
        val quote = DetourMath.quote(route(1000.0, 100.0), route(1200.0, 120.0))

        cache.put(key, quote, nowMs = 0)
        assertEquals(quote, cache.get(key, nowMs = 999))
        assertNull(cache.get(key, nowMs = 1_000))

        cache.put(key, quote, nowMs = 0)
        cache.put(key2, quote, nowMs = 0)
        cache.put(key3, quote, nowMs = 0)
        assertEquals(2, cache.size())
        assertNull(cache.get(key, nowMs = 0))
    }

    @Test fun cacheRejectsInvalidCapacityAndTtl() {
        assertThrows(IllegalArgumentException::class.java) { DetourQuoteCache(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { DetourQuoteCache(maxEntries = DetourQuoteCache.MAX_CACHE_ENTRIES + 1) }
        assertThrows(IllegalArgumentException::class.java) { DetourQuoteCache(ttlMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { DetourQuoteCache(ttlMs = DetourQuoteCache.MAX_TTL_MS + 1) }
    }

    @Test fun headingBucketsIgnoreJitterButNotMeaningfulTurns() {
        assertEquals(90.0, com.ridedeck.app.data.DetourContext.quantizeHeading(89.0)!!, 0.001)
        assertEquals(90.0, com.ridedeck.app.data.DetourContext.quantizeHeading(91.0)!!, 0.001)
        assertTrue(com.ridedeck.app.data.DetourContext.quantizeHeading(10.0) != com.ridedeck.app.data.DetourContext.quantizeHeading(50.0))
    }

    @Test fun routedViaMustLinkSelectedStationNotJustShareEndpoints() {
        val baseline = routeWithStops(listOf(origin, final.point), emptyList(), 1_000.0)
        val goodVia = routeWithStops(listOf(origin, station.point, final.point), listOf(1), 1_200.0)
        val wrongVia = routeWithStops(
            listOf(origin, GeoPoint(41.02, 29.02), final.point), listOf(1), 1_200.0,
        )

        assertTrue(DetourMath.quote(baseline, goodVia, station, emptyList()).available)
        assertEquals(
            DetourUnavailableReason.CONTEXT_MISMATCH,
            DetourMath.quote(baseline, wrongVia, station, emptyList()).reason,
        )
    }

    @Test fun routedViaMustLinkRemainingStopsInOrder() {
        val stop = Place("Stop", "", GeoPoint(41.005, 29.005))
        val baseline = routeWithStops(listOf(origin, stop.point, final.point), listOf(1), 1_000.0)
        val via = routeWithStops(listOf(origin, station.point, stop.point, final.point), listOf(1, 2), 1_200.0)
        val wrongOrder = routeWithStops(listOf(origin, station.point, final.point), listOf(1), 1_200.0)

        assertTrue(DetourMath.quote(baseline, via, station, listOf(stop)).available)
        assertEquals(
            DetourUnavailableReason.CONTEXT_MISMATCH,
            DetourMath.quote(baseline, wrongOrder, station, listOf(stop)).reason,
        )
    }

    private fun routeWithStops(
        points: List<GeoPoint>,
        stopIndices: List<Int>,
        distance: Double,
    ): Route = Route(
        shape = points,
        maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = 10.0)) +
            stopIndices.map { Maneuver(0, "", "", "", 0.0, it, timeS = 10.0, isStop = true) },
        distanceM = distance,
        durationS = 10.0,
        stopIndices = stopIndices,
    )

    private fun route(
        distanceM: Double,
        durationS: Double,
        start: GeoPoint = origin,
        end: Double = 29.01,
    ): Route = Route(
        shape = listOf(start, GeoPoint(41.01, end)),
        maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = durationS)),
        distanceM = distanceM,
        durationS = durationS,
    )

    private fun context() = DetourContext(
        origin = origin,
        fixAtMs = 10_000L,
        final = final,
        remainingStops = emptyList(),
        options = RouteOptions(),
        headingDeg = 90.0,
        language = "tr",
        miles = false,
        maxStops = 3,
        contextGeneration = 1L,
        freshFix = true,
    )
}

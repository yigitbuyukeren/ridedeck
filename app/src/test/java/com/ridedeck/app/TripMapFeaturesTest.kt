package com.ridedeck.app

import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripTrace
import com.ridedeck.app.ui.tripLineFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMapFeaturesTest {
    private fun point(t: Long, lat: Double, lon: Double, kmh: Int = 30) =
        TripPoint(t = 1_700_000_000_000L + t, lat = lat, lon = lon, kmh = kmh)

    @Test
    fun separateTraceSegmentsBecomeSeparateLineFeatures() {
        val points = listOf(
            point(0, 41.0000, 29.0000),
            point(1_000, 41.0001, 29.0001),
            // 40 s silence is a GPS gap, never a straight line through it.
            point(41_000, 41.0100, 29.0100),
            point(42_000, 41.0101, 29.0101),
        )
        val trace = TripTrace.from(points)
        assertEquals(2, trace.segments.size)
        assertEquals(2, tripLineFeatures(trace).size)
    }

    @Test
    fun singlePointHasNoLineFeature() {
        val trace = TripTrace.from(listOf(point(0, 41.0, 29.0)))
        assertEquals(1, trace.pointCount)
        assertTrue(tripLineFeatures(trace).isEmpty())
    }

    @Test
    fun oneHourOfPointsStaysWithinTheDrawingBudget() {
        val points = (0..3_600).map {
            point(it * 1_000L, 41.0 + it * 0.00001, 29.0 + it * 0.00001)
        }
        val trace = TripTrace.from(points, maxPoints = 2_000)
        assertTrue(trace.pointCount > 2_000)
        assertTrue(trace.wasSimplified)
        assertTrue(trace.sampledPointCount <= 2_000)
        assertTrue(tripLineFeatures(trace).isNotEmpty())
    }
}

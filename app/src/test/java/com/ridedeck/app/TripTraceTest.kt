package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.TripGapReason
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTraceTest {
    private fun p(sec: Long, lon: Double = 0.0, lat: Double = 0.0) =
        TripPoint(1_000L + sec * 1_000L, lat, lon, 30)

    @Test
    fun emptyTripHasNoTrace() {
        val trace = TripTrace.from(emptyList())
        assertTrue(trace.segments.isEmpty())
        assertNull(trace.start)
        assertNull(trace.end)
        assertEquals(0, trace.pointCount)
        assertEquals(0, trace.sampledPointCount)
        assertTrue(trace.gaps.isEmpty())
    }

    @Test
    fun singlePointIsOneSegmentAndBothEnds() {
        val only = p(0)
        val trace = TripTrace.from(listOf(only))
        assertEquals(listOf(listOf(GeoPoint(only.lat, only.lon))), trace.segments)
        assertEquals(GeoPoint(only.lat, only.lon), trace.start)
        assertEquals(trace.start, trace.end)
        assertEquals(1, trace.pointCount)
        assertEquals(1, trace.sampledPointCount)
        assertTrue(trace.gaps.isEmpty())
    }

    @Test
    fun manyContinuousPointsStayInOneSegment() {
        val points = listOf(p(0), p(1, 0.00001), p(2, 0.00002))
        val trace = TripTrace.from(points)
        assertEquals(1, trace.segments.size)
        assertEquals(3, trace.segments[0].size)
        assertEquals(3, trace.pointCount)
        assertEquals(3, trace.sampledPointCount)
        assertEquals(GeoPoint(points.first().lat, points.first().lon), trace.start)
        assertEquals(GeoPoint(points.last().lat, points.last().lon), trace.end)
        assertTrue(trace.gaps.isEmpty())
        assertEquals(0, trace.omittedSegmentCount)
        assertFalse(trace.wasSimplified)
    }

    @Test
    fun outOfOrderTimestampBreaksTheSegment() {
        val trace = TripTrace.from(
            listOf(p(0), p(1, 0.00001), TripPoint(1_500L, 0.0, 0.00002, 30), p(2, 0.00003)),
        )
        assertEquals(listOf(2, 2), trace.segments.map { it.size })
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.TIME_NOT_INCREASING, trace.gaps[0].reason)
        assertEquals(1, trace.gaps[0].afterPointIndex)
        assertEquals(0L, trace.gaps[0].durationMs)
    }

    @Test
    fun longGapBreaksTheSegmentInsteadOfDrawingAcrossIt() {
        val trace = TripTrace.from(listOf(p(0), p(31, 0.0001)))
        assertEquals(listOf(1, 1), trace.segments.map { it.size })
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.LONG_GAP, trace.gaps[0].reason)
        assertEquals(0, trace.gaps[0].afterPointIndex)
        assertEquals(31_000L, trace.gaps[0].durationMs)
        assertTrue(trace.gaps[0].distanceM > 0.0)
    }

    @Test
    fun implausibleJumpBreaksTheSegment() {
        val trace = TripTrace.from(listOf(p(0), p(1, 0.01)))
        assertEquals(listOf(1, 1), trace.segments.map { it.size })
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.IMPLAUSIBLE_JUMP, trace.gaps[0].reason)
        assertEquals(0, trace.gaps[0].afterPointIndex)
        assertTrue(trace.gaps[0].distanceM > 70.0)
    }

    @Test
    fun invalidCoordinateIsDroppedAndBreaksTheSegment() {
        val trace = TripTrace.from(
            listOf(p(0), TripPoint(2_000L, Double.NaN, 0.0, 0), p(3, 0.00001)),
        )
        assertEquals(listOf(1, 1), trace.segments.map { it.size })
        assertEquals(2, trace.pointCount)
        assertEquals(2, trace.sampledPointCount)
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.INVALID_COORDINATE, trace.gaps[0].reason)
        assertEquals(0, trace.gaps[0].afterPointIndex)
        assertEquals(1_000L, trace.gaps[0].durationMs)
    }

    @Test
    fun onlyInvalidPointsProduceAnEmptyTrace() {
        val trace = TripTrace.from(listOf(TripPoint(1_000L, Double.NaN, 0.0, 0)))
        assertTrue(trace.segments.isEmpty())
        assertNull(trace.start)
        assertNull(trace.end)
        assertEquals(0, trace.pointCount)
        assertTrue(trace.gaps.isEmpty())
    }

    @Test
    fun invalidTimestampIsDropped() {
        val trace = TripTrace.from(listOf(TripPoint(0L, 0.0, 0.0, 0), p(0)))
        assertEquals(1, trace.segments.size)
        assertEquals(1, trace.pointCount)
        assertEquals(GeoPoint(0.0, 0.0), trace.start)
    }

    @Test
    fun hourLongRecordIsBoundedAndKeepsEndpoints() {
        // 1 Hz for one hour, ~11 m/s eastbound at the equator: plausible and continuous.
        val points = (0..3_600).map { i -> TripPoint(1_000L + i * 1_000L, 0.0, i * 0.0001, 40) }
        val trace = TripTrace.from(points, maxPoints = 1_000)
        assertEquals(1, trace.segments.size)
        assertEquals(3_601, trace.pointCount)
        assertTrue("sampled=${trace.sampledPointCount}", trace.sampledPointCount <= 1_000)
        assertTrue(trace.sampledPointCount >= 2)
        assertEquals(GeoPoint(points.first().lat, points.first().lon), trace.segments[0].first())
        assertEquals(GeoPoint(points.last().lat, points.last().lon), trace.segments[0].last())
        assertEquals(trace.start, trace.segments[0].first())
        assertEquals(trace.end, trace.segments[0].last())
        assertTrue(trace.gaps.isEmpty())
        assertEquals(0, trace.omittedSegmentCount)
        assertTrue(trace.wasSimplified)
    }

    @Test
    fun samplingKeepsEverySegmentSeparateAndItsEndpoints() {
        val first = (0..99).map { i -> p(i.toLong(), i * 0.00001) }
        val second = (0..99).map { i -> p(200L + i.toLong(), 0.01 + i * 0.00001) }
        val trace = TripTrace.from(first + second, maxPoints = 20)
        assertEquals(2, trace.segments.size)
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.LONG_GAP, trace.gaps[0].reason)
        assertTrue(trace.segments.all { it.size <= 10 })
        assertEquals(GeoPoint(first.first().lat, first.first().lon), trace.segments[0].first())
        assertEquals(GeoPoint(first.last().lat, first.last().lon), trace.segments[0].last())
        assertEquals(GeoPoint(second.first().lat, second.first().lon), trace.segments[1].first())
        assertEquals(GeoPoint(second.last().lat, second.last().lon), trace.segments[1].last())
        assertTrue(trace.sampledPointCount <= 20)
        assertEquals(0, trace.omittedSegmentCount)
        assertTrue(trace.wasSimplified)
    }

    @Test
    fun thousandsOfDiscontinuitiesStillRespectTheStrictBudget() {
        val points = buildList {
            repeat(2_000) { s ->
                val base = 1_000L + s * 60_000L
                val lon = s * 0.001
                add(TripPoint(base, 0.0, lon, 30))
                add(TripPoint(base + 1_000L, 0.0, lon + 0.00001, 30))
                add(TripPoint(base + 2_000L, 0.0, lon + 0.00002, 30))
            }
        }
        val trace = TripTrace.from(points, maxPoints = 100)
        assertEquals(1_999, trace.gaps.size)
        assertEquals(100, trace.sampledPointCount)
        assertEquals(50, trace.segments.size)
        assertEquals(1_950, trace.omittedSegmentCount)
        assertTrue(trace.wasSimplified)
        assertEquals(GeoPoint(points.first().lat, points.first().lon), trace.start)
        assertEquals(GeoPoint(points.last().lat, points.last().lon), trace.end)
        assertEquals(trace.start, trace.segments.first().first())
        assertEquals(trace.end, trace.segments.last().last())
        assertTrue(trace.segments.all { it.size <= 2 })
    }

    @Test
    fun budgetTwoReturnsSeparateGlobalEndpointsInsteadOfAFakeLine() {
        val points = buildList {
            repeat(1_000) { s ->
                val base = 1_000L + s * 60_000L
                add(TripPoint(base, 0.0, s * 0.001, 30))
                add(TripPoint(base + 2_000L, 0.0, s * 0.001 + 0.00002, 30))
            }
        }
        val trace = TripTrace.from(points, maxPoints = 2)
        assertEquals(2, trace.segments.size)
        assertTrue(trace.segments.all { it.size == 1 })
        assertEquals(2, trace.sampledPointCount)
        assertEquals(998, trace.omittedSegmentCount)
        assertEquals(trace.start, trace.segments[0].single())
        assertEquals(trace.end, trace.segments[1].single())
        assertTrue(trace.wasSimplified)
    }

    @Test
    fun budgetThreeAlsoKeepsTheEndpointsSeparate() {
        val trace = TripTrace.from(
            listOf(p(0), p(1, 0.00001), p(100, 0.01), p(101, 0.01001), p(200, 0.02)),
            maxPoints = 3,
        )
        assertTrue(trace.sampledPointCount <= 3)
        assertEquals(2, trace.segments.size)
        assertTrue(trace.segments.all { it.size == 1 })
        assertEquals(trace.start, trace.segments[0].single())
        assertEquals(trace.end, trace.segments[1].single())
        assertEquals(1, trace.omittedSegmentCount)
        assertTrue(trace.wasSimplified)
    }

    @Test
    fun duplicateSavedFixAddsNoGapAndKeepsTheLineContinuous() {
        val points = listOf(p(0), p(1, 0.00001), p(1, 0.00001), p(2, 0.00002))
        val trace = TripTrace.from(points)
        assertEquals(listOf(3), trace.segments.map { it.size })
        assertEquals(3, trace.pointCount)
        assertEquals(3, trace.sampledPointCount)
        assertTrue(trace.gaps.isEmpty())
        assertEquals(GeoPoint(points.first().lat, points.first().lon), trace.start)
        assertEquals(GeoPoint(points.last().lat, points.last().lon), trace.end)
    }

    @Test
    fun equalTimestampAtAnotherPositionStillBreaksTheSegment() {
        val trace = TripTrace.from(
            listOf(p(0), p(1, 0.00001), TripPoint(2_000L, 0.0, 0.00002, 30), p(2, 0.00003)),
        )
        assertEquals(listOf(2, 2), trace.segments.map { it.size })
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.TIME_NOT_INCREASING, trace.gaps[0].reason)
        assertEquals(1, trace.gaps[0].afterPointIndex)
        assertEquals(0L, trace.gaps[0].durationMs)
    }

    @Test
    fun invalidTimestampAtLongMinValueDoesNotOverflowGapDuration() {
        val trace = TripTrace.from(
            listOf(p(0), TripPoint(Long.MIN_VALUE, 0.0, 0.00002, 30), p(3, 0.00003)),
        )
        assertEquals(listOf(1, 1), trace.segments.map { it.size })
        assertEquals(1, trace.gaps.size)
        assertEquals(TripGapReason.INVALID_TIMESTAMP, trace.gaps[0].reason)
        assertEquals(0L, trace.gaps[0].durationMs)
    }
}

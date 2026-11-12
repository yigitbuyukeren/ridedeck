package com.ridedeck.app

import com.ridedeck.app.data.Trip
import com.ridedeck.app.data.TripStats
import com.ridedeck.app.data.TripSummaryMath
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSummaryMathTest {
    private fun trip(
        startedAt: String,
        durationMs: Long = 3_600_000,
        movingMs: Long = 1_800_000,
        distanceM: Double = 10_000.0,
    ): Trip {
        val start = Instant.parse(startedAt).toEpochMilli()
        return Trip(
            id = start,
            startedAt = start,
            endedAt = start + durationMs,
            stats = TripStats(distanceM = distanceM, movingMs = movingMs, maxKmh = 80),
        )
    }

    @Test
    fun usLocaleWeekStartsSundayAndUsesCalendarMonth() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val trips = listOf(
            trip("2026-11-08T10:00:00Z"), // Sunday: this week
            trip("2026-11-09T10:00:00Z"), // Monday: this week
            trip("2026-11-01T10:00:00Z"), // this month, previous week
            trip("2026-10-31T10:00:00Z"), // previous month
        )
        val s = TripSummaryMath.of(
            trips,
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )

        assertEquals("2026-11-08", s.week.periodStart.toString())
        assertEquals("2026-11-14", s.week.periodEndInclusive.toString())
        assertEquals(2, s.week.tripCount)
        assertEquals(20_000.0, s.week.distanceM, 0.01)
        assertEquals(7_200_000, s.week.totalMs)
        assertEquals(3_600_000, s.week.movingMs)
        assertEquals(20, s.week.averageMovingKmh)

        assertEquals("2026-11-01", s.month.periodStart.toString())
        assertEquals("2026-11-30", s.month.periodEndInclusive.toString())
        assertEquals(3, s.month.tripCount)
        assertEquals(30_000.0, s.month.distanceM, 0.01)
    }

    @Test
    fun ukLocaleWeekStartsMondayAndExcludesSunday() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val trips = listOf(
            trip("2026-11-08T10:00:00Z"),
            trip("2026-11-09T10:00:00Z"),
        )
        val s = TripSummaryMath.of(
            trips,
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-GB"),
        )

        assertEquals("2026-11-09", s.week.periodStart.toString())
        assertEquals("2026-11-15", s.week.periodEndInclusive.toString())
        assertEquals(1, s.week.tripCount)
    }

    @Test
    fun newYorkDstChangeKeepsBothSidesOfTheLocalDayInTheSameWeek() {
        val now = Instant.parse("2024-03-12T12:00:00Z")
        val trips = listOf(
            // 01:30 EST and 03:30 EDT on the same local calendar day, across the DST jump.
            trip("2024-03-10T06:30:00Z"),
            trip("2024-03-10T07:30:00Z"),
            trip("2024-03-09T23:30:00Z"), // previous local day
        )
        val s = TripSummaryMath.of(
            trips,
            now,
            ZoneId.of("America/New_York"),
            Locale.forLanguageTag("en-US"),
        )

        assertEquals("2024-03-10", s.week.periodStart.toString())
        assertEquals(2, s.week.tripCount)
    }

    @Test
    fun monthBoundaryIncludesTheLastLocalDay() {
        val now = Instant.parse("2026-11-30T23:59:00Z")
        val trips = listOf(
            trip("2026-11-30T23:30:00Z"),
            trip("2026-12-01T00:00:00Z"),
        )
        val s = TripSummaryMath.of(
            trips,
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )

        assertEquals("2026-11-30", s.month.periodEndInclusive.toString())
        assertEquals(1, s.month.tripCount)
    }

    @Test
    fun emptyPeriodIsExplicitlyZeroAndDoesNotInventStoppedTime() {
        val s = TripSummaryMath.of(
            emptyList(),
            Instant.parse("2026-11-12T12:00:00Z"),
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(0, s.week.tripCount)
        assertEquals(0, s.week.averageMovingKmh)
        assertEquals(0L, s.week.totalMs)
        assertEquals(0L, s.week.movingMs)
        assertEquals(0, s.month.tripCount)
    }


    @Test
    fun nextLocalMidnightIsDstSafe() {
        val now = Instant.parse("2024-03-10T06:00:00Z") // 01:00 EST
        assertEquals(
            "2024-03-11T04:00:00Z", // 00:00 EDT after spring forward
            TripSummaryMath.nextLocalMidnight(now, ZoneId.of("America/New_York")).toString(),
        )
    }

    @Test
    fun futureTripsDoNotCountUntilTheyStart() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val s = TripSummaryMath.of(
            listOf(
                trip("2026-11-12T10:00:00Z"),
                trip("2026-11-12T13:00:00Z"), // future, same day/week
            ),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(1, s.week.tripCount)
        assertEquals(1, s.month.tripCount)
    }

    @Test
    fun corruptAndNegativeStatsAreSkippedWithoutChangingValidRecords() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val valid = trip("2026-11-12T10:00:00Z", distanceM = 10_000.0, durationMs = 3_600_000, movingMs = 1_800_000)
        val corrupt = listOf(
            trip("2026-11-12T10:01:00Z", distanceM = Double.NaN),
            trip("2026-11-12T10:02:00Z", distanceM = Double.POSITIVE_INFINITY),
            trip("2026-11-12T10:03:00Z", distanceM = -1.0),
            trip("2026-11-12T10:04:00Z", movingMs = -1L),
        )
        val s = TripSummaryMath.of(
            listOf(valid) + corrupt,
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(1, s.week.tripCount)
        assertEquals(10_000.0, s.week.distanceM, 0.01)
        assertEquals(3_600_000, s.week.totalMs)
        assertEquals(1_800_000, s.week.movingMs)
        assertEquals(20, s.week.averageMovingKmh)
        assertEquals(4, s.week.omittedCount)
    }

    @Test
    fun movingGreaterThanWallDurationIsRetainedAsAuthoritative() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val s = TripSummaryMath.of(
            listOf(trip("2026-11-12T10:00:00Z", durationMs = 1_000, movingMs = 2_000, distanceM = 1_000.0)),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(1, s.week.tripCount)
        assertEquals(1_000, s.week.totalMs)
        assertEquals(2_000, s.week.movingMs)
        assertEquals(0, s.week.omittedCount)
        // Average remains moving-time based even when wall-clock duration is shorter.
        assertEquals(1_800, s.week.averageMovingKmh)
    }

    @Test
    fun extremeSumsSaturateInsteadOfWrapping() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val distanceSaturated = TripSummaryMath.of(
            listOf(
                trip("2026-11-12T10:00:00Z", distanceM = Double.MAX_VALUE),
                trip("2026-11-12T11:00:00Z", distanceM = Double.MAX_VALUE),
            ),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertTrue(distanceSaturated.week.distanceM.isFinite())
        assertEquals(Double.MAX_VALUE, distanceSaturated.week.distanceM, 0.0)
        assertTrue(distanceSaturated.week.capped)

        val huge = Long.MAX_VALUE / 2 + 1
        val timeSaturated = TripSummaryMath.of(
            listOf(
                trip("2026-11-12T09:00:00Z", durationMs = huge, movingMs = huge, distanceM = 1_000.0),
                trip("2026-11-12T10:00:00Z", durationMs = huge, movingMs = huge, distanceM = 1_000.0),
            ),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(Long.MAX_VALUE, timeSaturated.week.totalMs)
        assertEquals(Long.MAX_VALUE, timeSaturated.week.movingMs)
        assertTrue(timeSaturated.week.capped)
    }

    @Test
    fun weekCanCrossNewYearWhileMonthStaysCalendarLocal() {
        val now = Instant.parse("2027-01-01T12:00:00Z")
        val s = TripSummaryMath.of(
            listOf(
                trip("2026-12-31T10:00:00Z"),
                trip("2027-01-01T10:00:00Z"),
            ),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals("2026-12-27", s.week.periodStart.toString())
        assertEquals("2027-01-02", s.week.periodEndInclusive.toString())
        assertEquals(2, s.week.tripCount)
        assertEquals(1, s.month.tripCount)
    }

    @Test
    fun overnightTripCountsWholeInItsStartPeriod() {
        val now = Instant.parse("2026-11-13T12:00:00Z")
        val s = TripSummaryMath.of(
            listOf(trip("2026-11-12T23:30:00Z", durationMs = 7_200_000, movingMs = 3_600_000)),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        assertEquals(1, s.week.tripCount)
        assertEquals(1, s.month.tripCount)
        assertEquals(7_200_000, s.week.totalMs)
        assertEquals(3_600_000, s.week.movingMs)
    }


    @Test
    fun summaryUsesAuthoritativeDurationClampWithoutItsOwnSubtraction() {
        val start = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
        val inverted = Trip(
            id = 99,
            startedAt = start,
            endedAt = start - 1_000,
            stats = TripStats(distanceM = 1_000.0, movingMs = 0, maxKmh = 30),
        )
        val s = TripSummaryMath.of(
            listOf(inverted),
            Instant.parse("2026-11-12T12:00:00Z"),
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )
        // Trip.durationMs owns clamping; the summary must not subtract endedAt-startedAt itself.
        assertEquals(0L, s.week.totalMs)
        assertEquals(0L, s.week.movingMs)
        assertEquals(1, s.week.tripCount)
    }

    @Test
    fun averageUsesMovingTimeNotTotalDuration() {
        val now = Instant.parse("2026-11-12T12:00:00Z")
        val s = TripSummaryMath.of(
            listOf(trip("2026-11-12T10:00:00Z", durationMs = 3_600_000, movingMs = 1_800_000, distanceM = 10_000.0)),
            now,
            ZoneId.of("UTC"),
            Locale.forLanguageTag("en-US"),
        )

        // 10 km / 30 moving minutes = 20 km/h, not 10 km/h from total duration.
        assertEquals(20, s.week.averageMovingKmh)
        assertEquals(3_600_000, s.week.totalMs)
        assertEquals(1_800_000, s.week.movingMs)
    }
}

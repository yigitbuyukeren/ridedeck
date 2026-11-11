package com.ridedeck.app

import com.ridedeck.app.data.NextSunset
import com.ridedeck.app.data.SunsetMath
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunsetMathTest {
    private fun local(date: String, lat: Double, lon: Double, zone: String): ZonedDateTime? =
        SunsetMath.sunsetForDate(LocalDate.parse(date), lat, lon, ZoneId.of(zone))
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.of(zone)) }

    private fun assertNear(expected: String, actual: ZonedDateTime?) {
        assertNotNull(actual)
        val expectedTime = ZonedDateTime.parse(expected)
        val minutes = Math.abs(java.time.Duration.between(expectedTime, actual).toMinutes())
        assertTrue("expected $expectedTime, got $actual", minutes <= 5)
    }

    @Test
    fun londonSummerSolstice() {
        assertNear("2026-06-21T21:21+01:00[Europe/London]", local("2026-06-21", 51.5, -0.12, "Europe/London"))
    }

    @Test
    fun londonWinterSolstice() {
        assertNear("2026-12-21T15:53Z[Europe/London]", local("2026-12-21", 51.5, -0.12, "Europe/London"))
    }

    @Test
    fun istanbulIsOnLocalClock() {
        assertNear("2026-06-21T20:39+03:00[Europe/Istanbul]", local("2026-06-21", 41.01, 28.98, "Europe/Istanbul"))
    }

    @Test
    fun equatorialSunsetIsAroundSix() {
        val at = local("2026-03-20", 0.0, 0.0, "UTC")
        assertNotNull(at)
        assertEquals(18, at!!.hour)
        assertTrue(at.minute in 5..20)
    }

    @Test
    fun polarDayAndNightHaveNoSunset() {
        assertNull(SunsetMath.sunsetForDate(LocalDate.parse("2026-06-21"), 69.65, 18.96, ZoneId.of("Europe/Oslo")))
        assertNull(SunsetMath.sunsetForDate(LocalDate.parse("2026-12-21"), 69.65, 18.96, ZoneId.of("Europe/Oslo")))
    }

    @Test
    fun badCoordinatesAreRejected() {
        assertNull(SunsetMath.sunsetForDate(LocalDate.parse("2026-06-21"), Double.NaN, 0.0, ZoneId.of("UTC")))
        assertNull(SunsetMath.sunsetForDate(LocalDate.parse("2026-06-21"), 91.0, 0.0, ZoneId.of("UTC")))
        assertNull(SunsetMath.sunsetForDate(LocalDate.parse("2026-06-21"), 0.0, 181.0, ZoneId.of("UTC")))
    }

    @Test
    fun nextSunsetPrefersTodayWhileItIsAhead() {
        assertEquals(NextSunset(1_000L, tomorrow = false), SunsetMath.nextSunset(500L, 1_000L, 2_000L))
    }

    @Test
    fun nextSunsetRollsOverAfterSunset() {
        assertEquals(NextSunset(2_000L, tomorrow = true), SunsetMath.nextSunset(1_500L, 1_000L, 2_000L))
    }

    @Test
    fun nextSunsetUsesTomorrowWhenTodayHasNone() {
        assertEquals(NextSunset(2_000L, tomorrow = true), SunsetMath.nextSunset(500L, null, 2_000L))
    }

    @Test
    fun nextSunsetSaysSoWhenTomorrowHasNoneEither() {
        assertEquals(NextSunset(null, tomorrow = true), SunsetMath.nextSunset(1_500L, 1_000L, null))
        assertEquals(NextSunset(null, tomorrow = false), SunsetMath.nextSunset(500L, null, null))
    }

    @Test
    fun sunsetMsUsesTheDayOfTheGivenInstant() {
        val zone = ZoneId.of("Europe/Istanbul")
        val now = ZonedDateTime.parse("2026-06-21T23:30+03:00[Europe/Istanbul]").toInstant().toEpochMilli()
        val sunset = SunsetMath.sunsetMs(41.01, 28.98, now, zone)
        assertNotNull(sunset)
        assertEquals(LocalDate.parse("2026-06-21"), java.time.Instant.ofEpochMilli(sunset!!).atZone(zone).toLocalDate())
    }
}

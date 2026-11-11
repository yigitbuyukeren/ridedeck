package com.ridedeck.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The sunset a card should show, and whether it belongs to tomorrow. */
data class NextSunset(val atMs: Long?, val tomorrow: Boolean)

/**
 * Sunset times without a network call: the NOAA solar-position approximation is good to a few
 * minutes, which is enough for a dashboard card that says how long the light will last.
 */
object SunsetMath {
    /** The sun is this far below the horizon when its upper edge sets. */
    private const val ZENITH_DEG = 90.833

    /**
     * Picks the next sunset for [nowMs] from today's and tomorrow's times. Today's wins while it
     * is still ahead; after it sets, or when today has none, tomorrow is used. A null [atMs]
     * means there is no sunset on that day (polar day/night), and [tomorrow] selects the wording.
     */
    fun nextSunset(nowMs: Long, todayMs: Long?, tomorrowMs: Long?): NextSunset = when {
        todayMs != null && nowMs < todayMs -> NextSunset(todayMs, tomorrow = false)
        tomorrowMs != null -> NextSunset(tomorrowMs, tomorrow = true)
        else -> NextSunset(null, tomorrow = todayMs != null)
    }

    /**
     * Sunset for the local day that contains [nowMs], or null during polar day/night (the sun does
     * not cross the horizon that day) and for impossible coordinates.
     */
    fun sunsetMs(lat: Double, lon: Double, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        sunsetForDate(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), lat, lon, zone)

    /** Sunset on [date] at [lat]/[lon], as epoch milliseconds; null when the sun does not set that day. */
    fun sunsetForDate(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Long? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        // Fractional year at the middle of the local day; leap years differ by a day at most, which
        // the approximation tolerates.
        val gamma = 2.0 * PI / 365.0 * (date.dayOfYear - 1 + 0.5)
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val equationOfTime = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )

        val latRad = Math.toRadians(lat)
        val cosHourAngle = cos(Math.toRadians(ZENITH_DEG)) / (cos(latRad) * cos(declination)) -
            tan(latRad) * tan(declination)
        if (!cosHourAngle.isFinite() || cosHourAngle !in -1.0..1.0) return null
        val hourAngleDeg = Math.toDegrees(acos(cosHourAngle))

        // Minutes after 00:00 UTC; solar noon is 720 - 4*longitude - equation of time.
        val utcMinutes = 720.0 - 4.0 * lon - equationOfTime + 4.0 * hourAngleDeg
        val baseUtc = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        var epochS = baseUtc + Math.round(utcMinutes * 60.0)

        // Longitudes near the date line can put the UTC sunset on the neighbouring day; shift it
        // back so the result is the sunset seen on [date] in [zone].
        while (Instant.ofEpochSecond(epochS).atZone(zone).toLocalDate().isBefore(date)) epochS += 86_400
        while (Instant.ofEpochSecond(epochS).atZone(zone).toLocalDate().isAfter(date)) epochS -= 86_400
        return epochS * 1000L
    }
}

package com.ridedeck.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** Totals for one calendar-local week or month. No stopped time is inferred. */
data class TripSummary(
    val periodStart: LocalDate,
    val periodEndInclusive: LocalDate,
    val tripCount: Int,
    val distanceM: Double,
    val totalMs: Long,
    val movingMs: Long,
    /** Number of records inside this period that were invalid and NOT included in the totals. */
    val omittedCount: Int = 0,
    /** True when a total saturated or a distance exceeds safe display precision. */
    val capped: Boolean = false,
) {
    /** Average over moving time only, using the same distance/moving-time basis as TripStats.avgKmh. */
    val averageMovingKmh: Int
        get() {
            if (movingMs <= 0) return 0
            val value = distanceM / (movingMs / 1000.0) * 3.6
            return if (value.isFinite()) value.roundToInt() else Int.MAX_VALUE
        }
}

data class TripSummaries(
    val week: TripSummary,
    val month: TripSummary,
    /** Records whose local start date could not be resolved; never assigned to a period. */
    val unassignedDateCount: Int = 0,
)

/**
 * Calendar-local week/month summaries built from the already-loaded trip list only.
 * Boundaries are explicit in [Instant], [ZoneId] and [Locale]: the first day of week and minimal
 * days come from the locale's [WeekFields], and dates are resolved in the given time zone so DST
 * transitions cannot shift a trip into a neighbouring day. No GPS points or database reads.
 *
 * A trip is assigned to the local date of `startedAt`; an overnight trip is counted whole in the
 * period containing its start and is never split at midnight. Future-starting trips are excluded.
 *
 * Corrupt records are skipped, not clamped: non-finite or negative distance, negative moving time,
 * and dates that cannot be resolved are invalid. Valid records keep their authoritative stored
 * stats. Total duration is read from the authoritative `Trip.durationMs` property; this summary
 * performs no subtraction of its own. A moving time greater than wall-clock duration is retained
 * because wall-clock changes can produce that legitimately. [TripSummary.omittedCount] makes every skipped in-period record
 * visible to the UI instead of silently claiming complete totals. Counts and Long totals saturate
 * at the type maximum; distance accumulation saturates at Double.MAX_VALUE and very large finite
 * distances set [TripSummary.capped], so the UI can avoid showing a falsely precise label.
 */
object TripSummaryMath {
    /** Above this the shared duration formatter cannot show an exact label. */
    private const val MAX_DISPLAY_MS = 2_000_000_000_000L

    fun of(trips: List<Trip>, now: Instant, zoneId: ZoneId, locale: Locale): TripSummaries {
        val today = now.atZone(zoneId).toLocalDate()
        val weekFields = WeekFields.of(locale)
        val weekStart = today.with(weekFields.dayOfWeek(), 1L)
        val weekEnd = weekStart.plusDays(6)
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth())
        val nowMillis = now.toEpochMilli()
        val unassigned = trips.count { trip ->
            trip.startedAt <= nowMillis && localDate(trip.startedAt, zoneId) == null
        }
        return TripSummaries(
            week = summarize(trips, weekStart, weekEnd, zoneId, nowMillis),
            month = summarize(trips, monthStart, monthEnd, zoneId, nowMillis),
            unassignedDateCount = unassigned,
        )
    }

    private fun localDate(epochMillis: Long, zoneId: ZoneId): LocalDate? =
        runCatching { Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate() }.getOrNull()

    /** First instant of the next local calendar day, DST-safe; used for lifecycle-aware refresh. */
    fun nextLocalMidnight(now: Instant, zoneId: ZoneId): Instant =
        now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()

    private fun summarize(
        trips: List<Trip>,
        start: LocalDate,
        end: LocalDate,
        zoneId: ZoneId,
        nowMillis: Long,
    ): TripSummary {
        var count = 0
        var distance = 0.0
        var total = 0L
        var moving = 0L
        var omitted = 0
        var capped = false
        for (trip in trips) {
            if (trip.startedAt > nowMillis) continue
            val day = localDate(trip.startedAt, zoneId) ?: continue
            if (day.isBefore(start) || day.isAfter(end)) continue
            val tripDistance = trip.stats.distanceM
            val tripMoving = trip.stats.movingMs
            val tripTotal = trip.durationMs
            if (!tripDistance.isFinite() || tripDistance < 0.0 || tripMoving < 0L) {
                if (omitted < Int.MAX_VALUE) omitted++
                continue
            }
            if (count < Int.MAX_VALUE) count++ else capped = true
            // Authoritative stored stats: moving > wall duration can happen after wall-clock changes
            // and is retained rather than silently dropping the user's record.
            if (tripDistance > Int.MAX_VALUE.toDouble()) capped = true
            if (tripDistance > Double.MAX_VALUE - distance) {
                distance = Double.MAX_VALUE
                capped = true
            } else {
                distance += tripDistance
            }
            if (tripTotal > Long.MAX_VALUE - total) {
                total = Long.MAX_VALUE
                capped = true
            } else {
                total += tripTotal
            }
            if (tripMoving > Long.MAX_VALUE - moving) {
                moving = Long.MAX_VALUE
                capped = true
            } else {
                moving += tripMoving
            }
        }
        return TripSummary(
            periodStart = start,
            periodEndInclusive = end,
            tripCount = count,
            distanceM = distance,
            totalMs = total,
            movingMs = moving,
            omittedCount = omitted,
            capped = capped || distance > Int.MAX_VALUE.toDouble() ||
                total > MAX_DISPLAY_MS || moving > MAX_DISPLAY_MS,
        )
    }
}

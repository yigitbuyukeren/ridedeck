package com.ridedeck.app.data

import kotlin.math.abs

/**
 * A saved trip prepared for drawing. [segments] never bridge a gap: invalid fixes, non-increasing
 * timestamps, long GPS outages and implausible jumps start a new segment. [start] and [end] are the
 * first and last usable fixes before sampling; a one-point trip keeps the same point for both.
 *
 * [pointCount] is the number of usable saved fixes before sampling. [sampledPointCount] is the
 * number of points actually returned in [segments] and never exceeds the requested budget.
 * [gaps] describes every break by its index in the usable, pre-sampling sequence, so sampling
 * cannot make two segments look connected.
 *
 * [omittedSegmentCount] counts whole segments dropped because the budget was too small. The UI
 * should warn whenever [wasSimplified] is true: some saved geometry is not in [segments].
 */
data class TripTrace(
    val segments: List<List<GeoPoint>>,
    val start: GeoPoint?,
    val end: GeoPoint?,
    val pointCount: Int,
    val sampledPointCount: Int,
    val gaps: List<TripGap>,
    val omittedSegmentCount: Int = 0,
) {
    /** True when saved points or whole segments were dropped for drawing. */
    val wasSimplified: Boolean
        get() = omittedSegmentCount > 0 || sampledPointCount < pointCount

    companion object {
        /** Matches TripMath: a longer silence is a break, never a straight line. */
        const val MAX_GAP_MS = 30_000L

        /** Faster than this between two fixes is a GPS jump, not riding (about 250 km/h). */
        const val MAX_PLAUSIBLE_MPS = 70.0

        /** Rendering budget for a single prepared trip; callers may choose a smaller or larger value. */
        const val DEFAULT_MAX_POINTS = 2_000

        /**
         * Pure preparation from stored points. [sampledPointCount] is strictly at most
         * `maxPoints.coerceAtLeast(2)`. Global first/last fixes are always present. Whole segments
         * are kept uniformly while they fit; remaining segments are omitted and counted, never
         * merged across a gap. With a 2- or 3-point budget the two global endpoints are returned as
         * separate one-point segments instead of a line that would pretend the gap was ridden.
         */
        fun from(points: List<TripPoint>, maxPoints: Int = DEFAULT_MAX_POINTS): TripTrace {
            val limit = maxPoints.coerceAtLeast(2)
            val rawSegments = ArrayList<MutableList<GeoPoint>>()
            val gaps = ArrayList<TripGap>()
            var current = ArrayList<GeoPoint>()
            var previous: TripPoint? = null
            var usable = 0
            var start: GeoPoint? = null
            var end: GeoPoint? = null

            fun closeSegment() {
                if (current.isNotEmpty()) {
                    rawSegments += current
                    current = ArrayList()
                }
            }

            for (raw in points) {
                val invalid = invalidReason(raw)
                if (invalid != null) {
                    val before = previous
                    if (before != null) {
                        gaps += TripGap(
                            afterPointIndex = usable - 1,
                            durationMs = elapsedMs(before.t, raw.t),
                            distanceM = 0.0,
                            reason = invalid,
                        )
                        closeSegment()
                        previous = null
                    }
                    continue
                }

                val point = GeoPoint(raw.lat, raw.lon)
                val before = previous
                if (before != null) {
                    val dt = raw.t - before.t
                    val distance = Geo.distance(GeoPoint(before.lat, before.lon), point)
                    val reason = when {
                        dt <= 0L -> TripGapReason.TIME_NOT_INCREASING
                        dt > MAX_GAP_MS -> TripGapReason.LONG_GAP
                        distance / (dt / 1000.0) > MAX_PLAUSIBLE_MPS -> TripGapReason.IMPLAUSIBLE_JUMP
                        else -> null
                    }
                    if (reason != null) {
                        gaps += TripGap(usable - 1, elapsedMs(before.t, raw.t), distance, reason)
                        closeSegment()
                    }
                }

                if (start == null) start = point
                end = point
                current += point
                usable++
                previous = raw
            }
            closeSegment()

            val sliced = slice(rawSegments, limit)
            return TripTrace(
                segments = sliced.segments,
                start = start,
                end = end,
                pointCount = usable,
                sampledPointCount = sliced.segments.sumOf { it.size },
                gaps = gaps,
                omittedSegmentCount = sliced.omitted,
            )
        }

        /** Subtracts only when [to] is later, so Long.MIN_VALUE cannot overflow before clamping. */
        private fun elapsedMs(from: Long, to: Long): Long = if (to <= from) 0L else to - from

        private fun invalidReason(point: TripPoint): TripGapReason? = when {
            !point.lat.isFinite() || !point.lon.isFinite() ||
                abs(point.lat) > 90.0 || abs(point.lon) > 180.0 -> TripGapReason.INVALID_COORDINATE
            point.t <= 0L -> TripGapReason.INVALID_TIMESTAMP
            else -> null
        }

        private data class Sliced(val segments: List<List<GeoPoint>>, val omitted: Int)

        private fun slice(segments: List<List<GeoPoint>>, maxPoints: Int): Sliced {
            if (segments.isEmpty()) return Sliced(emptyList(), 0)
            if (segments.size == 1) {
                val only = segments[0]
                return Sliced(listOf(sampleOne(only, minOf(maxPoints, only.size))), 0)
            }
            if (maxPoints < 4) {
                // Too little room to draw any kept segment as a line. Mark the two ends separately.
                return Sliced(
                    listOf(listOf(segments.first().first()), listOf(segments.last().last())),
                    segments.size - 2,
                )
            }

            val last = segments.lastIndex
            val firstCost = minOf(segments[0].size, 2)
            val lastCost = minOf(segments[last].size, 2)
            val roomForMiddle = maxPoints - firstCost - lastCost
            val middleCount = segments.size - 2
            val keepMiddle = if (middleCount > 0) minOf(middleCount, roomForMiddle / 2) else 0
            val kept = ArrayList<Int>(keepMiddle + 2).apply {
                add(0)
                addAll(uniformIndices(keepMiddle, 1, last - 1))
                add(last)
            }

            val targets = IntArray(kept.size) { i -> minOf(segments[kept[i]].size, 2) }
            var remaining = maxPoints - targets.sum()
            while (remaining > 0) {
                var added = false
                for (i in targets.indices) {
                    if (remaining <= 0) break
                    if (targets[i] < segments[kept[i]].size) {
                        targets[i]++
                        remaining--
                        added = true
                    }
                }
                if (!added) break
            }

            val sampled = kept.mapIndexed { i, index -> sampleOne(segments[index], targets[i]) }
            return Sliced(sampled, segments.size - kept.size)
        }

        /** [count] indices spread evenly across [from]..[to], including both ends when count > 1. */
        private fun uniformIndices(count: Int, from: Int, to: Int): List<Int> {
            if (count <= 0 || from > to) return emptyList()
            if (count == 1) return listOf(from + (to - from) / 2)
            val span = to - from
            return (0 until count).map { i -> from + (span.toLong() * i / (count - 1)).toInt() }
        }

        private fun sampleOne(segment: List<GeoPoint>, target: Int): List<GeoPoint> {
            if (target >= segment.size) return segment.toList()
            if (target <= 1) return listOf(segment.first())
            return ArrayList<GeoPoint>(target).apply {
                for (i in 0 until target) {
                    val index = (i.toLong() * (segment.size - 1) / (target - 1)).toInt()
                    add(segment[index])
                }
            }
        }
    }
}

/**
 * One break in a prepared trace. [afterPointIndex] is the last usable point before the break,
 * [durationMs] is non-negative and [distanceM] is the straight-line distance across the break
 * (0 for an invalid fix, because there is no trustworthy coordinate to measure from).
 */
data class TripGap(
    val afterPointIndex: Int,
    val durationMs: Long,
    val distanceM: Double,
    val reason: TripGapReason,
)

enum class TripGapReason {
    INVALID_COORDINATE,
    INVALID_TIMESTAMP,
    TIME_NOT_INCREASING,
    LONG_GAP,
    IMPLAUSIBLE_JUMP,
}

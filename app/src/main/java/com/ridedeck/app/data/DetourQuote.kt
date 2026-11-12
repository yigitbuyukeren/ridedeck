package com.ridedeck.app.data

import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A selected-station detour quote. This is intentionally a quote only: it never mutates navigation,
 * never starts guidance, and never substitutes straight-line distance for a routed delta.
 */
enum class DetourStatus { AVAILABLE, UNAVAILABLE }

enum class DetourUnavailableReason {
    STALE_CONTEXT,
    STOP_LIMIT,
    BASELINE_FAILED,
    VIA_FAILED,
    BUDGET_UNAVAILABLE,
    MOTION_LOCKED,
    INVALID_ROUTE,
    CONTEXT_MISMATCH,
    NEGATIVE_DELTA,
}

data class DetourQuote(
    val status: DetourStatus,
    val extraDistanceM: Double = 0.0,
    val extraTimeS: Double = 0.0,
    val reason: DetourUnavailableReason? = null,
) {
    val available: Boolean get() = status == DetourStatus.AVAILABLE

    companion object {
        fun available(extraDistanceM: Double, extraTimeS: Double) =
            DetourQuote(DetourStatus.AVAILABLE, extraDistanceM, extraTimeS)

        fun unavailable(reason: DetourUnavailableReason) =
            DetourQuote(DetourStatus.UNAVAILABLE, reason = reason)
    }
}

/**
 * Exact quote context. Every field that changes the route is part of identity; two contexts are
 * interchangeable only when all of them match exactly. `freshFix` is supplied by the existing
 * location policy owner; this type does not invent a freshness threshold.
 */
data class DetourContext(
    val origin: GeoPoint,
    val fixAtMs: Long,
    val final: Place,
    val remainingStops: List<Place>,
    val options: RouteOptions,
    val headingDeg: Double?,
    val language: String,
    val miles: Boolean,
    val maxStops: Int,
    val contextGeneration: Long,
    val freshFix: Boolean,
) {
    /** Target/options/stops/heading/unit changes always invalidate a quote. */
    fun routePlanMatches(other: DetourContext): Boolean =
        contextGeneration == other.contextGeneration &&
            final == other.final &&
            remainingStops == other.remainingStops &&
            options == other.options &&
            headingDeg == other.headingDeg &&
            language == other.language &&
            miles == other.miles &&
            maxStops == other.maxStops

    /** Origin is a stationary snapshot, not a new route identity for every GPS sample. */
    fun originWithin(other: DetourContext, toleranceM: Double = ORIGIN_TOLERANCE_M): Boolean =
        Geo.distance(origin, other.origin) <= toleranceM

    companion object {
        /** Explicit bounded movement allowance for a stationary quote context. */
        const val ORIGIN_TOLERANCE_M = 25.0
        /** Bearing jitter inside this bucket must not invalidate an otherwise identical context. */
        const val HEADING_BUCKET_DEG = 30.0

        fun quantizeHeading(deg: Double?): Double? {
            if (deg == null || !deg.isFinite()) return null
            val normalized = (deg % 360.0 + 360.0) % 360.0
            return (normalized / HEADING_BUCKET_DEG).roundToInt()
                .times(HEADING_BUCKET_DEG.toInt())
                .toDouble()
                .rem(360.0)
        }
    }
}

data class DetourRequestKey(val context: DetourContext, val station: Place)

object DetourMath {
    /** Tiny negative values are route-answer noise, not a shorter route claim. */
    const val ROUNDING_TOLERANCE_M = 1.0
    const val ROUNDING_TOLERANCE_S = 1.0
    /** Road snapping can move an endpoint slightly even for the same input position. */
    const val ENDPOINT_TOLERANCE_M = 25.0
    /** A selected waypoint should be linked to a stop index within this distance. */
    const val STOP_LINK_TOLERANCE_M = 75.0

    fun quote(
        baseline: Route,
        via: Route,
        station: Place? = null,
        remainingStops: List<Place> = emptyList(),
    ): DetourQuote {
        if (!baseline.isUsable() || !via.isUsable()) {
            return DetourQuote.unavailable(DetourUnavailableReason.INVALID_ROUTE)
        }
        val baselineStart = baseline.shape.first()
        val viaStart = via.shape.first()
        val baselineEnd = baseline.shape.last()
        val viaEnd = via.shape.last()
        if (Geo.distance(baselineStart, viaStart) > ENDPOINT_TOLERANCE_M ||
            Geo.distance(baselineEnd, viaEnd) > ENDPOINT_TOLERANCE_M
        ) {
            return DetourQuote.unavailable(DetourUnavailableReason.CONTEXT_MISMATCH)
        }
        if (station != null) {
            if (!routeVisitsStops(via, listOf(station) + remainingStops)) {
                return DetourQuote.unavailable(DetourUnavailableReason.CONTEXT_MISMATCH)
            }
            if (remainingStops.isNotEmpty() && !routeVisitsStops(baseline, remainingStops)) {
                return DetourQuote.unavailable(DetourUnavailableReason.CONTEXT_MISMATCH)
            }
        }

        val extraM = via.distanceM - baseline.distanceM
        val extraS = via.durationS - baseline.durationS
        if (!extraM.isFinite() || !extraS.isFinite()) {
            return DetourQuote.unavailable(DetourUnavailableReason.INVALID_ROUTE)
        }
        if (extraM < -ROUNDING_TOLERANCE_M || extraS < -ROUNDING_TOLERANCE_S) {
            return DetourQuote.unavailable(DetourUnavailableReason.NEGATIVE_DELTA)
        }
        return DetourQuote.available(
            extraDistanceM = extraM.coerceAtLeast(0.0),
            extraTimeS = extraS.coerceAtLeast(0.0),
        )
    }

    private fun routeVisitsStops(route: Route, points: List<Place>): Boolean {
        if (route.stopIndices.size != points.size) return false
        if (route.maneuvers.count { it.isStop } < points.size) return false
        return points.indices.all { i ->
            val index = route.stopIndices[i]
            index in route.shape.indices &&
                Geo.distance(route.shape[index], points[i].point) <= STOP_LINK_TOLERANCE_M
        }
    }
}

/** Memory-only, bounded, short-lived successful quote cache. No disk, analytics or persistence. */
class DetourQuoteCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private data class Entry(val quote: DetourQuote, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<DetourRequestKey, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DetourRequestKey, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: DetourRequestKey, nowMs: Long): DetourQuote? {
        val entry = entries[key] ?: return null
        if (nowMs >= entry.expiresAtMs) {
            entries.remove(key)
            return null
        }
        return entry.quote
    }

    @Synchronized
    fun put(key: DetourRequestKey, quote: DetourQuote, nowMs: Long) {
        if (!quote.available) return
        entries[key] = Entry(quote, nowMs + ttlMs)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    init {
        require(maxEntries in 1..MAX_CACHE_ENTRIES) { "Cache capacity must be between 1 and $MAX_CACHE_ENTRIES" }
        require(ttlMs in 1L..MAX_TTL_MS) { "Cache TTL must be between 1 and $MAX_TTL_MS ms" }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val DEFAULT_TTL_MS = 60_000L
        const val MAX_CACHE_ENTRIES = 256
        const val MAX_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}

/**
 * Explicit selected-station quote. At most two cancellable routing calls are made:
 * one exact baseline and one route with the selected station inserted before remaining stops.
 * It never mutates navigation and never starts guidance.
 */
internal class DetourQuoteOrchestrator(
    private val routing: Routing,
    private val cache: DetourQuoteCache = DetourQuoteCache(),
    /** Monotonic cache clock; immune to wall-clock rollback. */
    private val cacheNowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Wall clock for the existing fix-age policy, not cache TTL. */
    private val wallNowMs: () -> Long = System::currentTimeMillis,
    /** Budgeted by default; tests inject a direct or fake-clock gate. */
    private val requestGate: QuoteRequestGate = BudgetedQuoteRequestGate(
        ProviderRequestBudget(ProviderBudgetDefaults.CONSERVATIVE),
        nowMs = { System.nanoTime() / 1_000_000L },
        wait = { delay(it) },
    ),
) {
    suspend fun quote(
        context: DetourContext,
        station: Place,
        currentContext: (() -> DetourContext?)? = null,
    ): DetourQuote {
        val current = currentContext ?: { context }
        if (!isCurrent(context, current())) {
            return DetourQuote.unavailable(DetourUnavailableReason.STALE_CONTEXT)
        }
        // Adding fuel must not silently drop a planned stop.
        if (context.remainingStops.size >= context.maxStops) {
            return DetourQuote.unavailable(DetourUnavailableReason.STOP_LIMIT)
        }

        val key = DetourRequestKey(context, station)
        cache.get(key, cacheNowMs())?.let { return it }

        val baselinePoints = listOf(context.origin) +
            context.remainingStops.map { it.point } +
            context.final.point
        val baselineResult = requestRoute(baselinePoints, context, "baseline", null)
        if (baselineResult.isFailure) {
            return DetourQuote.unavailable(
                failureReason(baselineResult, DetourUnavailableReason.BASELINE_FAILED)
            )
        }
        if (!isCurrent(context, current())) {
            return DetourQuote.unavailable(DetourUnavailableReason.STALE_CONTEXT)
        }

        val viaPoints = listOf(context.origin, station.point) +
            context.remainingStops.map { it.point } +
            context.final.point
        val viaResult = requestRoute(viaPoints, context, "via", station)
        if (viaResult.isFailure) {
            return DetourQuote.unavailable(
                failureReason(viaResult, DetourUnavailableReason.VIA_FAILED)
            )
        }
        if (!isCurrent(context, current())) {
            return DetourQuote.unavailable(DetourUnavailableReason.STALE_CONTEXT)
        }

        val quote = DetourMath.quote(
            baselineResult.getOrThrow(),
            viaResult.getOrThrow(),
            station = station,
            remainingStops = context.remainingStops,
        )
        if (quote.available) cache.put(key, quote, cacheNowMs())
        return quote
    }

    private suspend fun requestRoute(
        points: List<GeoPoint>,
        context: DetourContext,
        role: String,
        station: Place?,
    ): Result<Route> {
        val identity = buildString {
            append("quote:").append(role)
            append(':').append(context.contextGeneration)
            append(':').append(context.origin.lat).append(',').append(context.origin.lon)
            append("->").append(context.final.point.lat).append(',').append(context.final.point.lon)
            station?.let { append(":station=").append(it.point.lat).append(',').append(it.point.lon) }
            append(":stops=").append(context.remainingStops.joinToString("|") { "${it.point.lat},${it.point.lon}" })
        }
        return requestGate.run(VALHALLA_PROVIDER_KEY, identity) {
            routing.routeVia(points, context.options, context.headingDeg).getOrThrow()
        }
    }

    private fun failureReason(result: Result<*>, fallback: DetourUnavailableReason): DetourUnavailableReason =
        if (result.exceptionOrNull() is BudgetUnavailableException) DetourUnavailableReason.BUDGET_UNAVAILABLE else fallback

    /** Rechecks the current route plan, bounded stationary origin and the existing fix-age policy. */
    private fun isCurrent(context: DetourContext, current: DetourContext?): Boolean {
        if (current == null) return false
        if (!current.routePlanMatches(context) || !current.originWithin(context)) return false
        if (!current.freshFix || current.fixAtMs <= 0L) return false
        val age = wallNowMs() - current.fixAtMs
        return age in 0 until LocationFixPolicy.MAX_AGE_MS
    }

    private companion object {
        const val VALHALLA_PROVIDER_KEY = "provider:valhalla"
    }
}

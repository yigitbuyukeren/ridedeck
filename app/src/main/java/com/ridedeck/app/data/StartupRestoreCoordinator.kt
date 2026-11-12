package com.ridedeck.app.data

/** Minimal startup dependencies, so delayed-store/user-action races are testable without Android. */
interface StartupTripStore {
    fun read(): String?
    fun clearIf(expected: String)
}

interface StartupGeometrySource {
    suspend fun restore(activeTrip: ActiveTrip, nowMs: Long): RouteGeometryRestoreResult
}

interface StartupNavigationPort {
    fun routeChoiceCount(): Long
    fun hasDestination(): Boolean
    fun isNavigating(): Boolean
    fun restoreCachedPreview(snapshot: RouteGeometrySnapshot): Boolean
    fun restoreNetworkTrip(activeTrip: ActiveTrip)
    fun markNeedsNetwork()
}

enum class StartupRestoreOutcome { NONE, CACHED_PREVIEW, NETWORK_PREVIEW, NEEDS_NETWORK, ABORTED }

/**
 * Extracted startup restore coordinator. It rechecks user actions, destination/navigation, stored
 * ActiveTrip identity and TTL after the geometry IO returns; a newer record is never overwritten.
 */
class StartupRestoreCoordinator(
    private val trips: StartupTripStore,
    private val geometry: StartupGeometrySource,
    private val navigation: StartupNavigationPort,
    private val connectivity: () -> ConnectivityStatus,
    private val nowMs: () -> Long,
) {
    suspend fun restore(savedJson: String?, saved: ActiveTrip?, routeChoicesAtStart: Long): StartupRestoreOutcome {
        if (savedJson == null || saved == null) return StartupRestoreOutcome.NONE

        val storedBefore = trips.read()
        if (!gate(savedJson, storedBefore, saved, routeChoicesAtStart, nowMs())) {
            if (storedBefore == savedJson) trips.clearIf(savedJson)
            return StartupRestoreOutcome.ABORTED
        }

        val cached = geometry.restore(saved, nowMs())
        val after = nowMs()
        val storedAfter = trips.read()
        if (!gate(savedJson, storedAfter, saved, routeChoicesAtStart, after)) {
            if (storedAfter == savedJson) trips.clearIf(savedJson)
            return StartupRestoreOutcome.ABORTED
        }

        if (cached is RouteGeometryRestoreResult.Restored &&
            cached.snapshot.fresh(after) &&
            navigation.restoreCachedPreview(cached.snapshot)
        ) {
            return StartupRestoreOutcome.CACHED_PREVIEW
        }

        return when (StartupGeometryPolicy.fallback(false, connectivity())) {
            StartupGeometryFallback.NETWORK_FALLBACK -> {
                navigation.restoreNetworkTrip(saved)
                StartupRestoreOutcome.NETWORK_PREVIEW
            }
            StartupGeometryFallback.NEEDS_NETWORK -> {
                navigation.markNeedsNetwork()
                StartupRestoreOutcome.NEEDS_NETWORK
            }
            StartupGeometryFallback.NONE -> StartupRestoreOutcome.NONE
        }
    }

    private fun gate(
        savedJson: String,
        storedJson: String?,
        saved: ActiveTrip,
        routeChoicesAtStart: Long,
        nowMs: Long,
    ): Boolean = ActiveTripRestoreGate.shouldRestore(
        originalJson = savedJson,
        storedJson = storedJson,
        saved = saved,
        nowMs = nowMs,
        routeChoicesAtStart = routeChoicesAtStart,
        routeChoicesNow = navigation.routeChoiceCount(),
        destinationPresent = navigation.hasDestination(),
        navigating = navigation.isNavigating(),
    )
}

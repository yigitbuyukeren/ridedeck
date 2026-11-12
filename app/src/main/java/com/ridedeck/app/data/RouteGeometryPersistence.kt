package com.ridedeck.app.data

/** Progress-free route key: no point index/fraction, ETA or timestamps. */
data class RouteGeometryKey(
    val route: Route,
    val destination: Place,
    val finalDestination: Place?,
    val stopsAhead: List<Place>,
    val options: RouteOptions,
    /** True for a route sliced at a passed-stop boundary from the same trip. */
    val derived: Boolean = false,
)

sealed interface RouteGeometryDecision {
    data class Save(val key: RouteGeometryKey) : RouteGeometryDecision
    data object Clear : RouteGeometryDecision
    data object None : RouteGeometryDecision
}

/** Pure save/clear gate for FEAT-004; storage and IO belong to RouteGeometryStore/RideNavigation. */
object RouteGeometryPersistence {
    fun decide(ui: NavUi, options: RouteOptions): RouteGeometryDecision {
        when (ui.termination) {
            NavigationTermination.USER_CANCELLED -> return RouteGeometryDecision.Clear
            NavigationTermination.ARRIVED -> if (ui.finalDestination == null) return RouteGeometryDecision.Clear
            else -> Unit
        }

        val destination = ui.destination ?: return RouteGeometryDecision.None
        val route = ui.route ?: return RouteGeometryDecision.None
        // The current TECH-004 restore previews the post-fuel final leg; never persist pump-leg geometry here.
        if (ui.finalDestination != null) return RouteGeometryDecision.Clear
        if (!ui.navigating) return RouteGeometryDecision.None

        val stopsAhead = ui.stops.drop(ui.stopsPassed)
        if (ui.stopsPassed > 0) {
            val sliced = RouteGeometrySlice.afterPassedStop(route, ui.stops, ui.stopsPassed)
                ?: return RouteGeometryDecision.Clear
            if (!RouteGeometrySnapshot.linkageValid(destination, null, stopsAhead, sliced)) {
                return RouteGeometryDecision.Clear
            }
            return RouteGeometryDecision.Save(
                RouteGeometryKey(sliced, destination, null, stopsAhead, options, derived = true)
            )
        }
        // After a passed stop the saved full geometry still contains old stop indices: invalidate, do not remap.
        if (stopsAhead.size != route.stopIndices.size) return RouteGeometryDecision.Clear
        if (!RouteGeometrySnapshot.linkageValid(destination, null, stopsAhead, route)) return RouteGeometryDecision.Clear
        return RouteGeometryDecision.Save(RouteGeometryKey(route, destination, null, stopsAhead, options))
    }

    /**
     * Preserve the original geometry creation clock while only options change or while a route is
     * sliced at a passed-stop boundary. A genuinely new route gets a new clock.
     */
    fun createdAtMs(previous: RouteGeometryKey?, previousCreatedAtMs: Long, next: RouteGeometryKey, nowMs: Long): Long =
        when {
            previous == null -> nowMs
            next.derived && previous.destination.point == next.destination.point -> previousCreatedAtMs
            previous.route == next.route -> previousCreatedAtMs
            else -> nowMs
        }
}

enum class StartupGeometryFallback { NONE, NETWORK_FALLBACK, NEEDS_NETWORK }

/** Pure gate for the explicit cached-route retry; motion/input is the existing owner's fact. */
object CachedRetryPolicy {
    fun allowed(
        contextPresent: Boolean,
        connectivity: ConnectivityStatus,
        inputLocked: Boolean,
        freshFix: Boolean,
        permissionGranted: Boolean,
        providerEnabled: Boolean,
    ): Boolean = contextPresent &&
        connectivity == ConnectivityStatus.VALIDATED &&
        !inputLocked &&
        freshFix &&
        permissionGranted &&
        providerEnabled
}

/** No implicit route request unless the injected connectivity fact is VALIDATED. */
object StartupGeometryPolicy {
    fun fallback(hasUsableCachedPreview: Boolean, connectivity: ConnectivityStatus): StartupGeometryFallback =
        when {
            hasUsableCachedPreview -> StartupGeometryFallback.NONE
            connectivity == ConnectivityStatus.VALIDATED -> StartupGeometryFallback.NETWORK_FALLBACK
            else -> StartupGeometryFallback.NEEDS_NETWORK
        }
}

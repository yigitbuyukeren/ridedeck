package com.ridedeck.app.data

/** The navigation state that matters for process-death recovery; passed stops are already removed. */
data class ActiveTripSnapshot(
    val guiding: Boolean,
    val destination: Place?,
    val finalDestination: Place?,
    val stopsAhead: List<Place>,
    val options: RouteOptions,
    val termination: NavigationTermination = NavigationTermination.NONE,
) {
    companion object {
        fun from(ui: NavUi, options: RouteOptions): ActiveTripSnapshot = ActiveTripSnapshot(
            guiding = ui.navigating,
            destination = ui.destination,
            finalDestination = ui.finalDestination,
            stopsAhead = ui.stops.drop(ui.stopsPassed),
            options = options,
            termination = ui.termination,
        )
    }
}

/** A single persistence decision for the active trip. */
sealed interface ActiveTripWrite {
    /** Not guiding before or now: leave a previous record alone. */
    data object None : ActiveTripWrite

    /** Guidance or a restored preview ended: remove the record. */
    data object Clear : ActiveTripWrite

    /** Guidance is active: replace the record with the current remaining trip. */
    data class Save(val trip: ActiveTrip) : ActiveTripWrite
}

object ActiveTripPersistence {
    /**
     * Save while guiding, clear once guidance or a restored preview ends, otherwise leave storage untouched.
     * [wasGuiding] separates a normal idle refresh from a cancel/arrival transition. Options are part of the snapshot
     * so a changed road preference is persisted without waiting for the next GPS fix.
     *
     * An invalid stop list is never truncated: the record is cleared instead, so an old trip cannot survive a
     * controller invariant break.
     */
    fun next(wasGuiding: Boolean, snapshot: ActiveTripSnapshot, nowMs: Long): ActiveTripWrite {
        val destination = snapshot.destination
        val stopsValid = snapshot.stopsAhead.size <= ActiveTrip.MAX_STOPS
        return when {
            snapshot.termination == NavigationTermination.USER_CANCELLED -> ActiveTripWrite.Clear
            snapshot.guiding && !stopsValid -> ActiveTripWrite.Clear
            snapshot.guiding && destination != null -> ActiveTripWrite.Save(
                ActiveTrip(
                    destination = destination,
                    finalDestination = snapshot.finalDestination,
                    savedAtMs = nowMs,
                    stops = snapshot.stopsAhead,
                    options = snapshot.options,
                )
            )
            wasGuiding -> ActiveTripWrite.Clear
            else -> ActiveTripWrite.None
        }
    }
}

/**
 * Startup restore gate: re-check age after the GPS wait and never resurrect a trip after the rider has chosen or
 * cancelled a route in this process. [originalJson] must still be the stored record; a newer save wins.
 */
object ActiveTripRestoreGate {
    fun shouldRestore(
        originalJson: String?,
        storedJson: String?,
        saved: ActiveTrip?,
        nowMs: Long,
        routeChoicesAtStart: Long,
        routeChoicesNow: Long,
        destinationPresent: Boolean,
        navigating: Boolean,
    ): Boolean =
        saved != null &&
            originalJson != null &&
            originalJson == storedJson &&
            saved.fresh(nowMs) &&
            routeChoicesNow == routeChoicesAtStart &&
            !destinationPresent &&
            !navigating
}

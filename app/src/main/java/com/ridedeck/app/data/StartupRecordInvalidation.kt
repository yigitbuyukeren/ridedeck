package com.ridedeck.app.data

/**
 * Synchronous persisted invalidation for startup/cancel races. STOP always clears the current persisted
 * record; ordinary route choices clear only the still-pending startup record; START keeps it for guidance save.
 */
class StartupRecordInvalidation(
    private val trips: StartupTripStore,
    private val clearGeometry: () -> Unit,
) {
    var startupJson: String? = null

    fun onRouteChoice(reason: RouteChoiceReason) {
        when (reason) {
            RouteChoiceReason.START -> Unit
            RouteChoiceReason.STOP -> {
                trips.read()?.let(trips::clearIf)
                clearGeometry()
            }
            else -> {
                val pending = startupJson ?: return
                if (trips.read() == pending) {
                    trips.clearIf(pending)
                    clearGeometry()
                }
            }
        }
    }
}

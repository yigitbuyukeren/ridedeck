package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Bound once by the process owner, independently of any dashboard collector. */
class NavigationRideLifecycle(
    scope: CoroutineScope,
    nav: NavigationController,
    ride: RideSessionController,
    guidanceChanged: (Boolean) -> Unit,
) {
    init {
        scope.launch {
            var previousGuidance: Boolean? = null
            var previousEnd = NavigationTermination.NONE
            nav.ui.collect { state ->
                // A planned stop is still part of the guided ride, including a retryable continuation.
                val guiding = state.navigating || (state.finalDestination != null && state.termination == NavigationTermination.ARRIVED)
                if (guiding != previousGuidance) {
                    previousGuidance = guiding
                    guidanceChanged(guiding)
                }
                val end = if (state.termination == NavigationTermination.ARRIVED && state.finalDestination != null) {
                    NavigationTermination.NONE
                } else state.termination
                // Old terminal UI may remain visible during a new, manually started recording.
                if (end == previousEnd) return@collect
                previousEnd = end
                when (end) {
                    NavigationTermination.USER_CANCELLED -> ride.stop()
                    NavigationTermination.ARRIVED -> if (state.finalDestination == null) ride.stop()
                    NavigationTermination.LOCATION_DISABLED -> ride.stop(RideFailure.GPS_DISABLED)
                    NavigationTermination.LOCATION_PERMISSION_REVOKED -> ride.stop(RideFailure.PERMISSION)
                    NavigationTermination.TRACKING_ERROR -> ride.stop(RideFailure.INTERRUPTED)
                    NavigationTermination.NONE -> Unit
                }
            }
        }
        scope.launch {
            var hadRide = false
            ride.state.collect { state ->
                if (state.running) hadRide = true
                if (hadRide && (state.phase == RidePhase.IDLE || state.phase == RidePhase.STOPPING)) {
                    val ui = nav.ui.value
                    // Also cancel a pending continuation/detour; its late result must never start a ride.
                    // Preserve the arrival summary and typed GPS error when guidance has already ended.
                    val endedWithError = ui.termination == NavigationTermination.LOCATION_DISABLED ||
                        ui.termination == NavigationTermination.LOCATION_PERMISSION_REVOKED ||
                        ui.termination == NavigationTermination.TRACKING_ERROR
                    if (!endedWithError && (ui.navigating || ui.loading || ui.rerouting || ui.finalDestination != null)) nav.stopNavigation()
                    if (state.phase == RidePhase.IDLE) hadRide = false
                }
            }
        }
    }
}

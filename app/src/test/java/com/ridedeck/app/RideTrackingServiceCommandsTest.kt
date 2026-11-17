package com.ridedeck.app

import com.ridedeck.app.service.RideServiceCommand
import com.ridedeck.app.service.RideTrackingService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Command decisions only; the Android promotion and the notification are exercised on a device.
 * Every command is delivered on the main thread, so these transitions cannot interleave.
 */
class RideTrackingServiceCommandsTest {
    private fun decide(
        action: String?,
        incoming: Long = 0L,
        request: Long = 0L,
        accepts: Boolean = false,
        active: Boolean = false,
        running: Boolean = false,
    ) = RideTrackingService.decide(action, incoming, request, accepts, active, running)

    @Test
    fun acceptedStartRuns() {
        assertEquals(RideServiceCommand.START, decide(RideTrackingService.ACTION_START, incoming = 1, accepts = true))
    }

    @Test
    fun stopBeforeAnyStartOnlyStopsThePromotedService() {
        assertEquals(RideServiceCommand.STOP_SERVICE, decide(RideTrackingService.ACTION_STOP))
    }

    @Test
    fun activeRideAcceptsItsStop() {
        assertEquals(RideServiceCommand.STOP,
            decide(RideTrackingService.ACTION_STOP, incoming = 1, request = 1, active = true, running = true))
    }

    @Test
    fun duplicateStartAndLateStopKeepTheCurrentRide() {
        assertEquals(RideServiceCommand.IGNORE,
            decide(RideTrackingService.ACTION_START, incoming = 1, request = 1, running = true))
        assertEquals(RideServiceCommand.IGNORE,
            decide(RideTrackingService.ACTION_STOP, incoming = 1, request = 1, running = true))
        // A stop notification from the ended ride, after a newer start owns the service.
        assertEquals(RideServiceCommand.IGNORE,
            decide(RideTrackingService.ACTION_STOP, incoming = 1, request = 2, running = true))
    }

    @Test
    fun startStopStartUsesTheNewTokenAndAnOldStopCannotKillIt() {
        assertEquals(RideServiceCommand.START,
            decide(RideTrackingService.ACTION_START, incoming = 2, request = 1, accepts = true))
        assertEquals(RideServiceCommand.IGNORE,
            decide(RideTrackingService.ACTION_STOP, incoming = 1, request = 2, running = true))
    }

    @Test
    fun unacceptedStartWithoutASessionDoesNotLeakANonForegroundService() {
        assertEquals(RideServiceCommand.STOP_SERVICE,
            decide(RideTrackingService.ACTION_START, incoming = 3, request = 0))
        // After a ride ended, the request field is stale but no phase is running.
        assertEquals(RideServiceCommand.STOP_SERVICE,
            decide(RideTrackingService.ACTION_START, incoming = 3, request = 2))
    }

    @Test
    fun unknownIntentOnlyStopsWhenNoRideIsOwned() {
        assertEquals(RideServiceCommand.STOP_SERVICE, decide("com.ridedeck.app.UNKNOWN"))
        assertEquals(RideServiceCommand.STOP_SERVICE, decide(null))
        assertEquals(RideServiceCommand.IGNORE,
            decide("com.ridedeck.app.UNKNOWN", request = 2, running = true))
    }
}

package com.ridedeck.app

import com.ridedeck.app.data.CallAudioRoutePolicy
import org.junit.Assert.*
import org.junit.Test

class CallAudioRoutePolicyTest {
    @Test fun bluetoothCanBeSelectedOnlyWhileAvailable() {
        assertTrue(CallAudioRoutePolicy.supports(2, 1 or 2 or 8))
        assertFalse(CallAudioRoutePolicy.supports(2, 1 or 8))
        assertTrue(CallAudioRoutePolicy.supports(8, 1 or 8))
    }

    @Test fun partialMasksAndUnknownRoutesAreRejected() {
        assertFalse(CallAudioRoutePolicy.supports(3, 1))
        assertFalse(CallAudioRoutePolicy.supports(3, 1 or 2))
        assertFalse(CallAudioRoutePolicy.supports(0, 15))
        assertFalse(CallAudioRoutePolicy.supports(32, 63))
    }
}

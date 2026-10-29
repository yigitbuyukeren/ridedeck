package com.ridedeck.app

import com.ridedeck.app.data.LocationFixPolicy
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.hasFreshFix
import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessTest {
    @Test fun rejectsCachedReorderedFutureAndInvalidPositions() {
        assertTrue(LocationFixPolicy.accepts(9_000, 8_000, 10_000, 41.0, 29.0))
        assertFalse(LocationFixPolicy.accepts(1_000, 0, 10_000, 41.0, 29.0))
        assertFalse(LocationFixPolicy.accepts(8_000, 9_000, 10_000, 41.0, 29.0))
        assertFalse(LocationFixPolicy.accepts(9_000, 9_000, 10_000, 41.0, 29.0))
        assertFalse(LocationFixPolicy.accepts(11_000, 9_000, 10_000, 41.0, 29.0))
        assertFalse(LocationFixPolicy.accepts(9_000, 0, 10_000, Double.NaN, 29.0))
        assertFalse(LocationFixPolicy.accepts(9_000, 0, 10_000, 91.0, 29.0))
    }

    @Test fun disabledGpsAndRevokedPermissionInvalidateAnOtherwiseFreshFix() {
        val fix = LocationState(fixAtMs = 10_000)
        assertTrue(fix.hasFreshFix(11_000))
        assertFalse(fix.copy(gpsProviderEnabled = false).hasFreshFix(11_000))
        assertFalse(fix.copy(locationPermissionGranted = false).hasFreshFix(11_000))
        assertFalse(LocationState().hasFreshFix(1_000))
    }

    @Test fun elapsedClockKeepsFreshnessCorrectAcrossWallClockChanges() {
        val fix = LocationState(fixAtMs = 10_000, elapsedRealtimeMs = 5_000)
        assertTrue(fix.hasFreshFix(1_000, 6_000))
        assertFalse(fix.hasFreshFix(10_001, 11_000))
        assertFalse(fix.hasFreshFix(9_000))
    }
}

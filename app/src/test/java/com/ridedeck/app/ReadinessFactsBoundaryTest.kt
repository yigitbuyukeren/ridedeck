package com.ridedeck.app

import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.LocationFixPolicy
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.ReadinessDetail
import com.ridedeck.app.data.ReadinessKind
import com.ridedeck.app.data.ReadinessStatus
import com.ridedeck.app.data.RideReadinessAssessment
import com.ridedeck.app.data.RideReadinessInput
import com.ridedeck.app.data.TtsFacts
import com.ridedeck.app.data.hasFreshFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake facts only: deterministic boundaries for the approved readiness mapping. */
class ReadinessFactsBoundaryTest {
    private val ready = RideReadinessInput(
        fineLocationGranted = true,
        coarseLocationGranted = true,
        gpsEnabled = true,
        hasFreshFix = true,
        internet = ConnectivityStatus.VALIDATED,
        tts = TtsFacts(engineInitialized = true, languageAvailable = true, selectedVoiceRequiresNetwork = false),
        notificationsGranted = true,
        mediaAccess = true,
        sosContactsConfigured = true,
        sosSmsGranted = true,
    )

    @Test fun permissionChangeRecomputesLocationFromCoarseToFine() {
        val coarse = RideReadinessAssessment.assess(ready.copy(fineLocationGranted = false, coarseLocationGranted = true))
        assertEquals(ReadinessStatus.MISSING, coarse.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessDetail.LOCATION_COARSE_ONLY, coarse.item(ReadinessKind.PRECISE_LOCATION).detail)

        val fine = RideReadinessAssessment.assess(ready)
        assertEquals(ReadinessStatus.READY, fine.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessDetail.LOCATION_FINE_GRANTED, fine.item(ReadinessKind.PRECISE_LOCATION).detail)
    }

    @Test fun unknownLocationFactsNeverBecomeReady() {
        val unknown = RideReadinessAssessment.assess(ready.copy(fineLocationGranted = null, coarseLocationGranted = null))
        assertEquals(ReadinessStatus.UNKNOWN, unknown.item(ReadinessKind.PRECISE_LOCATION).status)
        assertEquals(ReadinessDetail.LOCATION_UNKNOWN, unknown.item(ReadinessKind.PRECISE_LOCATION).detail)
    }

    @Test fun disabledGpsAndUnvalidatedInternetAreMissingNotReady() {
        val gpsOff = RideReadinessAssessment.assess(ready.copy(gpsEnabled = false))
        assertEquals(ReadinessStatus.MISSING, gpsOff.item(ReadinessKind.GPS).status)
        assertEquals(ReadinessDetail.GPS_DISABLED, gpsOff.item(ReadinessKind.GPS).detail)

        val unvalidated = RideReadinessAssessment.assess(ready.copy(internet = ConnectivityStatus.UNVALIDATED))
        assertEquals(ReadinessStatus.MISSING, unvalidated.item(ReadinessKind.INTERNET).status)
        assertEquals(ReadinessDetail.INTERNET_UNVALIDATED, unvalidated.item(ReadinessKind.INTERNET).detail)
    }

    @Test fun disabledNotificationsAreOptionalButUnknownReadIsUnknown() {
        val disabled = RideReadinessAssessment.assess(ready.copy(notificationsGranted = false))
        assertEquals(ReadinessStatus.OPTIONAL, disabled.item(ReadinessKind.NOTIFICATIONS).status)
        assertEquals(ReadinessDetail.NOTIFICATIONS_DENIED, disabled.item(ReadinessKind.NOTIFICATIONS).detail)

        val unknown = RideReadinessAssessment.assess(ready.copy(notificationsGranted = null))
        assertEquals(ReadinessStatus.UNKNOWN, unknown.item(ReadinessKind.NOTIFICATIONS).status)
        assertEquals(ReadinessDetail.NOTIFICATIONS_UNKNOWN, unknown.item(ReadinessKind.NOTIFICATIONS).detail)
    }

    @Test fun freshnessBoundaryUsesTheExistingPolicy() {
        val start = 1_000L
        val fix = LocationState(
            fixAtMs = start, elapsedRealtimeMs = start,
            gpsProviderEnabled = true, locationPermissionGranted = true,
        )
        val justFresh = start + LocationFixPolicy.MAX_AGE_MS - 1
        val atBoundary = start + LocationFixPolicy.MAX_AGE_MS
        assertTrue(fix.hasFreshFix(nowMs = justFresh, elapsedMs = justFresh))
        assertFalse(fix.hasFreshFix(nowMs = atBoundary, elapsedMs = atBoundary))
        assertFalse(fix.copy(gpsProviderEnabled = false).hasFreshFix(nowMs = justFresh, elapsedMs = justFresh))
    }
}

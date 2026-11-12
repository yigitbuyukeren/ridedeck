package com.ridedeck.app

import com.ridedeck.app.data.ReadinessDetail
import com.ridedeck.app.data.ReadinessItem
import com.ridedeck.app.data.ReadinessKind
import com.ridedeck.app.data.ReadinessStatus
import com.ridedeck.app.ui.ReadinessAction
import com.ridedeck.app.ui.readinessAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only rows with a real settings destination expose an action. */
class ReadinessActionTest {
    @Test fun actionsPointAtTheExistingSettingsOwners() {
        assertEquals(ReadinessAction.APP_DETAILS, readinessAction(item(ReadinessKind.PRECISE_LOCATION, ReadinessDetail.LOCATION_COARSE_ONLY)))
        assertEquals(ReadinessAction.LOCATION_SOURCE, readinessAction(item(ReadinessKind.GPS, ReadinessDetail.GPS_DISABLED)))
        assertEquals(ReadinessAction.NETWORK_SETTINGS, readinessAction(item(ReadinessKind.INTERNET, ReadinessDetail.INTERNET_OFFLINE)))
        assertEquals(ReadinessAction.NOTIFICATION_SETTINGS, readinessAction(item(ReadinessKind.NOTIFICATIONS, ReadinessDetail.NOTIFICATIONS_DENIED)))
        assertEquals(ReadinessAction.NOTIFICATION_ACCESS, readinessAction(item(ReadinessKind.MEDIA, ReadinessDetail.MEDIA_UNAVAILABLE)))
        assertNull(readinessAction(item(ReadinessKind.OFFLINE_MAP, ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED)))
        assertNull(readinessAction(item(ReadinessKind.VOICE, ReadinessDetail.VOICE_UNKNOWN)))
        assertNull(readinessAction(item(ReadinessKind.SOS, ReadinessDetail.SOS_CONFIGURED_UNVERIFIED)))
    }

    @Test fun gpsWithoutPrecisePermissionUsesPermissionRecoveryNotProviderSettings() {
        assertEquals(
            ReadinessAction.APP_DETAILS,
            readinessAction(item(ReadinessKind.GPS, ReadinessDetail.GPS_PERMISSION_MISSING)),
        )
        assertEquals(
            ReadinessAction.LOCATION_SOURCE,
            readinessAction(item(ReadinessKind.GPS, ReadinessDetail.GPS_WAITING_FIX)),
        )
    }

    private fun item(kind: ReadinessKind, detail: ReadinessDetail) =
        ReadinessItem(kind, ReadinessStatus.UNKNOWN, detail)
}

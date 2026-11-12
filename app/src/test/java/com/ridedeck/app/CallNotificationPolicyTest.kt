package com.ridedeck.app

import com.ridedeck.app.data.CallNotificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android-version gate for a ringing call's full-screen intent (CallNotificationPolicy.kt). */
class CallNotificationPolicyTest {
    @Test
    fun beforeAndroid14TheManifestPermissionIsTheOnlyGate() {
        assertTrue(CallNotificationPolicy.fullScreenIntentAllowed(sdkInt = 33, allowedBySystem = false))
    }

    @Test
    fun android14AndLaterFollowTheUserControlledSwitch() {
        assertTrue(CallNotificationPolicy.fullScreenIntentAllowed(sdkInt = 34, allowedBySystem = true))
        assertFalse(CallNotificationPolicy.fullScreenIntentAllowed(sdkInt = 34, allowedBySystem = false))
        assertFalse(CallNotificationPolicy.fullScreenIntentAllowed(sdkInt = 35, allowedBySystem = false))
    }
}

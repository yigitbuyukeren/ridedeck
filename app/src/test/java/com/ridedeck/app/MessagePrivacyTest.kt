package com.ridedeck.app

import com.ridedeck.app.data.MessagePrivacy
import com.ridedeck.app.data.MessagePrivacy.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Matrix for the pure privacy gate used by CarNotificationListener.
 *
 * Android wiring (instrumentation-only, explained here because it needs a real KeyguardManager and
 * NotificationListenerService): `onNotificationPosted` passes `notification.visibility` and
 * `keyguardManager.isKeyguardLocked` to [MessagePrivacy.surface]. SKIP returns before parsing; APP_ONLY
 * never calls `parse(notification)` and publishes only the app label plus the redacted string; FULL
 * uses the existing parser. Group-summary, muted-app and duplicate-key handling stay before the gate.
 */
class MessagePrivacyTest {
    @Test
    fun secretContentIsNeverSurfaced() {
        assertEquals(Surface.SKIP, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_SECRET, keyguardLocked = false))
        assertEquals(Surface.SKIP, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_SECRET, keyguardLocked = true))
    }

    @Test
    fun publicContentIsSurfacedLockedAndUnlocked() {
        assertEquals(Surface.FULL, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_PUBLIC, keyguardLocked = false))
        assertEquals(Surface.FULL, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_PUBLIC, keyguardLocked = true))
    }

    @Test
    fun privateContentIsFullWhenUnlockedButAppOnlyWhenLocked() {
        assertEquals(Surface.FULL, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_PRIVATE, keyguardLocked = false))
        assertEquals(Surface.APP_ONLY, MessagePrivacy.surface(MessagePrivacy.VISIBILITY_PRIVATE, keyguardLocked = true))
    }

    @Test
    fun unknownVisibilityIsTreatedConservatively() {
        // Platform values today are only -1/0/1; anything else must not leak content.
        assertEquals(Surface.APP_ONLY, MessagePrivacy.surface(visibility = 2, keyguardLocked = false))
        assertEquals(Surface.APP_ONLY, MessagePrivacy.surface(visibility = 2, keyguardLocked = true))
    }

    @Test
    fun visibilityConstantsMatchThePlatformContract() {
        // Documented values from android.app.Notification; repeated here to keep the policy JVM-pure.
        assertEquals(-1, MessagePrivacy.VISIBILITY_SECRET)
        assertEquals(0, MessagePrivacy.VISIBILITY_PRIVATE)
        assertEquals(1, MessagePrivacy.VISIBILITY_PUBLIC)
    }
}

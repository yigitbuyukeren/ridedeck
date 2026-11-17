package com.ridedeck.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReason
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression for the transition that a phase-only observer missed: the dashboard is visible when
 * the countdown starts, then the screen goes dark. The SOS notification must still appear on the
 * lock screen, be public, and offer the "İyiyim" action. Uses TEST (no SMS, no contacts).
 */
class SosLockScreenAlertTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val safety get() = (context.applicationContext as RideDeckApplication).rideSafety
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun await(what: String, block: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!block() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(what, block())
    }

    @Before fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
        runCatching { shell("appops set --uid ${context.packageName} POST_NOTIFICATION allow") }
        safety.sos.dismiss()
    }

    @After fun cleanup() {
        safety.sos.cancel()
        safety.sos.dismiss()
        manager.cancel(40)
    }

    @Test fun screenOffDuringCountdownPostsPublicSosNotificationWithCancelAction() {
        safety.setScreenVisible(true)
        safety.sos.trigger(SosReason.TEST)
        await("test countdown should start") { safety.sos.ui.value.phase == SosPhase.COUNTDOWN }

        // The countdown began while the dashboard was visible. Now the screen goes off / another
        // app comes forward: the phase does not change, only screen visibility does.
        safety.setScreenVisible(false)

        await("SOS notification after screen off") {
            manager.activeNotifications.any { it.id == 40 }
        }
        val posted = manager.activeNotifications.single { it.id == 40 }
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.notification.visibility)
        val actions = posted.notification.actions.orEmpty().map { it.title?.toString() }
        assertTrue(
            "the lock-screen notification must offer the I am fine action; actions=$actions",
            actions.any { it == context.getString(R.string.sos_action_fine) },
        )

        safety.cancelFromNotification()
        await("notification action cancels the countdown") { safety.sos.ui.value.phase == SosPhase.IDLE }
        // NotificationManager removes the posted notification asynchronously; wait for it.
        await("notification is removed after cancel") {
            manager.activeNotifications.none { it.id == 40 }
        }
    }
}

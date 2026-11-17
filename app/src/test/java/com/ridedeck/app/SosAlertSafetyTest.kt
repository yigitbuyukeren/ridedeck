package com.ridedeck.app

import com.ridedeck.app.data.CrashDetector
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.SmsReport
import com.ridedeck.app.data.SmsSender
import com.ridedeck.app.data.SmsStatus
import com.ridedeck.app.data.SosAlarm
import com.ridedeck.app.data.SosAlertPermissionFacts
import com.ridedeck.app.data.SosAlertPlan
import com.ridedeck.app.data.SosAlertPolicy
import com.ridedeck.app.data.SosAlertWarning
import com.ridedeck.app.data.SosBlocker
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.SosController
import com.ridedeck.app.data.SosLockScreenPolicy
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReadiness
import com.ridedeck.app.data.SosReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five minute cooldown exists so one crash cannot alert twice. After the rider presses "İyiyim"
 * it must not also swallow a *second* crash, while the same recorded samples still cannot fire twice.
 */
class CrashCancelRegressionTest {
    private fun riding(d: CrashDetector, from: Long, seconds: Int = 5) {
        var t = from
        repeat(seconds * 50) { d.onSample(t, 1.05f, 60, 0f, 0f, 1f); t += 20 }
    }

    /** The impact plus nine seconds of phone-lying-still samples; true once the detector fires. */
    private fun crash(d: CrashDetector, at: Long, speedKmh: Int = 60): Boolean {
        var hit = d.onSample(at, 7.5f, speedKmh)
        var t = at + 20
        repeat(9 * 50) { hit = d.onSample(t, 1f, 0) || hit; t += 20 }
        return hit
    }

    @Test
    fun acknowledgedCancelLetsAGenuineSecondCrashWithinFiveMinutesAlert() {
        val d = CrashDetector()
        riding(d, 0)
        assertTrue(crash(d, 5_000))
        d.acknowledgeCancel()
        // The rider says "I am fine", then really crashes again ~7 s later: well inside the old cooldown.
        riding(d, 15_000)
        assertTrue(crash(d, 20_000))
    }

    @Test
    fun acknowledgedCancelDoesNotLetTheSameSamplesFireAgain() {
        val d = CrashDetector()
        riding(d, 0)
        assertTrue(crash(d, 5_000))
        d.acknowledgeCancel()
        // The same trace continues: bike stopped, phone lying still. No new ride, no new impact.
        var t = 15_000L
        var again = false
        repeat(30 * 50) { again = d.onSample(t, 1f, 0, 0f, 0f, 1f) || again; t += 20 }
        assertFalse(again)
    }

    @Test
    fun acknowledgedCancelStillNeedsANewRidingWindowBeforeArming() {
        val d = CrashDetector()
        riding(d, 0)
        assertTrue(crash(d, 5_000))
        d.acknowledgeCancel()
        // A hard knock while the bike is parked must not arm: there was no riding speed after the cancel.
        var t = 15_000L
        var hit = d.onSample(t, 8f, 0)
        t += 20
        repeat(10 * 50) { hit = d.onSample(t, 1f, 0, 0f, 0f, 1f) || hit; t += 20 }
        assertFalse(hit)
    }
}

/** The countdown fallback decisions, covered as plain booleans so no Android runtime is needed. */
class SosAlertPolicyTest {
    @Test
    fun notificationsThatAreAllowedCarryTheFullScreenIntentWhenThePolicyPermitsIt() {
        assertEquals(
            SosAlertPlan(postNotification = true, fullScreenIntent = true, wakeScreen = true, launchApp = false),
            SosAlertPolicy.plan(
                notificationsEnabled = true, notificationPermission = true, fullScreenIntentAllowed = true,
                screenOff = true, canStartActivity = true,
            ),
        )
    }

    @Test
    fun aRefusedFullScreenIntentKeepsTheHeadsUpNotificationAndStillWakesTheScreen() {
        val plan = SosAlertPolicy.plan(true, true, false, true, false)
        assertTrue(plan.postNotification)
        assertFalse("FSI denied on API 34+", plan.fullScreenIntent)
        assertTrue(plan.wakeScreen)
    }

    @Test
    fun deniedNotificationPermissionStillWakesTheScreenAndTriesTheActivity() {
        val plan = SosAlertPolicy.plan(notificationsEnabled = true, notificationPermission = false, fullScreenIntentAllowed = true, screenOff = true, canStartActivity = true)
        assertFalse(plan.postNotification)
        assertFalse(plan.fullScreenIntent)
        assertTrue("the screen is woken without a notification", plan.wakeScreen)
        assertTrue(plan.launchApp)
    }

    @Test
    fun notificationsSwitchedOffInSystemSettingsHasTheSameFallback() {
        val plan = SosAlertPolicy.plan(notificationsEnabled = false, notificationPermission = true, fullScreenIntentAllowed = true, screenOff = true, canStartActivity = true)
        assertFalse(plan.postNotification)
        assertTrue(plan.wakeScreen)
        assertTrue(plan.launchApp)
    }

    @Test
    fun aBlockedBackgroundLaunchIsNotAttemptedAndAnOnScreenIsNotWoken() {
        val blocked = SosAlertPolicy.plan(false, false, false, screenOff = false, canStartActivity = false)
        assertFalse(blocked.launchApp)
        assertFalse(blocked.wakeScreen)
        assertFalse(blocked.postNotification)
    }

    @Test
    fun aNotificationIsNeverPostedWithoutPermissionButTheAlarmIsIndependentOfThisPlan() {
        // The plan only describes the notification; the beep/TTS/SMS are owned by SosController.
        val plan = SosAlertPolicy.plan(false, false, true, true, false)
        assertFalse(plan.postNotification)
        assertFalse(plan.fullScreenIntent)
        assertFalse(plan.launchApp)
    }
}

/** A saved contact plus SMS permission is not readiness when the phone cannot send at all. */
class SosReadinessTest {    @Test
    fun contactsAndPermissionAloneDoNotEnableTheTriggersWithoutUsableTelephony() {
        assertFalse("no telephony", SosReadiness.triggersReady(1, true, false, true))
        assertFalse("no default SMS SIM", SosReadiness.triggersReady(1, true, true, false))
        assertFalse("no contact", SosReadiness.triggersReady(0, true, true, true))
        assertFalse("no SMS permission", SosReadiness.triggersReady(1, false, true, true))
        assertTrue(SosReadiness.triggersReady(3, true, true, true))
    }

    @Test
    fun blockerNamesTheFirstRealProblem() {
        assertEquals(SosBlocker.NO_CONTACTS, SosReadiness.blocker(0, true, true, true))
        assertEquals(SosBlocker.NO_TELEPHONY, SosReadiness.blocker(2, true, false, true))
        assertEquals(SosBlocker.NO_SIM, SosReadiness.blocker(2, true, true, false))
        assertEquals(SosBlocker.NO_SMS_PERMISSION, SosReadiness.blocker(2, false, true, true))
        assertNull(SosReadiness.blocker(2, true, true, true))
    }

    @Test
    fun theRadioProblemIsVisibleEvenWhenContactsAndPermissionAreReady() {
        // The settings card asks about the radio alone so a missing SIM is never hidden by another problem.
        assertEquals(SosBlocker.NO_SIM, SosReadiness.blocker(contacts = 1, smsPermission = true, telephony = true, defaultSmsUsable = false))
        assertEquals(SosBlocker.NO_TELEPHONY, SosReadiness.blocker(contacts = 1, smsPermission = true, telephony = false, defaultSmsUsable = true))
        assertNull(SosReadiness.blocker(contacts = 1, smsPermission = true, telephony = true, defaultSmsUsable = true))
    }
}

/** Lock-screen visibility warnings: they never gate SOS, and each platform state names the right fix. */
class SosAlertPermissionFactsTest {
    @Test
    fun aFullyAllowedPhoneHasNoWarnings() {
        assertTrue(SosAlertPermissionFacts().warnings.isEmpty())
    }

    @Test
    fun notificationsOffAndARefusedFullScreenIntentAreBothNamed() {
        val facts = SosAlertPermissionFacts(notificationsEnabled = false, postNotificationsGranted = false, fullScreenIntentAllowed = false)
        assertEquals(listOf(SosAlertWarning.NOTIFICATIONS_OFF, SosAlertWarning.FULL_SCREEN_INTENT_DENIED), facts.warnings)
    }

    @Test
    fun aDeniedPostNotificationsPermissionIsNamedAlongsideARefusedFullScreenIntent() {
        val facts = SosAlertPermissionFacts(notificationsEnabled = true, postNotificationsGranted = false, fullScreenIntentAllowed = false)
        assertEquals(listOf(SosAlertWarning.POST_NOTIFICATIONS_DENIED, SosAlertWarning.FULL_SCREEN_INTENT_DENIED), facts.warnings)
    }

    @Test
    fun aDeniedPostNotificationsPermissionAloneIsStillNamed() {
        val facts = SosAlertPermissionFacts(notificationsEnabled = true, postNotificationsGranted = false, fullScreenIntentAllowed = true)
        assertEquals(listOf(SosAlertWarning.POST_NOTIFICATIONS_DENIED), facts.warnings)
    }

    @Test
    fun aRefusedFullScreenIntentIsReportedEvenWhenNotificationsWork() {
        val facts = SosAlertPermissionFacts(notificationsEnabled = true, postNotificationsGranted = true, fullScreenIntentAllowed = false)
        assertEquals(listOf(SosAlertWarning.FULL_SCREEN_INTENT_DENIED), facts.warnings)
    }

    @Test
    fun warningsAreNotABlockerSoSosStaysConfigurable() {
        val facts = SosAlertPermissionFacts(notificationsEnabled = false, postNotificationsGranted = false, fullScreenIntentAllowed = false)
        assertTrue(facts.warnings.isNotEmpty())
        // Readiness only looks at contacts, SMS permission and the radio; visibility plays no part.
        assertTrue(SosReadiness.triggersReady(1, true, true, true))
    }

    @Test
    fun onlyTheCountdownShowsOverTheKeyguardAndTurnsTheScreenOn() {
        assertTrue(SosLockScreenPolicy.showOverKeyguard(SosPhase.COUNTDOWN))
        // The dashboard and every result screen must stay behind the lock screen.
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.IDLE))
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.SENDING))
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.SENT))
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.PARTIAL))
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.UNKNOWN))
        assertFalse(SosLockScreenPolicy.showOverKeyguard(SosPhase.FAILED))
    }
}

/** "İyiyim" must re-arm crash detection; dismissing a result or closing must not. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SosCancelAcknowledgeTest {
    private val location = MutableStateFlow(LocationState(fixAtMs = 100_000L, lat = 41.0, lon = 29.0))
    private val contacts = MutableStateFlow(listOf(SosContact("One", "15550000001")))
    private val sms = SmsSender { numbers, _ -> SmsReport(numbers.associateWith { SmsStatus.SENT }) }
    private val alarm = object : SosAlarm {
        override fun beep() {}
        override fun stop() {}
    }

    private fun TestScope.controller(onCancelled: () -> Unit): SosController = SosController(
        scope = backgroundScope,
        location = location,
        contacts = contacts,
        sms = sms,
        alarm = alarm,
        smsAllowed = { true },
        speak = { _, _ -> },
        message = { _, _, _ -> "" },
        onCancelled = onCancelled,
    )

    @Test
    fun cancellingTheCountdownAcknowledgesTheCancelExactlyOnce() = runTest {
        var acknowledged = 0
        val c = controller { acknowledged++ }
        c.trigger(SosReason.AUTO)
        runCurrent()
        assertEquals(SosPhase.COUNTDOWN, c.ui.value.phase)
        c.cancel()
        c.cancel()
        assertEquals(1, acknowledged)
        assertEquals(SosPhase.IDLE, c.ui.value.phase)
    }

    @Test
    fun dismissingAResultOrClosingDoesNotAcknowledgeACrashCancel() = runTest {
        var acknowledged = 0
        val c = controller { acknowledged++ }
        c.trigger(SosReason.MANUAL)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(SosPhase.SENT, c.ui.value.phase)
        c.dismiss()
        c.close()
        assertEquals(0, acknowledged)
    }

    @Test
    fun aTriggerThatFailsBeforeTheCountdownNeverAcknowledges() = runTest {
        var acknowledged = 0
        contacts.value = emptyList()
        val c = controller { acknowledged++ }
        c.trigger(SosReason.AUTO)
        runCurrent()
        assertEquals(SosPhase.FAILED, c.ui.value.phase)
        c.cancel()
        assertEquals(0, acknowledged)
    }
}

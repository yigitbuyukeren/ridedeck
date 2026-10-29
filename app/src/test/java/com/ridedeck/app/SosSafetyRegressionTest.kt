package com.ridedeck.app

import com.ridedeck.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosSafetyRegressionTest {
    private class Rig(scope: TestScope) {
        val contacts = MutableStateFlow(listOf(SosContact("One", "15550000001"), SosContact("Two", "15550000002")))
        val location = MutableStateFlow(LocationState(fixAtMs = 100_000L, lat = 41.0, lon = 29.0))
        var allowed = true; var stops = 0; var sends = 0
        var alarmThrows = false
        var report: SmsReport? = null
        var gate: CompletableDeferred<Unit>? = null
        var failUnexpectedly = false
        var lastNumbers = emptyList<String>()
        var lastBody = ""
        val controller = SosController(scope.backgroundScope, location, contacts, SmsSender { numbers, text ->
            sends++; lastNumbers = numbers; lastBody = text
            gate?.await()
            if (failUnexpectedly) throw IllegalStateException("transport disconnected")
            report ?: SmsReport(numbers.associateWith { SmsStatus.SENT })
        }, object : SosAlarm { override fun beep() { if (alarmThrows) throw SecurityException() }; override fun stop() { stops++ } },
            { allowed }, { _, _ -> }, { _, link, stale -> "$link|$stale" }, now = { 100_000L + scope.testScheduler.currentTime })
    }
    @Test fun permissionAndContactsAreCheckedAgainAtTheEndOfCountdown() = runTest {
        for (permission in listOf(true, false)) {
            val r = Rig(this); r.controller.trigger(SosReason.MANUAL); runCurrent()
            if (permission) r.allowed = false else r.contacts.value = emptyList()
            advanceTimeBy(5_001); runCurrent()
            assertEquals(0, r.sends)
            assertEquals(if (permission) SosError.NO_PERMISSION else SosError.NO_CONTACTS, r.controller.ui.value.error)
            assertTrue(r.stops > 0)
        }
    }
    @Test fun alarmFailureDoesNotCrashCountdownAndCleanupStillRuns() = runTest {
        val r = Rig(this); r.alarmThrows = true
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosPhase.SENT, r.controller.ui.value.phase)
        assertEquals(1, r.sends); assertTrue(r.stops > 0)
    }
    @Test fun partialAndUnknownResultsNeverClaimAllContactsReceivedTheMessage() = runTest {
        val r = Rig(this)
        r.report = SmsReport(mapOf("15550000001" to SmsStatus.SENT, "15550000002" to SmsStatus.FAILED))
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosPhase.PARTIAL, r.controller.ui.value.phase)
        assertEquals(listOf("One"), r.controller.ui.value.sentTo)
        assertEquals(listOf("Two"), r.controller.ui.value.failedTo)
        r.controller.dismiss(); r.report = SmsReport(emptyMap())
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosPhase.UNKNOWN, r.controller.ui.value.phase)
        assertEquals(listOf("One", "Two"), r.controller.ui.value.unknownTo)
    }
    @Test fun sendingCannotBeDismissedOrTriggeredTwiceAndHasNoAutomaticRetry() = runTest {
        val r = Rig(this); r.gate = CompletableDeferred()
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosPhase.SENDING, r.controller.ui.value.phase)
        r.controller.cancel(); r.controller.dismiss(); r.controller.trigger(SosReason.AUTO)
        assertEquals(1, r.sends)
        assertEquals(SosPhase.SENDING, r.controller.ui.value.phase)
        r.gate!!.complete(Unit); runCurrent(); advanceTimeBy(100_000); runCurrent()
        assertEquals(1, r.sends)
    }
    @Test fun newlyAddedContactsAreNotSentAnAlertAlreadyInProgress() = runTest {
        val r = Rig(this); r.contacts.value = r.contacts.value.take(1)
        r.controller.trigger(SosReason.MANUAL); runCurrent()
        r.contacts.value = r.contacts.value + SosContact("New", "15550000003")
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("15550000001"), r.lastNumbers)
    }
    @Test fun teardownStopsAlarmAndNoLateTickCanSendAnSms() = runTest {
        val r = Rig(this); r.controller.trigger(SosReason.AUTO); runCurrent()
        r.controller.close(); advanceTimeBy(60_000); runCurrent()
        assertTrue(r.stops > 0); assertEquals(0, r.sends)
        r.controller.trigger(SosReason.MANUAL); advanceTimeBy(10_000); runCurrent(); assertEquals(0, r.sends)
    }
    @Test fun transportExceptionIsUnknownRatherThanPermissionToRetry() = runTest {
        val r = Rig(this); r.failUnexpectedly = true
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosPhase.UNKNOWN, r.controller.ui.value.phase); assertEquals(1, r.sends)
    }
    @Test fun missingDefaultSimAndOldOrInvalidFixAreReportedTruthfully() = runTest {
        val r = Rig(this); r.report = SmsReport(r.contacts.value.associate { it.number to SmsStatus.FAILED }, SmsProblem.NO_DEFAULT_SIM)
        r.location.value = r.location.value.copy(fixAtMs = 200_000L)
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(SosError.NO_SIM, r.controller.ui.value.error); assertTrue(r.lastBody.endsWith("|true"))
        r.controller.dismiss(); r.location.value = r.location.value.copy(lat = Double.NaN)
        r.controller.trigger(SosReason.MANUAL); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertTrue(r.lastBody.startsWith("null|"))
    }
    @Test fun damagedOrDuplicatedSavedContactsDoNotCauseDuplicateSms() {
        val json = """[{"name":"One","number":"15550000001"},{"name":"Duplicate","number":"15550000001"}]"""
        assertEquals(1, SosMath.fromJson(json).size)
    }
}

class SmsPartTrackerTest {
    @Test fun invalidAndUnselectedSimIdsAreNeverUsedAsAnImplicitFirstSim() {
        assertFalse(SmsSubscriptionPolicy.usable(-1))
        assertFalse(SmsSubscriptionPolicy.usable(Int.MAX_VALUE))
        assertTrue(SmsSubscriptionPolicy.usable(1))
    }
    @Test fun multipartRequiresEveryPartAndIgnoresDuplicateAndUnknownCallbacks() {
        val tracker = SmsPartTracker(3)
        tracker.result(2, true); tracker.result(2, true); tracker.result(99, true)
        assertEquals(SmsStatus.UNKNOWN, tracker.status()); assertFalse(tracker.complete)
        tracker.result(0, true); tracker.result(1, true)
        assertEquals(SmsStatus.SENT, tracker.status())
    }
    @Test fun oneFailedPartNeverBecomesSuccessfulFromALateDuplicate() {
        val tracker = SmsPartTracker(2)
        tracker.result(0, false); tracker.result(0, true); tracker.result(1, true)
        assertEquals(SmsStatus.FAILED, tracker.status()); assertTrue(tracker.complete)
    }
}

class CrashSafetyRegressionTest {
    private fun riding(d: CrashDetector) { for (t in 0L until 5_000L step 20) d.onSample(t, 1f, 60, 0f, 0f, 1f) }
    private fun quiet(d: CrashDetector, start: Long, seconds: Int = 10): Boolean {
        var hit = false
        for (t in start until start + seconds * 1_000L step 20) hit = d.onSample(t, 1f, 0, 0f, 0f, 1f) || hit
        return hit
    }
    @Test fun lifecycleResetAndUnknownSpeedCannotReuseAnEarlierImpact() {
        val d = CrashDetector(); riding(d); d.onSample(5_000, 7f, 60)
        d.reset()
        assertFalse(quiet(d, 5_020))
    }
    @Test fun aSensorGapIsNotEightSecondsOfObservedStillness() {
        val d = CrashDetector(); riding(d); d.onSample(5_000, 7f, 60)
        d.onSample(5_020, 1f, 0)
        assertFalse(quiet(d, 20_000))
    }
    @Test fun twoWheelsRequireTiltEvidenceForAnOrdinaryImpact() {
        val d = CrashDetector(needsTilt = { true }); riding(d); d.onSample(5_000, 4.5f, 60)
        var hit = false
        for (t in 5_020L until 15_000L step 20) hit = d.onSample(t, 1f, 0) || hit
        assertFalse(hit)
    }
    @Test fun corruptSamplesAndClockReorderingCannotArmAnAlert() {
        val d = CrashDetector(); riding(d); d.onSample(5_000, Float.POSITIVE_INFINITY, 60)
        assertFalse(quiet(d, 5_020))
        assertFalse(d.onSample(1, 9f, 60))
    }
    @Test fun resetDoesNotBypassAutomaticAlertCooldown() {
        val d = CrashDetector(); riding(d); d.onSample(5_000, 7f, 60)
        assertTrue(quiet(d, 5_020))
        d.reset(); d.onSample(16_000, 7f, 60)
        assertFalse(quiet(d, 16_020))
    }
}

package com.ridedeck.app

import com.ridedeck.app.data.*
import android.telecom.Call
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class CallerNotificationSourceTest {
    @Test fun unrelatedAppsAndManagedCallCannotOverwriteTheCaller() {
        val source = CallerNotificationSource()
        assertFalse(source.posted("chat", "chat.app", "phone.app", false))
        assertFalse(source.posted("phone", "phone.app", "phone.app", true))
        assertTrue(source.posted("phone", "phone.app", "phone.app", false))
    }
    @Test fun removingAnotherNotificationCannotClearTheCurrentCaller() {
        val source = CallerNotificationSource()
        source.posted("old", "phone.app", "phone.app", false)
        source.posted("new", "phone.app", "phone.app", false)
        assertFalse(source.removed("old", false))
        assertTrue(source.removed("new", false))
    }
    @Test fun notificationAccessLossDoesNotClearTelecomsManagedCallerName() {
        val source = CallerNotificationSource()
        assertFalse(source.disconnected(true))
        assertTrue(source.disconnected(false))
    }
}

class SpeechSessionTest {
    private class Rig {
        var focus = true
        var accepted = true
        var abandoned = 0
        var stopped = 0
        val said = mutableListOf<Pair<String, String>>()
        val speech = SpeechSession({ focus }, { abandoned++ }, { text, id -> said += text to id; accepted }, { stopped++ })
    }
    @Test fun deniedFocusNeverStartsSpeech() {
        val r = Rig(); r.focus = false
        r.speech.announce("turn")
        assertTrue(r.said.isEmpty())
    }
    @Test fun flushAndStaleCallbacksCannotReleaseTheNewPromptsFocus() {
        val r = Rig(); r.speech.announce("one"); val old = r.said.last().second
        r.speech.announce("two"); val current = r.said.last().second
        assertEquals(1, r.stopped)
        r.speech.complete(old); r.speech.timeout(old)
        assertEquals(1, r.abandoned)
        r.speech.complete(current)
        assertEquals(2, r.abandoned)
    }
    @Test fun callAndNoisyRouteInterruptionDoNotReplayStaleAnnouncements() {
        val r = Rig(); r.speech.announce("before")
        r.speech.suspendForCall(true)
        r.speech.announce("during")
        r.speech.suspendForCall(false)
        assertEquals(1, r.said.size)
        r.speech.announce("after"); r.speech.stop()
        assertEquals(2, r.stopped)
    }
    @Test fun engineRejectionAndTimeoutReleaseFocus() {
        val r = Rig(); r.accepted = false; r.speech.announce("rejected")
        assertEquals(1, r.abandoned)
        r.accepted = true; r.speech.announce("hung"); r.speech.timeout(r.said.last().second)
        assertEquals(2, r.abandoned)
        assertEquals(1, r.stopped)
    }
    @Test fun closingIsIdempotentAndPreventsLaterSpeech() {
        val r = Rig(); r.speech.announce("one"); r.speech.close(); r.speech.close(); r.speech.announce("late")
        assertEquals(1, r.said.size)
        assertEquals(1, r.abandoned)
    }
}

class CallRouteRequestsTest {
    @Test fun endpointTypesMapToRouteMasksWithoutConfusingSpeakerAndWiredHeadset() {
        assertEquals(1, CallAudioRoutePolicy.fromEndpointType(1))
        assertEquals(2, CallAudioRoutePolicy.fromEndpointType(2))
        assertEquals(4, CallAudioRoutePolicy.fromEndpointType(3))
        assertEquals(8, CallAudioRoutePolicy.fromEndpointType(4))
        assertEquals(0, CallAudioRoutePolicy.fromEndpointType(5))
    }
    @Test fun disconnectedBluetoothIsRejectedWithoutForcingSpeaker() {
        val routes = CallRouteRequests(); routes.availability(1 or 2 or 8)
        val request = routes.begin(2)!!
        routes.availability(1 or 8)
        assertTrue(routes.failed)
        assertNull(routes.begin(2))
        routes.failure(request)
        routes.confirmed(1)
        assertFalse(routes.failed)
    }
    @Test fun oldErrorsAndTimeoutsDoNotOverrideNewRequestsOrConfirmedRoutes() {
        val routes = CallRouteRequests(); routes.availability(1 or 2 or 8)
        val old = routes.begin(2)!!; val current = routes.begin(8)!!
        routes.failure(old); assertFalse(routes.failed)
        routes.confirmed(8); routes.failure(current); assertFalse(routes.failed)
    }
    @Test fun callRemovalInvalidatesRequestsAndCombinedMasksAreRejected() {
        val routes = CallRouteRequests(); routes.availability(15)
        assertNull(routes.begin(3))
        val request = routes.begin(2)!!; routes.reset(); routes.failure(request)
        assertFalse(routes.failed)
        assertNull(routes.begin(2))
    }
    @Test fun timeoutIsAnErrorUntilTelecomConfirmsAnActualRoute() {
        val routes = CallRouteRequests(); routes.availability(9)
        routes.failure(routes.begin(8)!!); assertTrue(routes.failed)
        routes.confirmed(1); assertFalse(routes.failed)
    }
    @Test fun liveActiveCallWinsOverHeldAndDisconnectingCallsAreNotActive() {
        assertTrue(TelecomCallPolicy.priority(Call.STATE_RINGING)!! < TelecomCallPolicy.priority(Call.STATE_ACTIVE)!!)
        assertTrue(TelecomCallPolicy.priority(Call.STATE_ACTIVE)!! < TelecomCallPolicy.priority(Call.STATE_HOLDING)!!)
        assertNull(TelecomCallPolicy.priority(Call.STATE_DISCONNECTING))
        assertEquals(CallPhase.IDLE, TelecomCallPolicy.phase(Call.STATE_DISCONNECTED))
        assertEquals(CallPhase.HELD, TelecomCallPolicy.phase(Call.STATE_HOLDING))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DtmfControllerTest {
    @Test fun rapidKeysCannotBeCutShortByThePreviousTimer() = runTest {
        val played = mutableListOf<Char>(); var stopped = 0
        val tones = DtmfController<String>(backgroundScope, { _, key -> played += key }, { stopped++ })
        tones.send("call", true, '1'); runCurrent(); advanceTimeBy(100)
        tones.send("call", true, '2'); runCurrent(); advanceTimeBy(50); runCurrent()
        assertEquals(1, stopped)
        advanceTimeBy(100); runCurrent()
        assertEquals(2, stopped)
        assertEquals(listOf('1', '2'), played)
    }
    @Test fun heldEndedAndUnsupportedKeysNeverReachTheCall() = runTest {
        var played = 0
        val tones = DtmfController<String>(backgroundScope, { _, _ -> played++ }, {})
        assertFalse(tones.send(null, true, '1'))
        assertFalse(tones.send("held", false, '1'))
        assertFalse(tones.send("call", true, 'A'))
        assertEquals(0, played)
    }
    @Test fun scopeTeardownStopsTheToneAndCallExceptionsDoNotCrash() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var stopped = 0
        val tones = DtmfController<String>(owner, { _, _ -> }, { stopped++ })
        tones.send("call", true, '#'); runCurrent(); owner.cancel(); runCurrent()
        assertEquals(1, stopped)
        val broken = DtmfController<String>(backgroundScope, { _, _ -> throw SecurityException() }, {})
        assertFalse(broken.send("gone", true, '1'))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CallInterruptionControllerTest {
    @Test fun disconnectedHeadsetPreventsMusicFromResumingOnThePhoneSpeaker() = runTest {
        val phase = MutableStateFlow(CallPhase.IDLE)
        var playing = true; var resumed = 0; var devices = setOf(7)
        CallInterruptionController(backgroundScope, phase, { playing }, { "player" }, { 0 }, {}, { resumed++ }, outputDevices = { devices })
        runCurrent(); phase.value = CallPhase.ACTIVE; runCurrent(); playing = false
        devices = emptySet(); phase.value = CallPhase.IDLE; runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, resumed)
    }
    @Test fun systemAudioStillInCallPreventsResumeAfterAPhonePermissionLoss() = runTest {
        val phase = MutableStateFlow(CallPhase.IDLE)
        var playing = true; var resumed = 0
        CallInterruptionController(backgroundScope, phase, { playing }, { "player" }, { 0 }, {}, { resumed++ }, canResume = { false })
        runCurrent(); phase.value = CallPhase.ACTIVE; runCurrent(); playing = false
        phase.value = CallPhase.IDLE; runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, resumed)
    }
    private class Rig(scope: TestScope) {
        val phase = MutableStateFlow(CallPhase.IDLE)
        var playing = true; var pkg: String? = "player"; var version = 0L; var resumed = 0; var suspended = false
        val controller = CallInterruptionController(scope.backgroundScope, phase, { playing }, { pkg }, { version }, { suspended = it }, { resumed++ })
    }
    @Test fun aNewCallCancelsThePreviousResumeTimer() = runTest {
        val r = Rig(this); runCurrent()
        r.phase.value = CallPhase.RINGING; runCurrent(); r.playing = false
        r.phase.value = CallPhase.IDLE; runCurrent(); advanceTimeBy(1_000)
        r.phase.value = CallPhase.ACTIVE; runCurrent(); advanceTimeBy(2_000)
        assertEquals(0, r.resumed); assertTrue(r.suspended)
        r.phase.value = CallPhase.IDLE; runCurrent(); advanceTimeBy(1_501); runCurrent()
        assertEquals(1, r.resumed); assertFalse(r.suspended)
    }
    @Test fun playerChangeOrUserActionPreventsAutomaticResume() = runTest {
        for (changePlayer in listOf(true, false)) {
            val r = Rig(this); runCurrent(); r.phase.value = CallPhase.ACTIVE; runCurrent(); r.playing = false
            r.phase.value = CallPhase.IDLE; runCurrent()
            if (changePlayer) r.pkg = "different" else r.version++
            advanceTimeBy(1_501); runCurrent(); assertEquals(0, r.resumed)
        }
    }
    @Test fun musicThatWasNotPlayingIsNeverStartedAndHeldCallKeepsSpeechSuspended() = runTest {
        val r = Rig(this); r.playing = false; runCurrent()
        r.phase.value = CallPhase.HELD; runCurrent(); assertTrue(r.suspended)
        r.phase.value = CallPhase.IDLE; runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, r.resumed)
    }
}

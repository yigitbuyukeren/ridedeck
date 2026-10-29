package com.ridedeck.app

import com.ridedeck.app.data.CrashDetector
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.SmsSender
import com.ridedeck.app.data.SmsReport
import com.ridedeck.app.data.SmsStatus
import com.ridedeck.app.data.SosAlarm
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.SosController
import com.ridedeck.app.data.SosError
import com.ridedeck.app.data.SosMath
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosMathTest {
    @Test
    fun numbersAreCleanedAndShortOnesRejected() {
        assertEquals("+905321234567", SosMath.normalizeNumber(" +90 (532) 123-45-67 "))
        assertEquals("05321234567", SosMath.normalizeNumber("0532 123 45 67"))
        assertNull(SosMath.normalizeNumber("12345"))
        assertNull(SosMath.normalizeNumber("abc"))
    }

    @Test
    fun contactListHoldsThreeAndNoDuplicates() {
        var list = emptyList<SosContact>()
        list = SosMath.addContact(list, SosContact("Anne", "0532 111 22 33"))
        list = SosMath.addContact(list, SosContact("Anne again", "+90 532 111 22 33"))
        assertEquals(1, list.size)
        list = SosMath.addContact(list, SosContact("Baba", "0533 111 22 33"))
        list = SosMath.addContact(list, SosContact("Ali", "0534 111 22 33"))
        list = SosMath.addContact(list, SosContact("Veli", "0535 111 22 33"))
        assertEquals(listOf("Anne", "Baba", "Ali"), list.map { it.name })
    }

    @Test
    fun blankNameFallsBackToTheNumber() {
        val list = SosMath.addContact(emptyList(), SosContact("  ", "0532 111 22 33"))
        assertEquals("05321112233", list.single().name)
    }

    @Test
    fun locationLinkUsesFivePlacesAndADotDecimal() {
        assertEquals(
            "https://www.openstreetmap.org/?mlat=41.01234&mlon=28.97654#map=17/41.01234/28.97654",
            SosMath.locationLink(41.012344, 28.976543),
        )
    }

    @Test
    fun contactsRoundTripAndSurviveDamage() {
        val list = listOf(SosContact("Anne", "+905321112233"))
        assertEquals(list, SosMath.fromJson(SosMath.toJson(list)))
        assertTrue(SosMath.fromJson("{oops").isEmpty())
        assertTrue(SosMath.fromJson(null).isEmpty())
    }
}

class CrashDetectorTest {
    /** Feeds one second of 50 Hz samples. */
    private fun run(d: CrashDetector, from: Long, seconds: Int, g: Float, speed: Int): Boolean {
        var hit = false
        var t = from
        repeat(seconds * 50) {
            if (d.onSample(t, g, speed)) hit = true
            t += 20
        }
        return hit
    }

    @Test
    fun hardImpactWhileRidingThenStillnessTriggersOnce() {
        val d = CrashDetector()
        assertFalse(run(d, 0, 10, 1.1f, 60))              // cruising
        assertFalse(d.onSample(10_000, 7.5f, 60))          // the impact
        assertFalse(run(d, 10_020, 6, 1.0f, 0))            // lying still, not long enough yet
        assertTrue(run(d, 16_020, 4, 1.0f, 0))             // 8 s of stillness reached
        assertFalse(run(d, 20_020, 30, 1.0f, 0))           // cooldown: no second alarm
    }

    @Test
    fun potholeWhileTheBikeKeepsRollingDoesNotTrigger() {
        val d = CrashDetector()
        run(d, 0, 10, 1.1f, 60)
        d.onSample(10_000, 6f, 60)
        assertFalse(run(d, 10_020, 30, 1.2f, 55))
    }

    @Test
    fun hardBrakingWithoutAnImpactDoesNotTrigger() {
        val d = CrashDetector()
        run(d, 0, 10, 1.1f, 80)
        assertFalse(run(d, 10_000, 3, 1.6f, 20))
        assertFalse(run(d, 13_000, 20, 1.0f, 0))
    }

    @Test
    fun bikeKnockedOverWhileParkedDoesNotTrigger() {
        val d = CrashDetector()
        run(d, 0, 30, 1.0f, 0)
        assertFalse(d.onSample(30_000, 8f, 0))
        assertFalse(run(d, 30_020, 30, 1.0f, 0))
    }

    @Test
    fun impactThenRidingOnCancelsTheAlert() {
        val d = CrashDetector()
        run(d, 0, 10, 1.1f, 50)
        d.onSample(10_000, 5f, 50)
        // Wobbles, slows, then speeds up again: the rider is fine.
        assertFalse(run(d, 10_020, 3, 1.5f, 10))
        assertFalse(run(d, 13_020, 30, 1.0f, 40))
    }

    @Test
    fun shakingPhoneAfterTheImpactKeepsWaiting() {
        val d = CrashDetector()
        run(d, 0, 10, 1.1f, 60)
        d.onSample(10_000, 6f, 60)
        // Speed is zero but the phone keeps being thrown around: not the quiet of a person lying still.
        assertFalse(run(d, 10_020, 20, 2.0f, 0))
    }
}

class SosControllerTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val location = MutableStateFlow(LocationState(0, 100_000L, 41.01234, 28.97654))
    private val contacts = MutableStateFlow(listOf(SosContact("Anne", "+905321112233"), SosContact("Ali", "+905331112233")))

    private class FakeSms(var fail: Boolean = false) : SmsSender {
        val sent = mutableListOf<Pair<List<String>, String>>()
        override suspend fun send(numbers: List<String>, text: String): SmsReport {
            sent += numbers to text
            return SmsReport(numbers.associateWith { if (fail) SmsStatus.FAILED else SmsStatus.SENT })
        }
    }

    private class FakeAlarm : SosAlarm {
        var beeps = 0
        var stopped = 0
        override fun beep() { beeps++ }
        override fun stop() { stopped++ }
    }

    private val sms = FakeSms()
    private val alarm = FakeAlarm()
    private var smsAllowed = true
    private val spoken = mutableListOf<Pair<SosReason, Int>>()

    private fun controller(tick: suspend () -> Unit = {}, now: () -> Long = { 101_000L }) = SosController(
        scope, location, contacts, sms, alarm,
        smsAllowed = { smsAllowed },
        speak = { reason, seconds -> spoken += reason to seconds },
        message = { reason, link, stale -> "${reason.name}|$link|$stale" },
        tick = tick,
        now = now,
    )

    @Test
    fun autoCountdownEndsWithOneSmsToEveryContact() {
        val c = controller()
        c.trigger(SosReason.AUTO)
        assertEquals(SosPhase.SENT, c.ui.value.phase)
        assertEquals(listOf("Anne", "Ali"), c.ui.value.sentTo)
        val (numbers, text) = sms.sent.single()
        assertEquals(listOf("+905321112233", "+905331112233"), numbers)
        assertEquals("AUTO|https://www.openstreetmap.org/?mlat=41.01234&mlon=28.97654#map=17/41.01234/28.97654|false", text)
        assertEquals(listOf(SosReason.AUTO to 30), spoken)
        assertEquals(30, alarm.beeps) // once at the start and on each of the 29 following seconds
        assertTrue(alarm.stopped >= 1)
    }

    @Test
    fun manualCountdownIsShort() {
        val c = controller()
        c.trigger(SosReason.MANUAL)
        assertEquals(listOf(SosReason.MANUAL to 5), spoken)
        assertEquals(SosPhase.SENT, c.ui.value.phase)
    }

    @Test
    fun cancelDuringTheCountdownSendsNothing() {
        var release: (() -> Unit)? = null
        val c = controller(tick = { suspendCancellableCoroutine { cont -> release = { cont.resumeWith(Result.success(Unit)) } } })
        c.trigger(SosReason.AUTO)
        assertEquals(SosPhase.COUNTDOWN, c.ui.value.phase)
        assertEquals(30, c.ui.value.secondsLeft)
        release?.invoke()
        assertEquals(29, c.ui.value.secondsLeft)
        c.cancel()
        assertEquals(SosPhase.IDLE, c.ui.value.phase)
        assertTrue(sms.sent.isEmpty())
        assertTrue(alarm.stopped >= 1)
    }

    @Test
    fun testRunNeverSendsAnything() {
        val c = controller()
        contacts.value = emptyList()
        c.trigger(SosReason.TEST)
        assertEquals(SosPhase.SENT, c.ui.value.phase)
        assertTrue(c.ui.value.sentTo.isEmpty())
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun withoutContactsOrPermissionItFailsAtOnceAndStaysSilent() {
        val c = controller()
        contacts.value = emptyList()
        c.trigger(SosReason.AUTO)
        assertEquals(SosError.NO_CONTACTS, c.ui.value.error)
        c.dismiss()
        contacts.value = listOf(SosContact("Anne", "+905321112233"))
        smsAllowed = false
        c.trigger(SosReason.AUTO)
        assertEquals(SosError.NO_PERMISSION, c.ui.value.error)
        assertTrue(sms.sent.isEmpty())
        assertEquals(0, alarm.beeps)
    }

    @Test
    fun aRadioFailureIsReportedNotHidden() {
        sms.fail = true
        val c = controller()
        c.trigger(SosReason.AUTO)
        assertEquals(SosPhase.FAILED, c.ui.value.phase)
        assertEquals(SosError.SEND_FAILED, c.ui.value.error)
    }

    @Test
    fun anOldFixIsSentAsLastKnownAndNoFixSendsNoLink() {
        val c = controller(now = { 100_000L + SosMath.FRESH_FIX_MS + 1 })
        c.trigger(SosReason.AUTO)
        assertTrue(sms.sent.single().second.endsWith("|true"))

        sms.sent.clear()
        c.dismiss()
        location.value = LocationState()
        c.trigger(SosReason.AUTO)
        assertTrue(sms.sent.single().second.startsWith("AUTO|null|"))
    }

    @Test
    fun aSecondTriggerWhileCountingDoesNothing() {
        var release: (() -> Unit)? = null
        val c = controller(tick = { suspendCancellableCoroutine { cont -> release = { cont.resumeWith(Result.success(Unit)) } } })
        c.trigger(SosReason.AUTO)
        c.trigger(SosReason.MANUAL)
        assertEquals(SosReason.AUTO, c.ui.value.reason)
        assertEquals(1, spoken.size)
        c.cancel()
    }
}

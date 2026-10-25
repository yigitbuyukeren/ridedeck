package com.ridedeck.app

import com.ridedeck.app.data.Contact
import com.ridedeck.app.data.DialogContext
import com.ridedeck.app.data.Nicknames
import com.ridedeck.app.data.ProactiveInput
import com.ridedeck.app.data.ProactiveKind
import com.ridedeck.app.data.ProactivePrompts
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.VoiceResultKind
import com.ridedeck.app.data.inverse
import com.ridedeck.app.data.isCritical
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogTest {
    private var clock = 1_000_000L
    private fun context() = DialogContext { clock }

    @Test
    fun heTheyThereMeanWhatWasJustSaid() {
        val c = context()
        c.rememberContact(Contact("Ayşe Demir", "333"))
        c.rememberPlace("Kadıköy")
        assertEquals(VoiceCommand.Call("Ayşe Demir"), c.resolve("onu ara"))
        assertTrue(c.resolve("oraya git") is VoiceCommand.NavigateTo)
        assertTrue(c.resolve("bir de oraya uğra") is VoiceCommand.AddStop)
        assertNull(c.resolve("müziği aç"))
    }

    @Test
    fun contextExpiresAfterTwoMinutes() {
        val c = context()
        c.rememberContact(Contact("Ayşe", "333"))
        clock += DialogContext.TTL_MS + 1
        assertNull(c.resolve("onu ara"))
    }

    @Test
    fun previousSearchResultsResolveSpokenOrdinalAndCorrectionUntilTimeout() {
        val c = context()
        c.rememberResults(listOf("Opet", "Shell"), VoiceResultKind.FUEL_STOP)
        assertEquals(VoiceCommand.SelectPreviousResult(1, VoiceResultKind.FUEL_STOP), c.resolve("ikincisine git"))
        assertEquals(VoiceCommand.SelectPreviousResult(0, VoiceResultKind.FUEL_STOP), c.resolve("yok ilkine dön"))
        assertNull(c.resolve("dördüncüsüne git"))
        clock += DialogContext.TTL_MS + 1
        assertNull(c.resolve("ikincisine git"))
    }

    @Test
    fun aCorrectionReplacesTheSlotOfTheLastCommand() {
        val c = context()
        c.rememberCommand(VoiceCommand.NavigateTo(listOf("ankara")))
        val fixed = c.resolve("hayır ankara değil izmir") as VoiceCommand.NavigateTo
        assertEquals("izmir", fixed.candidates.first())
        val bare = c.resolve("hayır izmir") as VoiceCommand.NavigateTo
        assertEquals("izmir", bare.candidates.first())
        c.rememberCommand(VoiceCommand.PlaySearch("tarkan"))
        assertEquals(VoiceCommand.PlaySearch("sezen aksu"), c.resolve("hayır tarkan değil sezen aksu"))
    }

    @Test
    fun repeatAndUndoAreNotRemembered() {
        val c = context()
        c.rememberCommand(VoiceCommand.VolumeUp)
        c.rememberCommand(VoiceCommand.Undo)
        c.rememberCommand(VoiceCommand.Repeat)
        assertEquals(VoiceCommand.VolumeUp, c.lastCommand)
    }

    @Test
    fun inversesAndCriticalCommands() {
        assertEquals(VoiceCommand.VolumeDown, VoiceCommand.VolumeUp.inverse())
        assertEquals(VoiceCommand.AvoidTolls(false), VoiceCommand.AvoidTolls(true).inverse())
        assertEquals(VoiceCommand.Brightness(1), VoiceCommand.Brightness(-1).inverse())
        assertNull(VoiceCommand.GoHome.inverse())
        assertTrue(VoiceCommand.Call("ali").isCritical())
        assertTrue(VoiceCommand.SendSms("ali").isCritical())
        assertTrue(VoiceCommand.Chain(VoiceCommand.VolumeUp, VoiceCommand.Emergency).isCritical())
        assertFalse(VoiceCommand.MusicPlay.isCritical())
    }

    @Test
    fun nicknameWordsMapToOneKey() {
        for (w in listOf("annem", "annemi", "anneme", "Annemle", "anne")) assertEquals(w, "annem", Nicknames.canonical(w))
        assertEquals("patron", Nicknames.canonical("patronu"))
        assertNull(Nicknames.canonical("ali"))
        assertEquals("Annen", Nicknames.asking("annem"))
    }

    @Test
    fun nicknamesSurviveTheirJson() {
        val map = mapOf("annem" to Contact("Annem", "0532"), "babam" to Contact("Baba", "0533"))
        assertEquals(map, Nicknames.fromJson(Nicknames.toJson(map)))
        assertTrue(Nicknames.fromJson("junk").isEmpty())
    }

    private fun input(now: Long, speed: Int = 40, rain: Int? = null, battery: Int = 80, nav: Boolean = false, left: Int = 0, busy: Boolean = false) =
        ProactiveInput(now, speed, rain, battery, false, nav, left, busy)

    @Test
    fun proactiveQuestionsHaveACooldownAndStaySilentWhenBusy() {
        val p = ProactivePrompts()
        assertNull(p.next(input(0, rain = 20, busy = true)))
        assertEquals(ProactiveKind.RAIN, p.next(input(1_000, rain = 20)))
        // The same thing again within half an hour, or anything else within five minutes, stays quiet.
        assertNull(p.next(input(60_000, rain = 20)))
        assertNull(p.next(input(120_000, battery = 10)))
        assertEquals(ProactiveKind.BATTERY, p.next(input(400_000, battery = 10)))
        assertEquals(ProactiveKind.RAIN, p.next(input(2_000_000, rain = 10)))
    }

    @Test
    fun arrivalAndLongRides() {
        val p = ProactivePrompts()
        assertEquals(ProactiveKind.ARRIVAL, p.next(input(0, nav = true, left = 1800)))
        val q = ProactivePrompts()
        var t = 0L
        while (t <= 121 * 60_000L) { q.onSpeed(t, 60); t += 60_000L }
        assertEquals(ProactiveKind.BREAK, q.next(input(t, speed = 60)))
    }

    @Test
    fun proactiveBatteryWarningsRequireRealReadingsAndIncludeHighTemperature() {
        val unknown = ProactivePrompts()
        assertNull(unknown.next(input(1_000, battery = 5).copy(hasBatteryReading = false, batteryTempC = 55f)))
        val hot = ProactivePrompts()
        assertEquals(ProactiveKind.TEMPERATURE, hot.next(input(2_000, battery = 80).copy(batteryTempC = 46f)))
    }

    @Test
    fun proactiveCategoriesCanBeDisabledIndividually() {
        val p = ProactivePrompts()
        assertNull(p.next(input(3_000, rain = 10).copy(enabledKinds = setOf(ProactiveKind.BATTERY))))
    }

    @Test
    fun bluetoothLossRequiresObservedConnectionAndSurvivesBusyPeriod() {
        val p = ProactivePrompts()
        assertNull(p.next(input(1_000).copy(bluetoothMicAvailable = false)))
        assertNull(p.next(input(2_000).copy(bluetoothMicAvailable = true)))
        assertNull(p.next(input(3_000, busy = true).copy(bluetoothMicAvailable = false)))
        assertEquals(ProactiveKind.BLUETOOTH, p.next(input(4_000).copy(bluetoothMicAvailable = false)))
        assertNull(p.next(input(400_000).copy(bluetoothMicAvailable = false)))
    }

    @Test
    fun navigationFailurePromptRequiresTypedTrackingErrorAndHonorsBusyState() {
        val p = ProactivePrompts()
        assertNull(p.next(input(1_000).copy(navigationTrackingError = false)))
        assertNull(p.next(input(2_000, busy = true).copy(navigationTrackingError = true)))
        assertEquals(ProactiveKind.NAVIGATION_ERROR, p.next(input(3_000).copy(navigationTrackingError = true)))
        assertNull(p.next(input(400_000).copy(navigationTrackingError = true)))
    }
}

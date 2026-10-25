package com.ridedeck.app

import com.ridedeck.app.data.AppRef
import com.ridedeck.app.data.CallKind
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.Contact
import com.ridedeck.app.data.DialOutcome
import com.ridedeck.app.data.InfoQuestion
import com.ridedeck.app.data.MessageInfo
import com.ridedeck.app.data.NavQuestion
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.RecentCall
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VoiceActions
import com.ridedeck.app.data.VoiceHeard
import com.ridedeck.app.data.VoiceAssistant
import com.ridedeck.app.data.VoiceResponseFormatter
import com.ridedeck.app.data.VoiceActionResponse
import com.ridedeck.app.data.VoiceResponseKind
import com.ridedeck.app.data.VoiceResponseMode
import com.ridedeck.app.data.VoiceFailure
import com.ridedeck.app.data.VoicePhase
import com.ridedeck.app.data.VoiceScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The assistant's dialogue (asking back, confirming, giving up) with a scripted speech engine and no Android. */
class VoiceAssistantTest {
    /** Each call to start() answers with the next scripted list of alternatives. */
    private class ScriptedEar(var script: ArrayDeque<List<String>> = ArrayDeque()) : VoiceAssistant.SpeechListener {
        var listens = 0
        var holdResults = false
        private var pendingResult: ((VoiceHeard) -> Unit)? = null
        private var partialResult: ((String) -> Unit)? = null
        private var quietPartial: ((String) -> Unit)? = null
        private var quietResult: ((VoiceHeard) -> Unit)? = null
        /** The score the recogniser reports for the next result (null: it reports none). */
        var confidence: Float? = null
        var failure: VoiceFailure? = null
        override fun available() = true
        override fun start(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) {
            listens++
            partialResult = onPartial
            val result = VoiceHeard(script.removeFirstOrNull() ?: emptyList(), confidence, failure.also { failure = null })
            if (holdResults) pendingResult = onResult else onResult(result)
        }
        fun deliver(phrase: String) { (pendingResult.also { pendingResult = null } ?: error("No pending speech result"))(VoiceHeard(listOf(phrase))) }
        fun emitPartial(phrase: String) { partialResult?.invoke(phrase) }
        override fun startQuiet(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) {
            listens++
            quietPartial = onPartial
            quietResult = onResult
        }
        fun emitQuietPartial(phrase: String) { quietPartial?.invoke(phrase) }
        fun deliverQuiet(phrase: String) {
            quietPartial = null
            quietResult.also { quietResult = null }?.invoke(VoiceHeard(listOf(phrase)))
        }
        override fun cancel() {}
    }

    private class FakeMouth : Speaker {
        val said = mutableListOf<String>()
        var responseMode: VoiceResponseMode? = null
        override fun formatResponse(text: String, kind: VoiceResponseKind): String =
            responseMode?.let { VoiceResponseFormatter.format(text, it, kind) } ?: text
        override fun speak(text: String) { said += text }
        override fun stop() {}
        override fun shutdown() {}
    }

    private class HoldingMouth : Speaker {
        val said = mutableListOf<String>()
        private var continuation: (() -> Unit)? = null
        override fun speak(text: String) { said += text }
        override fun speakThen(text: String, then: () -> Unit) { said += text; continuation = then }
        fun finishSpeaking() { continuation.also { continuation = null }?.invoke() }
        override fun stop() { continuation = null }
        override fun shutdown() { stop() }
    }

    private class FakeActions : VoiceActions {
        val log = mutableListOf<String>()
        var contacts = listOf(Contact("Ali Yılmaz", "111"), Contact("Ali Kaya", "222"), Contact("Ayşe", "333"))
        var recents = listOf(RecentCall("444", "Mehmet", CallKind.OUTGOING, 1L), RecentCall("555", null, CallKind.MISSED, 2L))
        var phase = CallPhase.IDLE
        var message: MessageInfo? = MessageInfo("Ali", "WhatsApp", "Nerdesin?", canReply = true)
        var replySucceeds = true
        var homeSaved = true
        var routing = true
        var infoGate: CompletableDeferred<String?>? = null
        var mediaSessionAvailable = true
        var mediaPlaying = true
        var canStartMedia = true
        var mediaActionSucceeds = true
        var callActionsSucceed = true
        var dialOutcome = DialOutcome.REQUESTED
        var voicePlaces = emptyList<String>()
        var voiceStops = emptyList<String>()
        var routineResponse: VoiceActionResponse? = null
        override fun supportsVoiceSearchResults() = true
        override fun supportsVoiceStopResults() = true
        override fun openScreen(screen: VoiceScreen) { log += "screen:$screen" }
        var navigateFails = false
        var suggestion: String? = null
        override fun suggestPlaceName(candidates: List<String>): String? = suggestion
        override suspend fun navigateTo(candidates: List<String>): String? {
            log += "nav:${candidates.first()}"
            return if (navigateFails) null else candidates.first().replaceFirstChar { it.uppercase() }
        }
        override suspend fun runRoutine(name: String): VoiceActionResponse? = routineResponse
        override suspend fun searchPlaces(candidates: List<String>): List<String> { log += "searchPlaces:${candidates.first()}"; return voicePlaces }
        override suspend fun navigateToSearchResult(index: Int): String? = voicePlaces.getOrNull(index)?.also { log += "route:$it" }
        override suspend fun searchFuelStopsForVoice(): List<String> { log += "searchFuelStops"; return voiceStops }
        override suspend fun selectFuelStopResult(index: Int): String? = voiceStops.getOrNull(index)?.also { log += "fuelRoute:$it" }
        override suspend fun goSaved(kind: PlaceKind): VoiceActionResponse? =
            when {
                !homeSaved -> null.also { log += "no:$kind" }
                !routing -> VoiceActionResponse("Rota oluşturulamadı.", VoiceResponseKind.ERROR)
                else -> VoiceActionResponse("Ev")
            }
        override fun stopNavigation(): Boolean { log += "stop"; return routing }
        override fun findStops() { log += "stops" }
        override suspend fun findNearest(term: String, along: Boolean): String? { log += "near:$term:$along"; return "Bir $term" }
        var alternative = true
        override suspend fun alternativeRoute(): Boolean { log += "alt"; return alternative }
        override fun navInfo(question: NavQuestion): String? = if (routing) "info:$question" else null
        override fun setAvoid(highways: Boolean?, tolls: Boolean?) { log += "avoid:$highways:$tolls" }
        override fun recalculate(): Boolean = routing
        override suspend fun saveHere(kind: PlaceKind): String? { log += "save:$kind"; return "Burası" }
        override fun canReadContacts() = true
        override fun contacts() = contacts
        override fun recents() = recents
        override fun dial(number: String, name: String?): DialOutcome { log += "dial:$number"; return dialOutcome }
        override fun callPhase() = phase
        override fun answerCall(): Boolean { log += "answer"; return callActionsSucceed }
        override fun rejectCall(): Boolean { log += "reject"; return callActionsSucceed }
        override fun muteCall(): Boolean { log += "mute"; return callActionsSucceed }
        override fun setSpeaker(on: Boolean): Boolean { log += "speaker:$on"; return callActionsSucceed }
        override fun lastMessage() = message
        override fun reply(text: String): Boolean { log += "reply:$text"; return replySucceeds }
        override fun mediaPlay(): Boolean { log += "play"; return mediaActionSucceeds }
        override fun mediaHasSession() = mediaSessionAvailable
        override fun mediaIsPlaying() = mediaPlaying
        override fun canStartMedia() = canStartMedia
        override fun mediaPause(): Boolean { log += "pause"; return mediaActionSucceeds }
        override fun mediaNext(): Boolean { log += "next"; return mediaActionSucceeds }
        override fun mediaPrevious(): Boolean { log += "prev"; return mediaActionSucceeds }
        override fun volumeUp() { log += "vol+" }
        override fun volumeDown() { log += "vol-" }
        override fun volumeSet(percent: Int) { log += "vol=$percent" }
        override fun volumeSteps(steps: Int) { log += "volSteps:$steps" }
        override fun playSearch(query: String): Boolean { log += "search:$query"; return true }
        override suspend fun info(question: InfoQuestion): String? { log += "info:$question"; return infoGate?.await() ?: "info:$question" }
        override fun apps() = listOf(AppRef("WhatsApp", "wa"), AppRef("Gmail", "gm"), AppRef("Chrome", "ch"))
        override fun launchApp(pkg: String): Boolean { log += "launch:$pkg"; return true }
        override fun setTheme(mode: ThemeMode) { log += "theme:$mode" }
        override fun setMiles(miles: Boolean) { log += "miles:$miles" }
        override fun setBrightness(delta: Int) { log += "bright:$delta" }
        override fun setWarningVoice(on: Boolean) { log += "warn:$on" }
        var smsAllowed = true
        val nicknames = mutableMapOf<String, Contact>()
        override fun canSendSms() = smsAllowed
        override fun sendSms(number: String, text: String): Boolean { log += "sms:$number:$text"; return true }
        override fun nickname(key: String): Contact? = nicknames[key]
        override fun setNickname(key: String, contact: Contact) { nicknames[key] = contact }
        override fun setReminder(minutes: Int, what: String?): Boolean { log += "remind:$minutes:$what"; return true }
        override fun setProactive(on: Boolean) { log += "proactive:$on" }
        override fun startSos() { log += "sos" }
        override fun showHelp() { log += "help" }
    }

    private lateinit var ear: ScriptedEar
    private lateinit var mouth: FakeMouth
    private lateinit var actions: FakeActions
    private lateinit var assistant: VoiceAssistant

    @Before
    fun setUp() {
        ear = ScriptedEar()
        mouth = FakeMouth()
        actions = FakeActions()
        // The strings are looked up by id; the test only needs them to be distinct, so the id itself is the text.
        assistant = VoiceAssistant(
            text = { id -> "s$id" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = ear, speaker = mouth, actions = actions,
            pause = { },
        )
    }

    private fun say(vararg phrases: List<String>) {
        ear.script = ArrayDeque(phrases.toList())
        assistant.listen()
    }

    @Test
    fun aSimpleCommandRunsAtOnce() {
        say(listOf("müziği aç"))
        assertEquals(listOf("play"), actions.log)
        say(listOf("sonraki şarkı"))
        assertTrue("next" in actions.log)
    }

    @Test fun unsupportedMediaActionIsReportedAsFailure() {
        actions.mediaActionSucceeds = false
        say(listOf("sonraki şarkı"))
        assertEquals("s${R.string.voice_media_action_failed}", mouth.said.last())
    }

    @Test fun minimalResponsesKeepInformationAndNavigationFacts() {
        mouth.responseMode = VoiceResponseMode.MINIMAL
        say(listOf("pil ne kadar kaldı"))
        assertEquals(listOf("info:BATTERY"), mouth.said)

        mouth.said.clear()
        say(listOf("ne kadar kaldı"))
        assertEquals(listOf("info:REMAINING_TIME"), mouth.said)
    }

    @Test
    fun aLaterAlternativeIsUsedWhenTheFirstIsNoise() {
        say(listOf("müziği ac mı", "müziği aç"))
        assertEquals(listOf("play"), actions.log)
    }

    @Test
    fun nothingHeardSaysSoAndDoesNothing() {
        say(emptyList())
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun processingShowsFinalRecognizedCommandUntilAsyncActionReturns() {
        actions.infoGate = CompletableDeferred()
        say(listOf("hava nasıl"))
        assertEquals(VoicePhase.PROCESSING, assistant.ui.value.phase)
        assertEquals("hava nasıl", assistant.ui.value.recognizedText)
        actions.infoGate?.complete("Şu an 18 derece.")
        assertEquals("Şu an 18 derece.", mouth.said.last())
    }

    @Test
    fun recognitionFailureShowsAnActionableMessage() {
        ear.failure = VoiceFailure.NETWORK
        say(emptyList())
        assertEquals(listOf("s${R.string.voice_error_network}"), mouth.said)
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun callingOnePersonCountsDownThenDials() {
        say(listOf("Ayşe'yi ara"))
        assertEquals(listOf("dial:333"), actions.log)
    }

    @Test
    fun callConfirmationDoesNotExposeContactOrNumberInUiOrTranscriptHistory() {
        val pauseGate = CompletableDeferred<Unit>()
        val privateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transcripts = mutableListOf<String>()
        ear.script = ArrayDeque(listOf(listOf("Ayşe'yi ara")))
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = privateScope, listener = ear, speaker = mouth, actions = actions,
            pause = { pauseGate.await() }, history = { heard, _ -> transcripts += heard },
        )

        privateAssistant.listen()

        assertEquals(VoicePhase.CONFIRM, privateAssistant.ui.value.phase)
        assertEquals("s${R.string.voice_call_confirmation_private}", privateAssistant.ui.value.title)
        assertFalse(privateAssistant.ui.value.title.contains("Ayşe"))
        assertFalse(privateAssistant.ui.value.recognizedText.contains("Ayşe"))
        assertEquals(listOf("s${R.string.voice_history_sensitive_redacted}"), transcripts)
        assertTrue(mouth.said.single().contains("Ayşe")) // The spoken confirmation still identifies the intended person.
        assertTrue(actions.log.none { it.startsWith("dial:") }) // Countdown remains cancellable; no call is made here.

        privateAssistant.cancel()
        pauseGate.cancel()
        privateScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun dictatedSmsBodyAndRecipientStayOutOfComposeStateUntilVoiceConfirmation() {
        ear.holdResults = true
        ear.script.clear()
        val holdingMouth = HoldingMouth()
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = ear, speaker = holdingMouth, actions = actions,
        )
        privateAssistant.listen()
        ear.deliver("Ayşe'ye mesaj at")
        assertEquals("s${R.string.voice_sms_dictation_private}", privateAssistant.ui.value.text)
        assertFalse(privateAssistant.ui.value.text.contains("Ayşe"))
        holdingMouth.finishSpeaking()
        ear.deliver("Giriş kodum 824193, lütfen sakla")

        val ui = privateAssistant.ui.value
        assertEquals(VoicePhase.MESSAGE, ui.phase)
        assertEquals("s${R.string.voice_sms_confirmation_private}", ui.text)
        assertFalse(ui.text.contains("Ayşe"))
        assertFalse(ui.text.contains("824193"))
        assertFalse(ui.recognizedText.contains("Ayşe"))
        assertFalse(ui.recognizedText.contains("824193"))
        assertTrue(actions.log.none { it.startsWith("sms:") })

        privateAssistant.cancel()
        ear.holdResults = false
    }

    @Test
    fun ambiguousContactChoicesHideContactNamesFromComposeState() {
        ear.holdResults = true
        ear.script.clear()
        val holdingMouth = HoldingMouth()
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = ear, speaker = holdingMouth, actions = actions,
        )
        privateAssistant.listen()
        ear.deliver("Ali'yi ara")

        assertEquals(VoicePhase.MESSAGE, privateAssistant.ui.value.phase)
        assertEquals("s${R.string.voice_contacts_multiple_private}", privateAssistant.ui.value.text)
        assertEquals(2, privateAssistant.ui.value.choices.size)
        assertTrue(privateAssistant.ui.value.choices.none { it.contains("Ali") })
        assertFalse(privateAssistant.ui.value.recognizedText.contains("Ali"))
        assertTrue(actions.log.none { it.startsWith("dial:") })

        privateAssistant.cancel()
        ear.holdResults = false
    }

    @Test
    fun partialSpeechIsNotExposedInVoiceUiBeforeIntentClassification() {
        ear.holdResults = true
        ear.script.clear()
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = ear, speaker = mouth, actions = actions,
        )
        privateAssistant.listen()
        ear.emitPartial("Ayşe'ye mesaj at şifrem 824193")

        assertEquals(VoicePhase.LISTENING, privateAssistant.ui.value.phase)
        assertEquals("", privateAssistant.ui.value.text)
        assertEquals("", privateAssistant.ui.value.recognizedText)

        privateAssistant.cancel()
        ear.holdResults = false
    }

    @Test
    fun followUpPartialTranscriptUsesTheSamePrivacyRuleAndFinalCommandStillRuns() {
        val quietEar = ScriptedEar()
        val quietMouth = HoldingMouth()
        val followAssistant = VoiceAssistant(
            text = { id -> "s$id" },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = quietEar,
            speaker = quietMouth,
            actions = actions,
            pause = { delay(100_000) },
            continuous = { true },
        )
        quietEar.script = ArrayDeque(listOf(listOf("müziği durdur")))
        followAssistant.listen()
        quietMouth.finishSpeaking()
        assertEquals(VoicePhase.LISTENING, followAssistant.ui.value.phase)

        quietEar.emitQuietPartial("Ayşe'ye mesaj at şifrem 824193")
        assertEquals("", followAssistant.ui.value.text)
        assertFalse(followAssistant.ui.value.text.contains("824193"))

        quietEar.deliverQuiet("müziği aç")
        assertTrue("play" in actions.log)
        followAssistant.cancel()
    }

    @Test
    fun partialRoutineResultCannotBecomeMinimalSuccessAcknowledgement() {
        mouth.responseMode = VoiceResponseMode.MINIMAL
        actions.routineResponse = VoiceActionResponse("Ev modu kısmen uygulandı: 2/3 adım.", VoiceResponseKind.PARTIAL)

        assistant.runRoutine("ev modu")

        assertEquals("Ev modu kısmen uygulandı: 2/3 adım.", mouth.said.last())
    }

    @Test
    fun savedPlaceRouteFailureIsNotReportedAsMissingPlaceOrSuccess() {
        actions.routing = false
        say(listOf("eve git"))

        assertEquals("Rota oluşturulamadı.", mouth.said.last())
        assertFalse(mouth.said.last().contains("rota oluşturuldu"))
    }

    @Test
    fun sensitiveUnknownOnMapIsNotEchoedOrForwardedToSearch() {
        val transcript = mutableListOf<String>()
        ear.script = ArrayDeque(listOf(listOf("sms gönder 5551234567 açıklama")))
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            listener = ear, speaker = mouth, actions = actions, history = { heard, _ -> transcript += heard },
        )
        privateAssistant.listen(searchFallback = true)

        assertTrue(actions.log.none { it.startsWith("nav:") || it.startsWith("searchPlaces:") })
        assertTrue(privateAssistant.ui.value.text.contains("s${R.string.voice_sensitive_search_blocked}"))
        assertFalse(privateAssistant.ui.value.text.contains("5551234567"))
        assertEquals(listOf("s${R.string.voice_history_sensitive_redacted}"), transcript)
    }

    @Test
    fun readMessageIsSpokenButMessageBodyAndSenderStayOutOfComposeState() {
        actions.message = MessageInfo("Ayşe", "WhatsApp", "Giriş kodum 824193", canReply = true)
        val pauseGate = CompletableDeferred<Unit>()
        val privateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ear.script = ArrayDeque(listOf(listOf("mesajı oku")))
        val privateAssistant = VoiceAssistant(
            text = { id -> "s$id" }, scope = privateScope, listener = ear, speaker = mouth, actions = actions,
            pause = { pauseGate.await() },
        )

        privateAssistant.listen()

        assertTrue(mouth.said.any { it.contains("Giriş kodum 824193") })
        assertEquals("s${R.string.voice_message_read_private}", privateAssistant.ui.value.text)
        assertFalse(privateAssistant.ui.value.text.contains("Ayşe"))
        assertFalse(privateAssistant.ui.value.text.contains("824193"))
        assertFalse(privateAssistant.ui.value.recognizedText.contains("mesajı oku"))

        privateAssistant.cancel()
        pauseGate.cancel()
        privateScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun voicePlaceSearchKeepsResultsForOrdinalFollowUp() {
        actions.voicePlaces = listOf("Benzinlik A", "Benzinlik B")
        say(listOf("Kadıköy'e git"), listOf("ikincisine git"))
        assertEquals(listOf("screen:MAP", "searchPlaces:kadıköy", "route:Benzinlik B"), actions.log)
    }

    @Test
    fun aCorrectionToTheFirstVoicePlaceIsNotTreatedAsCancellation() {
        actions.voicePlaces = listOf("Benzinlik A", "Benzinlik B")
        say(listOf("Kadıköy'e git"), listOf("yok ilkine dön"))
        assertEquals(listOf("screen:MAP", "searchPlaces:kadıköy", "route:Benzinlik A"), actions.log)
    }

    @Test
    fun nearbyFuelSearchCanBeContinuedWithAnOrdinalRouteRequest() {
        actions.voiceStops = listOf("Opet", "Shell")
        say(listOf("yakındaki benzinlikleri bul"))
        say(listOf("ikincisine git"))
        assertEquals(listOf("screen:MAP", "searchFuelStops", "fuelRoute:Shell"), actions.log)
    }

    @Test
    fun twoAlisAskWhichOneAndAcceptAnOrdinal() {
        say(listOf("Ali'yi ara"), listOf("ikinci"))
        assertEquals(listOf("dial:222"), actions.log)
    }

    @Test
    fun theChoiceCanBeNamedToo() {
        say(listOf("Ali'yi ara"), listOf("ali yılmaz"))
        assertEquals(listOf("dial:111"), actions.log)
    }

    @Test
    fun anUnclearAnswerIsAskedOnceMoreThenDropped() {
        say(listOf("Ali'yi ara"), listOf("hımm"), listOf("belki"))
        assertTrue(actions.log.none { it.startsWith("dial") })
        assertEquals(3, ear.listens)
    }

    @Test
    fun cancelAnswersStopTheCall() {
        say(listOf("Ali'yi ara"), listOf("vazgeç"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun unknownNameIsNotCalled() {
        say(listOf("Zeynep'i ara"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun lastDialledAndMissed() {
        say(listOf("son aradığımı ara"))
        assertEquals(listOf("dial:444"), actions.log)
        actions.log.clear()
        say(listOf("cevapsızı ara"))
        assertEquals(listOf("dial:555"), actions.log)
    }

    @Test
    fun replyAsksWhatToWriteThenConfirmsBeforeSending() {
        say(listOf("cevap yaz"), listOf("yoldayım geliyorum"), listOf("evet"))
        assertEquals(listOf("reply:yoldayım geliyorum"), actions.log)
    }

    @Test
    fun replyWithTextStillAsksToConfirm() {
        say(listOf("cevap yaz geliyorum"), listOf("hayır"))
        assertTrue(actions.log.isEmpty())
        say(listOf("cevap yaz geliyorum"), listOf("gönder"))
        assertEquals(listOf("reply:geliyorum"), actions.log)
    }

    @Test
    fun replyIsRefusedWhenTheAppOffersNoReplyButton() {
        actions.message = MessageInfo("Ali", "Mesajlar", "Selam", canReply = false)
        say(listOf("cevap yaz"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun cevaplaMeansTheCallWhenRingingAndTheMessageOtherwise() {
        actions.phase = CallPhase.RINGING
        say(listOf("cevapla"))
        assertEquals(listOf("answer"), actions.log)
        actions.log.clear()
        actions.phase = CallPhase.IDLE
        say(listOf("cevapla"), listOf("evet, olur"), listOf("evet"))
        // No text was given, so it asked what to write; the scripted "evet, olur" became the text.
        assertEquals(listOf("reply:evet, olur"), actions.log)
    }

    @Test
    fun failedCallControlDoesNotAnnounceSuccess() {
        actions.phase = CallPhase.RINGING
        actions.callActionsSucceed = false
        say(listOf("cevapla"))
        assertEquals("answer", actions.log.last())
        assertEquals("s${com.ridedeck.app.R.string.voice_call_action_failed}", mouth.said.last())

        say(listOf("aramayı reddet"))
        assertEquals("reject", actions.log.last())
        assertEquals("s${com.ridedeck.app.R.string.voice_call_action_failed}", mouth.said.last())

        actions.phase = CallPhase.ACTIVE
        say(listOf("aramayı sessize al"))
        assertEquals("mute", actions.log.last())
        assertEquals("s${com.ridedeck.app.R.string.voice_call_action_failed}", mouth.said.last())

        say(listOf("hoparlör aç"))
        assertEquals("speaker:true", actions.log.last())
        assertEquals("s${com.ridedeck.app.R.string.voice_call_action_failed}", mouth.said.last())
    }

    @Test
    fun failedDialDoesNotPresentCallAsStarted() {
        actions.dialOutcome = DialOutcome.FAILED
        say(listOf("Ayşe'yi ara"), listOf("evet"))
        assertTrue(actions.log.contains("dial:333"))
        assertEquals("s${com.ridedeck.app.R.string.voice_call_action_failed}", mouth.said.last())
    }

    @Test
    fun sosAsksFirst() {
        say(listOf("yardım çağır"), listOf("hayır"))
        assertFalse("sos" in actions.log)
        say(listOf("yardım çağır"), listOf("evet"))
        assertTrue("sos" in actions.log)
    }

    @Test
    fun homeAndRoutes() {
        say(listOf("eve git"))
        assertEquals(listOf("screen:MAP"), actions.log)
        actions.log.clear()
        actions.homeSaved = false
        say(listOf("eve git"))
        assertFalse("screen:MAP" in actions.log)
    }

    @Test
    fun navigatingAndNearestPlace() {
        say(listOf("Kadıköy'e git"))
        assertEquals(listOf("screen:MAP", "searchPlaces:kadıköy", "nav:kadıköy"), actions.log)
        actions.log.clear()
        say(listOf("en yakın kafe"))
        assertEquals(listOf("screen:MAP", "searchPlaces:kafe", "near:kafe:false"), actions.log)
    }

    @Test
    fun anUnknownPlaceOffersASuggestionAndWaitsForAYes() {
        actions.navigateFails = true
        actions.suggestion = "Taksim"
        say(listOf("taksm'e git"), listOf("hayır"))
        assertTrue(actions.log.none { it == "nav:Taksim" })
        actions.log.clear()
        say(listOf("taksm'e git"), listOf("evet"))
        assertTrue("nav:Taksim" in actions.log)
    }

    @Test
    fun anUnknownPlaceWithoutASuggestionJustSaysNothingFound() {
        actions.navigateFails = true
        actions.suggestion = null
        say(listOf("qwzxv'e git"), listOf("evet"))
        // nothing to confirm: the "evet" starts no second search
        assertEquals(listOf("screen:MAP", "searchPlaces:qwzxv", "nav:qwzxv"), actions.log)
    }

    @Test
    fun appsAreOpenedByLooseName() {
        say(listOf("whatsapp'ı aç"))
        assertEquals(listOf("launch:wa"), actions.log)
        actions.log.clear()
        say(listOf("kıl aç"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun anUnknownPhraseOnTheMapSearchBoxIsSearched() {
        ear.script = ArrayDeque(listOf(listOf("moda sahil yolu")))
        assistant.listen(searchFallback = true)
        assertEquals(listOf("screen:MAP", "nav:moda sahil yolu"), actions.log)
    }

    @Test
    fun musicAndVolume() {
        say(listOf("Tarkan çal"))
        say(listOf("sesi yüzde elli yap"))
        say(listOf("sesi iki kademe kıs"))
        say(listOf("müziği durdur"))
        assertEquals(listOf("search:tarkan", "vol=50", "volSteps:-2", "pause"), actions.log)
    }

    @Test
    fun relativeVolumeActionsGiveUserFeedback() {
        say(listOf("sesi aç"))
        say(listOf("müziğin sesini kıs"))
        assertEquals(listOf("s${R.string.voice_volume_up}", "s${R.string.voice_volume_down}"), mouth.said)
    }

    @Test
    fun unsupportedMediaControlsDoNotReportSuccess() {
        actions.mediaSessionAvailable = false
        actions.canStartMedia = false
        say(listOf("sonraki şarkı"))
        assertTrue(actions.log.isEmpty())
        assertEquals("s${R.string.voice_no_media}", mouth.said.last())

        say(listOf("müziği aç"))
        assertTrue(actions.log.isEmpty())
        assertEquals("s${R.string.voice_cannot_play}", mouth.said.last())
    }

    @Test
    fun steppedVolumeCanBeUndone() {
        say(listOf("sesi iki kademe kıs"))
        say(listOf("geri al"))
        assertEquals(listOf("volSteps:-2", "volSteps:2"), actions.log)
    }

    @Test
    fun aStopOnTheWayIsLookedForAlongTheRoute() {
        say(listOf("yolda kafe"))
        assertEquals(listOf("screen:MAP", "near:kafe:true"), actions.log)
    }

    @Test
    fun anotherRouteAndTheQuickOnes() {
        say(listOf("başka rota"))
        say(listOf("ekranı kıs"))
        say(listOf("parlaklığı artır"))
        say(listOf("sesli uyarıları kapat"))
        say(listOf("yolculuk özeti"))
        assertEquals(listOf("alt", "bright:-1", "bright:1", "warn:false", "info:TRIP"), actions.log)
        actions.log.clear()
        actions.alternative = false
        say(listOf("alternatif rota"))
        assertEquals(listOf("alt"), actions.log)
    }

    @Test
    fun aLowScoreAsksBeforeRunning() {
        ear.confidence = 0.2f
        say(listOf("müziği aç"), listOf("evet"))
        assertEquals(listOf("play"), actions.log)
        actions.log.clear()
        say(listOf("müziği aç"), listOf("hayır"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun noScoreOrAGoodScoreRunsAtOnce() {
        ear.confidence = 0f
        say(listOf("müziği aç"))
        ear.confidence = 0.9f
        say(listOf("müziği durdur"))
        assertEquals(listOf("play", "pause"), actions.log)
    }

    @Test
    fun everyCommandIsWrittenToTheHistory() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        assistant = VoiceAssistant(text = { id -> "s$id" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), listener = ear, speaker = mouth, actions = actions, pause = { }, history = { h, ok -> seen += h to ok })
        say(listOf("müziği aç"))
        say(listOf("bugün hava çok güzel dostum"))
        assertEquals(listOf("müziği aç" to true, "bugün hava çok güzel dostum" to false), seen)
    }

    @Test
    fun settingsAndScreens() {
        say(listOf("gece modu"))
        say(listOf("mil kullan"))
        say(listOf("müzik ekranını aç"))
        say(listOf("ne diyebilirim"))
        assertEquals(listOf("theme:NIGHT", "miles:true", "screen:MUSIC", "help"), actions.log)
    }

    @Test
    fun twoCommandsRunInSpokenOrderAndCallKeepsItsConfirmationFlow() {
        say(listOf("Ayşe'yi ara ve müziği kıs"))
        assertEquals(listOf("dial:333", "vol-"), actions.log)
    }

    @Test
    fun threeCommandsRunInSpokenOrder() {
        say(listOf("sesi biraz kıs ve ekranı kıs sonra gece modunu aç"))
        assertEquals(listOf("vol-", "bright:-1", "theme:NIGHT"), actions.log)
    }

    @Test
    fun twoCallsInOneSentenceAreRefused() {
        say(listOf("Ayşe'yi ara ve Mehmet'i ara"))
        assertTrue(actions.log.none { it.startsWith("dial") })
    }

    @Test
    fun aNewMessageIsDictatedReadBackAndOnlySentAfterAYes() {
        say(listOf("Ayşe'ye mesaj at"), listOf("yoldayım"), listOf("hayır"))
        assertTrue(actions.log.none { it.startsWith("sms") })
        say(listOf("Ayşe'ye mesaj at"), listOf("yoldayım"), listOf("evet"))
        assertEquals(listOf("sms:333:yoldayım"), actions.log)
    }

    @Test
    fun aMessageNeedsTheSmsPermission() {
        actions.smsAllowed = false
        say(listOf("Ayşe'ye mesaj at"))
        assertTrue(actions.log.isEmpty())
    }

    @Test
    fun onuAraMeansTheLastPerson() {
        say(listOf("Ayşe'yi ara"))
        say(listOf("onu ara"))
        assertEquals(listOf("dial:333", "dial:333"), actions.log)
    }

    @Test
    fun aSpokenCorrectionChangesThePlace() {
        say(listOf("Kadıköy'e git"))
        say(listOf("hayır Kadıköy değil Moda"))
        assertEquals(listOf("screen:MAP", "searchPlaces:kadıköy", "nav:kadıköy", "screen:MAP", "searchPlaces:moda", "nav:moda"), actions.log)
    }

    @Test
    fun undoTakesBackTheLastCommand() {
        say(listOf("sesi kıs"))
        say(listOf("geri al"))
        assertEquals(listOf("vol-", "vol+"), actions.log)
    }

    @Test
    fun theFirstTimeANicknameIsAskedForThenRemembered() {
        say(listOf("annemi ara"), listOf("ayşe"))
        assertEquals(listOf("dial:333"), actions.log)
        assertEquals("Ayşe", actions.nicknames["annem"]?.name)
        say(listOf("annemi ara"))
        assertEquals(listOf("dial:333", "dial:333"), actions.log)
    }

    @Test
    fun aReminderIsSet() {
        say(listOf("yirmi dakika sonra hatırlat"))
        assertEquals(listOf("remind:20:null"), actions.log)
    }

    @Test
    fun theAssistantsOwnQuestionRunsOnYesAndIsSilentOtherwise() {
        ear.script = ArrayDeque(listOf(listOf("evet")))
        assertTrue(assistant.proactive("Yağmur geliyor, okuyayım mı?", com.ridedeck.app.data.VoiceCommand.Info(InfoQuestion.WEATHER)))
        assertEquals(listOf("info:WEATHER"), actions.log)
        ear.script = ArrayDeque(listOf(listOf("hayır")))
        assertTrue(assistant.proactive("Pil düşük, ekranı kısayım mı?", com.ridedeck.app.data.VoiceCommand.Brightness(-1)))
        assertEquals(listOf("info:WEATHER"), actions.log)
        assertEquals(com.ridedeck.app.data.VoicePhase.IDLE, assistant.ui.value.phase)
    }

    @Test
    fun aHalfHeardSentenceAsksWhatWasMeant() {
        say(listOf("hava çok güzel"), listOf("evet"))
        assertEquals(listOf("info:WEATHER"), actions.log)
    }
}

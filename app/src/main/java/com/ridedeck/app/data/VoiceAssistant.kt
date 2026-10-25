package com.ridedeck.app.data

import com.ridedeck.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class VoicePhase { IDLE, LISTENING, PROCESSING, CONFIRM, MESSAGE }

data class VoiceUi(
    val phase: VoicePhase = VoicePhase.IDLE,
    /** What the recogniser heard so far, or the message to show. */
    val text: String = "",
    /** The action waiting for its countdown (a call), shown as the card title. */
    val title: String = "",
    val secondsLeft: Int = 0,
    /** While the assistant asks "which one?": the options, so they can also be tapped. */
    val choices: List<String> = emptyList(),
    /** The final transcription, retained while the assistant asks a question or performs the action. */
    val recognizedText: String = "",
    val isError: Boolean = false,
)

/** One immutable snapshot consumed by Compose so dialog content and audio state cannot render out of sync. */
data class VoicePresentationState(
    val assistant: VoiceUi = VoiceUi(),
    val runtime: VoiceRuntimeState = VoiceRuntimeState.IDLE,
)

/** The latest chat message as the assistant needs it. */
data class MessageInfo(val sender: String, val app: String, val text: String, val canReply: Boolean)

data class AppRef(val label: String, val pkg: String)

/** Everything the assistant can do to the app; the Android side implements it, tests use a fake. */
interface VoiceActions {
    fun openScreen(screen: VoiceScreen)
    suspend fun navigateTo(candidates: List<String>): String?
    /** Optional full result set for a voice destination; old actions keep their first-match behavior. */
    fun supportsVoiceSearchResults(): Boolean = false
    suspend fun searchPlaces(candidates: List<String>): List<String> = emptyList()
    suspend fun navigateToSearchResult(index: Int): String? = null
    fun supportsVoiceStopResults(): Boolean = false
    suspend fun searchFuelStopsForVoice(): List<String> = emptyList()
    suspend fun selectFuelStopResult(index: Int): String? = null
    fun goSaved(kind: PlaceKind): String?
    fun stopNavigation(): Boolean
    fun findStops()
    suspend fun findNearest(term: String, along: Boolean): String?
    suspend fun alternativeRoute(): Boolean
    fun navInfo(question: NavQuestion): String?
    fun setAvoid(highways: Boolean?, tolls: Boolean?)
    fun recalculate(): Boolean
    suspend fun saveHere(kind: PlaceKind): String?

    fun canReadContacts(): Boolean
    fun contacts(): List<Contact>
    fun recents(): List<RecentCall>
    fun dial(number: String, name: String?): DialOutcome
    fun callPhase(): CallPhase
    fun answerCall(): Boolean
    fun rejectCall(): Boolean
    fun muteCall(): Boolean
    fun setSpeaker(on: Boolean): Boolean

    fun lastMessage(): MessageInfo?
    fun reply(text: String): Boolean

    fun mediaPlay(): Boolean
    fun canStartMedia(): Boolean = true
    fun mediaHasSession(): Boolean = true
    fun mediaIsPlaying(): Boolean = true
    fun mediaPause(): Boolean
    fun mediaNext(): Boolean
    fun mediaPrevious(): Boolean
    fun volumeUp()
    fun volumeDown()
    fun volumeSet(percent: Int)
    fun volumeSteps(steps: Int) {
        repeat(kotlin.math.abs(steps).coerceAtMost(5)) { if (steps > 0) volumeUp() else volumeDown() }
    }
    fun playSearch(query: String): Boolean

    suspend fun info(question: InfoQuestion): String?
    fun apps(): List<AppRef>
    fun launchApp(pkg: String): Boolean
    fun setTheme(mode: ThemeMode)
    fun setBrightness(delta: Int)
    fun setWarningVoice(on: Boolean)
    fun setMiles(miles: Boolean)
    fun startSos()
    fun showHelp()

    // Added later; the defaults keep older fakes working.
    /** A stop on the way to the current destination (home/work by [saved], otherwise searched); the stop's name, or null. */
    suspend fun addStop(candidates: List<String>, saved: PlaceKind?): String? = null
    fun canSendSms(): Boolean = false
    fun sendSms(number: String, text: String): Boolean = false
    /** The contact the rider assigned to a nickname key ("annem"), if any. */
    fun nickname(key: String): Contact? = null
    fun setNickname(key: String, contact: Contact) {}
    fun setReminder(minutes: Int, what: String?): Boolean = false
    fun setProactive(on: Boolean) {}
    fun matchRoutine(phrase: String): String? = null
    suspend fun runRoutine(name: String): String? = null
    fun restoreRoutineState(): Boolean = false
}

/** What the recogniser heard: the alternatives best first, and how sure it was (0..1) when it says. */
data class VoiceHeard(val alternatives: List<String>, val confidence: Float? = null, val failure: VoiceFailure? = null)
enum class VoiceFailure { NO_SPEECH, TIMEOUT, PERMISSION, NETWORK, BUSY, LANGUAGE, SERVICE, ENGINE }

/** What the assistant waits for after it asked something. */
private sealed class Expect {
    object None : Expect()
    class YesNo(val prompt: String, val displayPrompt: String = prompt, val onYes: () -> Unit) : Expect()
    class Choice(
        val prompt: String,
        val options: List<Pair<String, () -> Unit>>,
        val displayPrompt: String = prompt,
        val displayChoices: List<String> = options.map { it.first },
    ) : Expect()
    class Dictation(val prompt: String, val displayPrompt: String = prompt, val onText: (String) -> Unit) : Expect()
}

/**
 * Hands-free control: listens once, parses the Turkish phrase ([VoiceParser]) and runs it through [VoiceActions].
 * It can ask back ("Hangi Ali?", "Gönderilsin mi?") and listens for the answer. A call is never placed at once:
 * it counts down so a wrong guess can be cancelled with a tap; a message is only sent after a spoken "evet".
 */
class VoiceAssistant(
    private val text: (Int) -> String,
    private val scope: CoroutineScope,
    private val listener: SpeechListener,
    private val speaker: Speaker,
    private val actions: VoiceActions,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** Called once per command with what was heard and whether it was understood (for the command history). */
    private val history: (heard: String, understood: Boolean) -> Unit = { _, _ -> },
    /** After an answer, listen once more for a few seconds without beeping (setting; off by default here). */
    private val continuous: () -> Boolean = { false },
    private val context: DialogContext = DialogContext(),
) {
    /** The speech engine, injectable for tests. */
    interface SpeechListener {
        fun available(): Boolean
        /** Listens once; [onResult] gets what was heard (no alternatives when nothing was understood). */
        fun start(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit)
        /** Open transcription only for a user-requested message body, never for command interpretation. */
        fun startDictation(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = start(onPartial, onResult)
        /** The same without the start and end beeps and the buzz (the follow-up listen after an answer). */
        fun startQuiet(onPartial: (String) -> Unit, onResult: (VoiceHeard) -> Unit) = start(onPartial, onResult)
        fun cancel()
    }

    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui
    private val voiceStates = AudioSessionManager.process.stateMachine
    private var job: Job? = null
    private var expect: Expect = Expect.None
    private var attempts = 0
    private var allowFollowUp = true
    private var followUpTurn = false
    private var followUpWatchdog: Job? = null
    /** The current question was the assistant's own idea: an unanswered one is dropped without a word. */
    private var proactiveAsk = false

    init {
        scope.launch {
            _ui.collect { ui ->
                val owner = AudioSessionManager.process.currentOwner()
                val next = when (ui.phase) {
                    VoicePhase.IDLE -> VoiceRuntimeState.IDLE.takeIf { owner == null }
                    VoicePhase.LISTENING -> if (owner == AudioSessionManager.Owner.FOLLOW_UP_STT) VoiceRuntimeState.FOLLOW_UP else VoiceRuntimeState.COMMAND_LISTENING
                    VoicePhase.PROCESSING -> VoiceRuntimeState.PROCESSING
                    VoicePhase.CONFIRM -> VoiceRuntimeState.CONFIRMING
                    VoicePhase.MESSAGE -> when {
                        owner == AudioSessionManager.Owner.TTS -> VoiceRuntimeState.SPEAKING
                        expect !is Expect.None -> VoiceRuntimeState.CONFIRMING
                        _ui.value.isError -> VoiceRuntimeState.ERROR
                        else -> VoiceRuntimeState.PROCESSING
                    }
                }
                if (next != null) voiceStates.transition(next)
            }
        }
    }

    /** [searchFallback]: on the map's search box, an unrecognised phrase is simply searched for. */
    fun listen(searchFallback: Boolean = false) {
        job?.cancel()
        followUpWatchdog?.cancel()
        followUpTurn = false
        proactiveAsk = false
        allowFollowUp = true
        expect = Expect.None
        attempts = 0
        voiceStates.transition(VoiceRuntimeState.ACTIVATING)
        startListening(searchFallback)
    }

    /**
     * The assistant's own question ("Yağmur geliyor, hava durumunu okuyayım mı?"): asked and listened for only when it is
     * idle; "evet" runs [then]. A silent or negative answer is dropped without a word. False when it is busy.
     */
    fun proactive(question: String, then: VoiceCommand): Boolean {
        if (_ui.value.phase != VoicePhase.IDLE || expect !is Expect.None || !listener.available()) return false
        allowFollowUp = false
        proactiveAsk = true
        ask(Expect.YesNo(question) { proactiveAsk = false; run(then) })
        return true
    }

    private fun startListening(searchFallback: Boolean) {
        if (!listener.available()) {
            say(text(R.string.voice_unavailable))
            return
        }
        _ui.update { VoiceUi(VoicePhase.LISTENING, choices = it.choices) }
        // Partial speech can contain contact details or a dictated message. Do not expose it
        // before the complete utterance has been classified.
        val onPartial: (String) -> Unit = { }
        val onResult: (VoiceHeard) -> Unit = { heard -> handle(heard.alternatives, heard.confidence, searchFallback, heard.failure) }
        if (expect is Expect.Dictation) listener.startDictation(onPartial, onResult) else listener.start(
            onPartial = onPartial,
            onResult = onResult,
        )
    }

    fun cancel() {
        job?.cancel()
        followUpWatchdog?.cancel()
        followUpTurn = false
        proactiveAsk = false
        listener.cancel()
        speaker.stop()
        expect = Expect.None
        _ui.value = VoiceUi()
        voiceStates.transition(VoiceRuntimeState.IDLE)
    }

    fun runRoutine(name: String) { run(VoiceCommand.RunRoutine(name)) }

    /** A tap on one of the shown choices. */
    fun choose(index: Int) {
        val e = expect as? Expect.Choice ?: return
        e.options.getOrNull(index)?.let { (_, action) -> listener.cancel(); expect = Expect.None; action() }
    }

    private fun handle(alternatives: List<String>, confidence: Float?, searchFallback: Boolean, failure: VoiceFailure? = null) {
        voiceStates.transition(VoiceRuntimeState.PROCESSING)
        val e = expect
        if (e !is Expect.None) {
            handleAnswer(e, alternatives)
            return
        }
        if (alternatives.isEmpty()) {
            history("", false)
            say(text(failure?.let(::failureMessage) ?: R.string.voice_not_heard), speak = true)
            return
        }
        val recognized = alternatives.first().trim()
        _ui.value = VoiceUi(VoicePhase.PROCESSING, text = text(R.string.voice_processing))
        // What was just talked about ("onu ara", "oraya git", a correction) first; then the recogniser's alternatives, best first.
        val routine = alternatives.firstNotNullOfOrNull(actions::matchRoutine)
        val command = routine?.let { VoiceCommand.RunRoutine(it) } ?: alternatives.firstNotNullOfOrNull { context.resolve(it) }
            ?: alternatives.map(VoiceParser::parse).firstOrNull { it !is VoiceCommand.Unknown }
        val sensitive = command?.containsSensitiveTranscript() == true || VoiceHistory.safeTranscript(recognized) != recognized
        val safeDisplay = if (sensitive) text(R.string.voice_history_sensitive_redacted) else recognized
        _ui.update { it.copy(text = safeDisplay, recognizedText = safeDisplay) }
        val transcript = if (sensitive) text(R.string.voice_history_sensitive_redacted) else VoiceHistory.safeTranscript(recognized)
        history(transcript, command != null)
        if (command == null) {
            if (searchFallback && !sensitive) {
                actions.openScreen(VoiceScreen.MAP)
                scope.launch { actions.navigateTo(listOf(alternatives.first())) }
                _ui.value = VoiceUi()
                return
            }
            if (searchFallback && sensitive) {
                say(text(R.string.voice_sensitive_search_blocked))
                return
            }
            // Nothing fits, but one harmless command nearly does: ask instead of "anlamadım".
            val guess = alternatives.firstNotNullOfOrNull { VoiceLoose.suggestion(it) }
            if (guess != null) {
                ask(Expect.YesNo(text(guess.askRes)) { run(guess.command) })
            } else {
                say(text(R.string.voice_unknown) + " " + safeDisplay)
            }
            return
        }
        // A low score (0 means "no score") makes a wrong guess likely: say what was heard and ask.
        if (confidence != null && confidence > 0f && confidence < LOW_CONFIDENCE && command !is VoiceCommand.Help) {
            val spokenPrompt = text(R.string.voice_did_you_say) + " " + alternatives.first() + "?"
            val displayPrompt = if (sensitive) text(R.string.voice_sensitive_confirmation_private) else spokenPrompt
            ask(Expect.YesNo(spokenPrompt, displayPrompt) { run(command) })
            return
        }
        run(command)
    }

    private fun handleAnswer(e: Expect, alternatives: List<String>) {
        if (alternatives.isEmpty()) {
            giveUp(); return
        }
        val answers = alternatives.map(VoiceParser::parseAnswer)
        when (e) {
            is Expect.YesNo -> when {
                answers.any { it is VoiceAnswer.Yes } -> { expect = Expect.None; e.onYes() }
                answers.any { it is VoiceAnswer.No || it is VoiceAnswer.Cancel } -> {
                    expect = Expect.None
                    if (proactiveAsk) { proactiveAsk = false; _ui.value = VoiceUi() } else say(text(R.string.voice_cancelled))
                }
                else -> askAgain(e)
            }
            is Expect.Choice -> {
                val ordinal = answers.firstNotNullOfOrNull { (it as? VoiceAnswer.Ordinal)?.index }
                val byName = alternatives.firstNotNullOfOrNull { heard ->
                    Turkish.bestMatches(heard, e.options.indices.toList(), threshold = 0.8) { e.options[it].first }.singleOrNull()
                }
                val picked = ordinal?.takeIf { it < e.options.size } ?: byName
                when {
                    answers.any { it is VoiceAnswer.Cancel || it is VoiceAnswer.No } -> { expect = Expect.None; say(text(R.string.voice_cancelled)) }
                    picked != null -> { expect = Expect.None; e.options[picked].second() }
                    else -> askAgain(e)
                }
            }
            is Expect.Dictation -> {
                if (answers.any { it is VoiceAnswer.Cancel }) {
                    expect = Expect.None; say(text(R.string.voice_cancelled))
                } else {
                    expect = Expect.None
                    e.onText(alternatives.first().trim())
                }
            }
            Expect.None -> Unit
        }
    }

    /** One more try with the question repeated; after that the assistant gives up rather than guess. */
    private fun askAgain(e: Expect) {
        attempts++
        if (attempts > 1) { giveUp(); return }
        when (e) {
            is Expect.YesNo -> askListen(e.prompt, displayPrompt = e.displayPrompt)
            is Expect.Choice -> askListen(e.prompt, e.options.map { it.first }, e.displayPrompt, e.displayChoices)
            is Expect.Dictation -> askListen(e.prompt, displayPrompt = e.displayPrompt)
            Expect.None -> Unit
        }
    }

    private fun giveUp() {
        expect = Expect.None
        if (proactiveAsk) { proactiveAsk = false; _ui.value = VoiceUi(); return }
        say(text(R.string.voice_gave_up))
    }

    /** Says [prompt], shows it, then listens for the answer. */
    private fun askListen(
        prompt: String,
        choices: List<String> = emptyList(),
        displayPrompt: String = prompt,
        displayChoices: List<String> = choices,
    ) {
        showMessage(displayPrompt, displayChoices)
        speaker.speakThen(prompt) { startListening(false) }
    }

    private fun showMessage(message: String, choices: List<String> = emptyList()) {
        _ui.value = VoiceUi(VoicePhase.MESSAGE, message, choices = choices, recognizedText = _ui.value.recognizedText)
    }

    private fun ask(e: Expect) {
        voiceStates.transition(VoiceRuntimeState.CONFIRMING)
        expect = e
        attempts = 0
        when (e) {
            is Expect.YesNo -> askListen(e.prompt, displayPrompt = e.displayPrompt)
            is Expect.Choice -> askListen(e.prompt, e.options.map { it.first }, e.displayPrompt, e.displayChoices)
            is Expect.Dictation -> askListen(e.prompt, displayPrompt = e.displayPrompt)
            Expect.None -> Unit
        }
    }

    private fun run(command: VoiceCommand) {
        voiceStates.transition(VoiceRuntimeState.PROCESSING)
        if (command !is VoiceCommand.Chain) context.rememberCommand(command)
        when (command) {
            is VoiceCommand.NavigateTo -> {
                actions.openScreen(VoiceScreen.MAP)
                showMessage(text(R.string.voice_searching) + " " + command.candidates.first())
                job = scope.launch {
                    val matches = if (actions.supportsVoiceSearchResults()) actions.searchPlaces(command.candidates) else emptyList()
                    if (matches.isNotEmpty()) context.rememberResults(matches, VoiceResultKind.PLACE)
                    when {
                            matches.size > 1 -> {
                            ask(Expect.Choice(text(R.string.voice_choose_place), matches.take(4).mapIndexed { index, name ->
                                name to {
                                    job = scope.launch {
                                        actions.navigateToSearchResult(index)?.let { place ->
                                            context.rememberPlace(place)
                                            done(text(R.string.voice_route_ready) + " " + place)
                                        } ?: say(text(R.string.route_failed))
                                    }
                                }
                            }))
                        }
                        matches.size == 1 -> {
                            val place = actions.navigateToSearchResult(0) ?: if (actions.supportsVoiceSearchResults()) null else actions.navigateTo(command.candidates)
                            if (place != null) { context.rememberPlace(place); done(text(R.string.voice_route_ready) + " " + place) }
                            else say(text(R.string.no_results))
                        }
                        else -> {
                            val place = actions.navigateTo(command.candidates)
                            if (place != null) { context.rememberPlace(place); done(text(R.string.voice_route_ready) + " " + place) } else say(text(R.string.no_results))
                        }
                    }
                }
            }
            VoiceCommand.GoHome -> goSaved(PlaceKind.HOME, R.string.voice_no_home)
            VoiceCommand.GoWork -> goSaved(PlaceKind.WORK, R.string.voice_no_work)
            VoiceCommand.GoParked -> goSaved(PlaceKind.PARKED, R.string.voice_no_parked)
            VoiceCommand.StopNavigation -> if (actions.stopNavigation()) done(text(R.string.voice_nav_stopped)) else say(text(R.string.voice_no_route))
            VoiceCommand.FindStops -> {
                actions.openScreen(VoiceScreen.MAP)
                if (!actions.supportsVoiceStopResults()) {
                    actions.findStops()
                    done(text(R.string.voice_looking_stops))
                } else job = scope.launch {
                    val results = actions.searchFuelStopsForVoice()
                    if (results.isEmpty()) say(text(R.string.no_results))
                    else {
                        context.rememberResults(results, VoiceResultKind.FUEL_STOP)
                            done(text(R.string.voice_found_stops))
                    }
                }
            }
            is VoiceCommand.FindNearest -> {
                actions.openScreen(VoiceScreen.MAP)
                showMessage(text(R.string.voice_searching) + " " + command.label)
                job = scope.launch {
                    val matches = if (command.along || !actions.supportsVoiceSearchResults()) emptyList() else actions.searchPlaces(listOf(command.term))
                    if (matches.isNotEmpty()) context.rememberResults(matches, VoiceResultKind.PLACE)
                    when {
                        matches.size > 1 -> ask(Expect.Choice(text(R.string.voice_choose_place), matches.take(4).mapIndexed { index, name ->
                            name to {
                                job = scope.launch {
                                    actions.navigateToSearchResult(index)?.let { place -> context.rememberPlace(place); done(command.label + ": " + place) }
                                        ?: say(text(R.string.route_failed))
                                }
                            }
                        }))
                        matches.size == 1 -> {
                            val place = actions.navigateToSearchResult(0) ?: if (actions.supportsVoiceSearchResults()) null else actions.findNearest(command.term, command.along)
                            if (place != null) { context.rememberPlace(place); done(command.label + ": " + place) }
                            else say(text(R.string.no_results))
                        }
                        else -> {
                            val place = actions.findNearest(command.term, command.along)
                            if (place != null) { context.rememberPlace(place); done(command.label + ": " + place) } else say(text(R.string.no_results))
                        }
                    }
                }
            }
            is VoiceCommand.NavInfo -> actions.navInfo(command.question)?.let { done(it, VoiceResponseKind.INFORMATION) } ?: say(text(R.string.voice_no_route))
            is VoiceCommand.AvoidHighways -> { actions.setAvoid(command.on, null); done(text(if (command.on) R.string.voice_avoid_highways_on else R.string.voice_avoid_highways_off)) }
            is VoiceCommand.AvoidTolls -> { actions.setAvoid(null, command.on); done(text(if (command.on) R.string.voice_avoid_tolls_on else R.string.voice_avoid_tolls_off)) }
            VoiceCommand.Recalculate -> if (actions.recalculate()) done(text(R.string.voice_recalculated)) else say(text(R.string.voice_no_route))
            VoiceCommand.AlternativeRoute -> {
                showMessage(text(R.string.voice_looking_alternative))
                job = scope.launch { if (actions.alternativeRoute()) done(text(R.string.voice_alternative_ready)) else say(text(R.string.voice_no_alternative)) }
            }
            VoiceCommand.SaveFavoriteHere -> saveHere(PlaceKind.FAVORITE)
            VoiceCommand.SetHomeHere -> saveHere(PlaceKind.HOME)
            VoiceCommand.SetWorkHere -> saveHere(PlaceKind.WORK)

            is VoiceCommand.Call -> callPerson(command.name)
            is VoiceCommand.CallDigits -> confirmCall(Contact(command.number, command.number))
            VoiceCommand.CallLast -> callFromRecents { it.kind == CallKind.OUTGOING }
            VoiceCommand.CallMissed -> callFromRecents { it.kind == CallKind.MISSED }
            VoiceCommand.ShowRecents -> { actions.openScreen(VoiceScreen.PHONE); _ui.value = VoiceUi() }
            VoiceCommand.AnswerCall -> when {
                actions.callPhase() == CallPhase.RINGING -> if (actions.answerCall()) done(text(R.string.voice_call_answered)) else say(text(R.string.voice_call_action_failed), speak = true)
                // Nothing is ringing: "cevapla" most likely means the chat message.
                actions.lastMessage()?.canReply == true -> run(VoiceCommand.ReplyMessage(null))
                else -> { actions.openScreen(VoiceScreen.PHONE); _ui.value = VoiceUi() }
            }
            VoiceCommand.RejectCall -> if (actions.callPhase() != CallPhase.IDLE) { if (actions.rejectCall()) done(text(R.string.voice_call_rejected)) else say(text(R.string.voice_call_action_failed), speak = true) } else say(text(R.string.voice_no_call))
            VoiceCommand.MuteCall -> if (actions.callPhase() == CallPhase.ACTIVE) { if (actions.muteCall()) done(text(R.string.voice_call_mute_toggled)) else say(text(R.string.voice_call_action_failed), speak = true) } else say(text(R.string.voice_no_call))
            is VoiceCommand.SpeakerCall -> if (actions.callPhase() == CallPhase.ACTIVE) { if (actions.setSpeaker(command.on)) done(text(if (command.on) R.string.voice_call_speaker_on else R.string.voice_call_speaker_off)) else say(text(R.string.voice_call_action_failed), speak = true) } else say(text(R.string.voice_no_call))

            VoiceCommand.ReadMessage -> actions.lastMessage()?.let {
                speakPrivateAndShow(it.sender + ": " + it.text, text(R.string.voice_message_read_private))
            } ?: say(text(R.string.voice_no_message))
            VoiceCommand.WhoWrote -> actions.lastMessage()?.let {
                speakPrivateAndShow(it.sender + ", " + it.app, text(R.string.voice_sender_read_private))
            } ?: say(text(R.string.voice_no_message))
            is VoiceCommand.ReplyMessage -> reply(command.text)

            VoiceCommand.MusicPlay -> when {
                !actions.canStartMedia() -> say(text(R.string.voice_cannot_play), speak = true)
                !actions.mediaPlay() -> say(text(R.string.voice_media_action_failed), speak = true)
                else -> done(text(if (actions.mediaHasSession()) R.string.voice_music_play else R.string.voice_music_starting))
            }
            VoiceCommand.MusicPause -> when {
                !actions.mediaHasSession() -> say(text(R.string.voice_no_media), speak = true)
                !actions.mediaIsPlaying() -> done(text(R.string.voice_music_already_paused))
                else -> if (actions.mediaPause()) done(text(R.string.voice_music_pause)) else say(text(R.string.voice_media_action_failed), speak = true)
            }
            VoiceCommand.MusicNext -> if (!actions.mediaHasSession()) say(text(R.string.voice_no_media), speak = true)
                else if (actions.mediaNext()) done(text(R.string.voice_music_next)) else say(text(R.string.voice_media_action_failed), speak = true)
            VoiceCommand.MusicPrevious -> if (!actions.mediaHasSession()) say(text(R.string.voice_no_media), speak = true)
                else if (actions.mediaPrevious()) done(text(R.string.voice_music_previous)) else say(text(R.string.voice_media_action_failed), speak = true)
            is VoiceCommand.PlaySearch -> if (actions.playSearch(command.query)) done(text(R.string.voice_playing) + " " + command.query) else say(text(R.string.voice_cannot_play), speak = true)
            VoiceCommand.VolumeUp -> { actions.volumeUp(); done(text(R.string.voice_volume_up)) }
            VoiceCommand.VolumeDown -> { actions.volumeDown(); done(text(R.string.voice_volume_down)) }
            is VoiceCommand.VolumeChange -> { actions.volumeSteps(command.steps); done(text(if (command.steps > 0) R.string.voice_volume_up else R.string.voice_volume_down)) }
            is VoiceCommand.VolumeSet -> { actions.volumeSet(command.percent); done(text(R.string.voice_volume) + " %" + command.percent) }

            is VoiceCommand.Info -> {
                job = scope.launch { actions.info(command.question)?.let { speakAndShow(it) } ?: say(text(R.string.voice_no_info)) }
            }
            is VoiceCommand.OpenApp -> openApp(command.name)
            is VoiceCommand.OpenScreen -> { actions.openScreen(command.screen); _ui.value = VoiceUi() }
            is VoiceCommand.SetTheme -> { actions.setTheme(command.mode); done(text(when (command.mode) { ThemeMode.AUTO -> R.string.voice_theme_auto; ThemeMode.DAY -> R.string.voice_theme_day; ThemeMode.NIGHT -> R.string.voice_theme_night })) }
            is VoiceCommand.Brightness -> { actions.setBrightness(command.delta); done(text(if (command.delta > 0) R.string.voice_brightness_up else R.string.voice_brightness_down)) }
            is VoiceCommand.WarningVoice -> { actions.setWarningVoice(command.on); done(text(if (command.on) R.string.voice_warnings_on else R.string.voice_warnings_off)) }
            is VoiceCommand.RunRoutine -> {
                job = scope.launch { actions.runRoutine(command.name)?.let(::done) ?: say(text(R.string.voice_routine_failed)) }
            }
            VoiceCommand.RestoreRoutineState -> if (actions.restoreRoutineState()) done(text(R.string.voice_routine_restored)) else say(text(R.string.voice_routine_nothing_to_restore))
            is VoiceCommand.SetMiles -> { actions.setMiles(command.miles); done(text(if (command.miles) R.string.voice_miles else R.string.voice_km)) }

            is VoiceCommand.SendSms -> sendSms(command.name)
            is VoiceCommand.AddStop -> addStop(command)
            is VoiceCommand.Reminder -> {
                if (actions.setReminder(command.minutes, command.what)) done(text(R.string.voice_reminder_set) + " " + spokenMinutes(command.minutes))
                else say(text(R.string.voice_reminder_failed))
            }
            is VoiceCommand.SelectPreviousResult -> {
                job = scope.launch {
                    val selected = when (command.kind) {
                        VoiceResultKind.PLACE -> actions.navigateToSearchResult(command.index)
                        VoiceResultKind.FUEL_STOP -> actions.selectFuelStopResult(command.index)
                    }
                    if (selected != null) { context.rememberPlace(selected); done(text(R.string.voice_route_ready) + " " + selected) }
                    else say(text(R.string.no_results))
                }
            }
            is VoiceCommand.Chain -> runChain(command)
            VoiceCommand.Repeat -> context.lastSpoken?.let { done(it, VoiceResponseKind.INFORMATION) } ?: say(text(R.string.voice_nothing_to_repeat))
            VoiceCommand.Undo -> context.lastCommand?.inverse()?.let { run(it) } ?: say(text(R.string.voice_nothing_to_undo))
            VoiceCommand.StopProactive -> { actions.setProactive(false); done(text(R.string.voice_proactive_off)) }
            VoiceCommand.Help -> { actions.showHelp(); _ui.value = VoiceUi() }
            VoiceCommand.Emergency -> ask(Expect.YesNo(text(R.string.voice_sos_ask)) { actions.startSos(); _ui.value = VoiceUi() })
            is VoiceCommand.Unknown -> say(text(R.string.voice_unknown) + " " + command.heard)
        }
    }

    private fun goSaved(kind: PlaceKind, missing: Int) {
        val name = actions.goSaved(kind)
        if (name == null) { say(text(missing)); return }
        actions.openScreen(VoiceScreen.MAP)
        context.rememberPlace(name)
        done(text(R.string.voice_route_ready) + " " + name)
    }

    private fun saveHere(kind: PlaceKind) {
        job = scope.launch {
            val name = actions.saveHere(kind)
            if (name != null) done(text(R.string.voice_saved) + " " + name) else say(text(R.string.voice_no_fix))
        }
    }

    private fun callPerson(name: String) = withContact(name) { confirmCall(it) }

    /**
     * Finds the person for a heard name and hands them to [then]. A nickname ("annemi") uses the contact the rider
     * assigned to it; the first time, when no contact is literally called that, the rider is asked who it is.
     */
    private fun withContact(name: String, then: (Contact) -> Unit) {
        if (!actions.canReadContacts()) { say(text(R.string.voice_no_contacts_permission)); return }
        val key = Nicknames.canonical(name)
        if (key != null) {
            actions.nickname(key)?.let { then(it); return }
            if (VoiceParser.matchContact(name, actions.contacts()).isEmpty()) {
                ask(Expect.Dictation(Nicknames.asking(key) + " " + text(R.string.voice_nick_who), text(R.string.voice_nickname_who_private)) { heard ->
                    pickContact(heard) { c -> actions.setNickname(key, c); then(c) }
                })
                return
            }
        }
        pickContact(name, then)
    }

    private fun pickContact(name: String, then: (Contact) -> Unit) {
        val found = VoiceParser.matchContact(name, actions.contacts())
        when {
            found.isEmpty() -> say(text(R.string.voice_contact_not_found))
            found.size == 1 -> then(found.first())
            else -> {
                val top = found.take(MAX_CHOICES)
                val prompt = text(R.string.voice_which) + " " + top.joinToString(", ") { it.name }
                val displayChoices = top.indices.map { index -> "${index + 1}. " + text(R.string.voice_contact_choice_person) }
                ask(Expect.Choice(prompt, top.map { c -> c.name to { then(c) } }, text(R.string.voice_contacts_multiple_private), displayChoices))
            }
        }
    }

    /** "Ali'ye mesaj at": who, what to write, read it back, send only after a spoken yes. */
    private fun sendSms(name: String) {
        if (!actions.canSendSms()) { say(text(R.string.voice_no_sms_permission)); return }
        withContact(name) { c ->
            context.rememberContact(c)
            ask(Expect.Dictation(text(R.string.voice_what_to_write) + " " + c.name, text(R.string.voice_sms_dictation_private)) { body ->
                ask(Expect.YesNo("${c.name}: “$body”. " + text(R.string.voice_send_ask), text(R.string.voice_sms_confirmation_private)) {
                    if (actions.sendSms(c.number, body)) done(text(R.string.voice_sent)) else say(text(R.string.voice_send_failed))
                })
            })
        }
    }

    private fun addStop(command: VoiceCommand.AddStop) {
        showMessage(text(R.string.voice_adding_stop))
        job = scope.launch {
            val stop = actions.addStop(command.candidates, command.saved)
            if (stop != null) { context.rememberPlace(stop); done(text(R.string.voice_stop_added) + " " + stop) } else say(text(R.string.voice_stop_failed))
        }
    }

    /** Runs up to four commands in spoken order; multiple sensitive actions are refused. */
    private fun runChain(chain: VoiceCommand.Chain) {
        val commands = flatten(chain)
        if (commands.size > MAX_CHAIN_COMMANDS || commands.count(VoiceCommand::isCritical) > 1) {
            say(text(R.string.voice_chain_one_critical))
            return
        }
        commands.lastOrNull()?.let(context::rememberCommand)
        fun execute(index: Int) {
            if (index >= commands.size) return
            job = null
            run(commands[index])
            val actionJob = job
            if (actionJob?.isActive == true) {
                job = scope.launch { actionJob.join(); execute(index + 1) }
            } else execute(index + 1)
        }
        execute(0)
    }

    private fun flatten(command: VoiceCommand): List<VoiceCommand> = when (command) {
        is VoiceCommand.Chain -> flatten(command.first) + flatten(command.second)
        else -> listOf(command)
    }

    private fun spokenMinutes(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60} " + text(R.string.voice_hour_word) else "$minutes " + text(R.string.voice_minute_word)

    private fun callFromRecents(match: (RecentCall) -> Boolean) {
        val recent = actions.recents().firstOrNull(match)
        if (recent == null) { say(text(R.string.voice_no_recent)); return }
        confirmCall(Contact(recent.name ?: recent.number, recent.number))
    }

    private fun confirmCall(contact: Contact) {
        context.rememberContact(contact)
        val spokenTitle = text(R.string.voice_calling) + " " + contact.name
        _ui.value = VoiceUi(VoicePhase.CONFIRM, title = text(R.string.voice_call_confirmation_private), secondsLeft = CONFIRM_SECONDS)
        speaker.speak(spokenTitle)
        job = scope.launch {
            for (left in CONFIRM_SECONDS - 1 downTo 1) {
                pause(1000)
                _ui.update { it.copy(secondsLeft = left) }
            }
            pause(1000)
            _ui.value = VoiceUi()
            when (actions.dial(contact.number, contact.name)) {
                DialOutcome.REQUESTED -> say(text(R.string.voice_call_request_sent), errorState = false)
                DialOutcome.DIALER_OPENED -> say(text(R.string.voice_dialer_opened), errorState = false)
                DialOutcome.FAILED -> say(text(R.string.voice_call_action_failed), speak = true)
            }
        }
    }

    private fun reply(typed: String?) {
        val m = actions.lastMessage()
        when {
            m == null -> say(text(R.string.voice_no_message))
            !m.canReply -> say(text(R.string.voice_cannot_reply))
            typed != null -> confirmSend(m, typed)
            else -> ask(Expect.Dictation(text(R.string.voice_what_to_write) + " " + m.sender, text(R.string.voice_sms_dictation_private)) { body -> confirmSend(m, body) })
        }
    }

    private fun confirmSend(m: MessageInfo, body: String) {
        ask(Expect.YesNo("${m.sender}: “$body”. " + text(R.string.voice_send_ask), text(R.string.voice_sms_confirmation_private)) {
            if (actions.reply(body)) done(text(R.string.voice_sent)) else say(text(R.string.voice_send_failed))
        })
    }

    private fun openApp(name: String) {
        val matches = Turkish.bestMatches(name, actions.apps(), threshold = 0.75) { it.label }
        when {
            matches.isEmpty() -> say(text(R.string.voice_app_not_found) + " " + name)
            matches.size == 1 -> launch(matches.first())
            else -> {
                val top = matches.take(MAX_CHOICES)
                ask(Expect.Choice(text(R.string.voice_which) + " " + top.joinToString(", ") { it.label }, top.map { a -> a.label to { launch(a) } }))
            }
        }
    }

    private fun launch(app: AppRef) {
        if (actions.launchApp(app.pkg)) { context.rememberApp(app.label); _ui.value = VoiceUi() } else say(text(R.string.voice_app_not_found) + " " + app.label)
    }

    /** A short result: spoken and shown, then it clears itself. With the follow-up setting on, the rider may add one more command. */
    private fun done(message: String, kind: VoiceResponseKind = VoiceResponseKind.ACTION) {
        val response = speaker.formatResponse(message, kind)
        context.rememberSpoken(response)
        if (allowFollowUp && continuous()) {
            allowFollowUp = false
            speaker.speakThen(response) { followUp() }
        } else {
            speaker.speak(response)
        }
            say(response, speak = false, errorState = false)
    }

    /** Listens once more for a few seconds without beeping; silence just closes the card. */
    private fun followUp() {
        if (expect !is Expect.None || _ui.value.phase == VoicePhase.CONFIRM || !listener.available()) return
        followUpTurn = true
        voiceStates.transition(VoiceRuntimeState.FOLLOW_UP)
        _ui.value = VoiceUi(VoicePhase.LISTENING)
        listener.startQuiet(
            onPartial = { partial -> _ui.update { if (it.phase == VoicePhase.LISTENING) it.copy(text = partial) else it } },
            onResult = { heard ->
                if (followUpTurn) {
                    followUpTurn = false
                    followUpWatchdog?.cancel()
                    if (heard.alternatives.isEmpty()) {
                        if (heard.failure != null && heard.failure != VoiceFailure.NO_SPEECH) say(text(failureMessage(heard.failure)))
                        else _ui.value = VoiceUi()
                    } else handle(heard.alternatives, heard.confidence, false, heard.failure)
                }
            },
        )
        followUpWatchdog = scope.launch {
            pause(FOLLOW_UP_MS)
            if (followUpTurn) { followUpTurn = false; listener.cancel(); _ui.value = VoiceUi() }
        }
    }

    private fun speakAndShow(message: String) = done(message, VoiceResponseKind.INFORMATION)

    /** Reads private message data aloud without putting it in Compose state or dialog memory. */
    private fun speakPrivateAndShow(spoken: String, display: String) {
        speaker.speak(spoken)
        say(display, errorState = false)
    }

    private fun failureMessage(failure: VoiceFailure): Int = when (failure) {
        VoiceFailure.NO_SPEECH -> R.string.voice_not_heard
        VoiceFailure.TIMEOUT -> R.string.voice_error_timeout
        VoiceFailure.PERMISSION -> R.string.voice_error_permission
        VoiceFailure.NETWORK -> R.string.voice_error_network
        VoiceFailure.BUSY -> R.string.voice_error_busy
        VoiceFailure.LANGUAGE -> R.string.voice_error_language
        VoiceFailure.SERVICE -> R.string.voice_error_service
        VoiceFailure.ENGINE -> R.string.voice_error_engine
    }

    private fun say(message: String, speak: Boolean = false, errorState: Boolean = true) {
        val recognized = _ui.value.recognizedText
        if (errorState) voiceStates.transition(VoiceRuntimeState.ERROR)
        else if (AudioSessionManager.process.currentOwner() == null) voiceStates.transition(VoiceRuntimeState.IDLE)
        if (speak) speaker.speak(message)
        job?.cancel()
        _ui.value = VoiceUi(VoicePhase.MESSAGE, message, recognizedText = recognized, isError = errorState)
        job = scope.launch {
            pause(MESSAGE_MS)
            _ui.update { if (it.phase == VoicePhase.MESSAGE) VoiceUi() else it }
            if (AudioSessionManager.process.currentOwner() == null) voiceStates.transition(VoiceRuntimeState.IDLE)
        }
    }

    private companion object {
        const val MAX_CHAIN_COMMANDS = 4
        const val CONFIRM_SECONDS = 3
        const val MESSAGE_MS = 3500L
        const val FOLLOW_UP_MS = 5000L
        const val MAX_CHOICES = 3
        /** Below this recogniser score a command is confirmed before it runs. */
        const val LOW_CONFIDENCE = 0.35f
    }
}

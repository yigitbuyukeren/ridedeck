package com.ridedeck.app

import android.Manifest
import com.ridedeck.app.data.AndroidSmsSender
import com.ridedeck.app.data.AndroidSosAlarm
import com.ridedeck.app.data.CrashSensor
import com.ridedeck.app.data.GeoPoint
import kotlinx.coroutines.Job
import com.ridedeck.app.data.outlook
import com.ridedeck.app.data.WeatherRepository
import com.ridedeck.app.data.VoiceCommand
import com.ridedeck.app.data.ProactivePrompts
import com.ridedeck.app.data.ProactiveKind
import com.ridedeck.app.data.ProactiveInput
import com.ridedeck.app.data.InfoQuestion
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.DialOutcome
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.SosController
import com.ridedeck.app.data.SosMath
import com.ridedeck.app.data.SosReason
import com.ridedeck.app.util.hasPermission
import com.ridedeck.app.data.ThermalGuard
import com.ridedeck.app.data.WeatherAlert
import com.ridedeck.app.data.WeatherController
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import com.ridedeck.app.data.DashCard
import com.ridedeck.app.data.DashLayout
import com.ridedeck.app.data.PinnedApps
import com.ridedeck.app.data.VoiceAssistant
import com.ridedeck.app.data.VoicePresentationState
import com.ridedeck.app.data.AudioSessionManager
import com.ridedeck.app.data.VoiceRuntimeState
import com.ridedeck.app.data.AndroidVoiceActions
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.OfflineVoice
import com.ridedeck.app.data.OfflineVoiceCheck
import com.ridedeck.app.data.VoiceButtonTrigger
import com.ridedeck.app.data.SherpaWakeWord
import com.ridedeck.app.data.VoicePhase
import com.ridedeck.app.data.VoiceHistory
import com.ridedeck.app.data.VoiceHistoryEntry
import com.ridedeck.app.data.VoiceResponseMode
import com.ridedeck.app.data.VoiceResponseFormatter
import com.ridedeck.app.data.RideRoutine
import com.ridedeck.app.data.RideRoutines
import com.ridedeck.app.data.VoiceInput
import com.ridedeck.app.data.VoiceScreen
import com.ridedeck.app.data.SavedPlaces
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ridedeck.app.data.SpeedLimitController
import com.ridedeck.app.data.TripStore
import com.ridedeck.app.data.TripRecorder
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridedeck.app.data.BatteryRepository
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallRepository
import com.ridedeck.app.data.LocationRepository
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.MediaRepository
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.NavigationTermination
import com.ridedeck.app.data.PhoneMath
import com.ridedeck.app.data.PhoneRepository
import com.ridedeck.app.data.ThemeController
import com.ridedeck.app.data.Voice
import com.ridedeck.app.service.CallBridge
import com.ridedeck.app.service.OverlayService
import com.ridedeck.app.service.IncomingMessage
import com.ridedeck.app.service.MessageBridge
import com.ridedeck.app.data.RouteRepository
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.Units
import com.ridedeck.app.util.simplifyMessage
import kotlinx.coroutines.flow.Flow
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.RideContextProvider
import com.ridedeck.app.data.BluetoothMicRouter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GpsUi(
    val speedKmh: Int = 0,
    val hasFix: Boolean = false,
    val accuracyM: Int? = null,
    /** For the hidden GPS debug line: age of the last fix, gap since the one before, and the chip's speed error. */
    val fixAgeMs: Int? = null,
    val fixIntervalMs: Int? = null,
    val speedAccuracyMps: Float? = null,
    /** The speed every readout shows: the filtered speed rounded to km/h, null while unknown (same source as the dial). `speedKmh` stays the raw fix. */
    val displayKmh: Int? = null,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val location = LocationRepository(app)

    /** The speed the gauge draws, in km/h, smoothed and projected forward between fixes; null while the speed is unknown. */
    fun displaySpeedKmh(): Double? = location.displayKmh()
    val battery = BatteryRepository(app)
    val media = MediaRepository(app)
    val call = CallRepository(app, viewModelScope)
    val theme = ThemeController(app)

    val messages: StateFlow<IncomingMessage?> = MessageBridge.latest
    val readMessagesAloud = MutableStateFlow(Prefs.readMessagesAloud(app))
    val voiceTtsNames = MutableStateFlow<List<String>>(emptyList())
    val selectedTtsVoice = MutableStateFlow(Prefs.voiceTtsName(app))
    private val announcer = Voice(app) {
        voiceTtsNames.value = it
        selectedTtsVoice.value = Prefs.voiceTtsName(app)
    }

    fun setTurkishTtsVoice(name: String?) {
        if (announcer.selectTurkishVoice(name)) {
            Prefs.setVoiceTtsName(getApplication(), name)
            selectedTtsVoice.value = name
        }
    }

    val callerName: StateFlow<String?> = CallBridge.callerName
    val phone = PhoneRepository(app, viewModelScope)

    /** Dials from RideDeck: the call screen comes up at once, with the name when we have one. */
    fun dial(number: String, name: String?): DialOutcome {
        if (PhoneMath.dialable(number).isEmpty()) return DialOutcome.FAILED
        val result = phone.dial(number)
        if (result == DialOutcome.REQUESTED) {
            CallBridge.callerName.value = name ?: number
            call.markDialing()
        }
        return result
    }

    init {
        // A name left over from one call must not label the next one.
        viewModelScope.launch { call.state.collect { if (it.phase == CallPhase.IDLE) CallBridge.callerName.value = null } }
    }

    val locationState: StateFlow<LocationState> = location.state

    /** Null until the first-launch chooser has been answered. */
    val vehicleChosen = MutableStateFlow(Prefs.vehicle(app))
    private val profileNow: VehicleProfile get() = vehicleChosen.value ?: VehicleProfile.MOTORCYCLE
    val profile: StateFlow<VehicleProfile> = vehicleChosen.map { it ?: VehicleProfile.MOTORCYCLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, profileNow)

    val nav = NavigationController(
        app::getString, viewModelScope, location.state, announcer,
        repo = RouteRepository(miles = { Units.miles }),
        initialOptions = Prefs.routeOptions(app, profileNow),
        onOptionsChanged = { Prefs.setRouteOptions(app, it) },
    )

    /**
     * First-launch choice or a change in Settings. Settings the user set themselves stay as they are;
     * only what they never touched (the speed-limit display) follows the new vehicle.
     */
    fun setVehicle(profile: VehicleProfile) {
        val app = getApplication<Application>()
        Prefs.setVehicle(app, profile)
        vehicleChosen.value = profile
        nav.setProfile(profile)
        if (!Prefs.speedLimitChosen(app)) speedLimitEnabled.value = profile.config.speedLimitDefault
    }

    /** Chat apps seen in notifications, each with whether the rider silenced it. */
    data class MessageAppRow(val pkg: String, val label: String, val muted: Boolean)

    val messageApps = MutableStateFlow(readMessageApps())

    private fun readMessageApps(): List<MessageAppRow> {
        val pm = getApplication<Application>().packageManager
        val muted = PinnedApps.fromJson(Prefs.messageAppsMuted(getApplication<Application>()))
        return PinnedApps.fromJson(Prefs.messageAppsSeen(getApplication<Application>())).map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            MessageAppRow(pkg, label, pkg in muted)
        }.sortedBy { it.label.lowercase() }
    }

    fun refreshMessageApps() { messageApps.value = readMessageApps() }

    fun setMessageAppMuted(pkg: String, muted: Boolean) {
        val now = PinnedApps.fromJson(Prefs.messageAppsMuted(getApplication<Application>()))
        Prefs.setMessageAppsMuted(getApplication<Application>(), PinnedApps.toJson(if (muted) PinnedApps.add(now, pkg) else PinnedApps.remove(now, pkg)))
        refreshMessageApps()
    }

    /** A voice command asked for this screen; the dashboard opens it and clears the flag. */
    val voiceScreen = MutableStateFlow<VoiceScreen?>(null)
    val voiceHelp = MutableStateFlow(false)

    /** Counts requests that start listening from outside the screen (the helmet's voice button, "assist"). */
    val voiceRequests = MutableStateFlow(0)

    fun requestVoice() { voiceRequests.value += 1 }

    /** Names the recogniser should expect: contacts, saved places and pinned apps. */
    private fun voiceNames(): List<String> {
        val pm = getApplication<Application>().packageManager
        val appNames = pinnedApps.value.mapNotNull { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        return phone.contacts.value.map { it.name } + places.value.map { it.place.name } + appNames
    }

    private val voiceInput = VoiceInput(app) { voiceNames() }

    val voiceResponseMode = MutableStateFlow(runCatching { VoiceResponseMode.valueOf(Prefs.voiceResponseMode(app)) }.getOrDefault(VoiceResponseMode.NORMAL))
    val voiceSpeechRate = MutableStateFlow(Prefs.voiceSpeechRate(app))

    fun setVoiceResponseMode(mode: VoiceResponseMode) {
        voiceResponseMode.value = mode
        Prefs.setVoiceResponseMode(getApplication(), mode.name)
    }

    fun setVoiceSpeechRate(rate: Float) {
        val bounded = rate.coerceIn(0.75f, 1.25f)
        voiceSpeechRate.value = bounded
        Prefs.setVoiceSpeechRate(getApplication(), bounded)
        announcer.setSpeechRate(bounded)
    }

    fun setVoiceRepliesEnabled(enabled: Boolean) {
        Prefs.setVoiceReplies(getApplication(), enabled)
        if (!enabled) announcer.stop()
    }

    /** Spoken answers can be switched off; the text still shows on screen. */
    private val voiceSpeaker = object : Speaker {
        override fun formatResponse(text: String, kind: com.ridedeck.app.data.VoiceResponseKind): String =
            VoiceResponseFormatter.format(text, voiceResponseMode.value, kind)
        override fun speak(text: String) { if (Prefs.voiceReplies(getApplication())) announcer.speakQueued(text) }
        override fun speakQueued(text: String) = speak(text)
        override fun speakThen(text: String, then: () -> Unit) {
            if (Prefs.voiceReplies(getApplication())) announcer.speakThen(text, then) else then()
        }
        override fun stop() = announcer.stop()
        override fun shutdown() {}
    }

    val offlineVoice = MutableStateFlow(OfflineVoice.UNKNOWN)

    fun refreshOfflineVoice() = OfflineVoiceCheck.check(getApplication()) { offlineVoice.value = it }

    fun downloadOfflineVoice() {
        OfflineVoiceCheck.download(getApplication())
        viewModelScope.launch { delay(6000); refreshOfflineVoice() }
    }

    val voiceButtonOn = MutableStateFlow(Prefs.voiceButton(app))
    val voiceWakeOn = MutableStateFlow(Prefs.voiceWake(app))
    private val voiceButton = VoiceButtonTrigger(app, onDoublePress = { requestVoice() }, onSinglePress = { media.togglePlay() })

    fun setVoiceButton(on: Boolean) {
        Prefs.setVoiceButton(getApplication(), on)
        voiceButtonOn.value = on
        applyVoiceExtras()
    }

    private val wakeWord = SherpaWakeWord(
        app,
        canRun = { wakeEligible() && voiceAssistant.ui.value.phase == VoicePhase.IDLE },
        onWake = { requestVoice() },
    )

    fun setVoiceWake(on: Boolean) {
        Prefs.setVoiceWake(getApplication(), on)
        voiceWakeOn.value = on
        applyVoiceExtras()
    }

    /** Wake capture is only active while the foreground dashboard is idle and no call is active. */
    private fun wakeEligible(): Boolean = onScreen && call.state.value.phase == CallPhase.IDLE &&
        voiceWakeOn.value && getApplication<Application>().hasPermission(Manifest.permission.RECORD_AUDIO) &&
        (AudioSessionManager.process.currentOwner() == null || AudioSessionManager.process.currentOwner() == AudioSessionManager.Owner.SHERPA_WAKE)

    private fun applyVoiceExtras() {
        val calm = onScreen && call.state.value.phase == CallPhase.IDLE
        if (!calm) AudioSessionManager.process.stateMachine.transition(VoiceRuntimeState.SUSPENDED)
        else if (AudioSessionManager.process.stateMachine.state.value == VoiceRuntimeState.SUSPENDED) {
            AudioSessionManager.process.stateMachine.transition(VoiceRuntimeState.IDLE)
        }
        if (calm && voiceAssistant.ui.value.phase == VoicePhase.IDLE && voiceWakeOn.value) wakeWord.start()
        else wakeWord.stop()
        if (onScreen && voiceButtonOn.value) voiceButton.start() else voiceButton.stop()
    }

    val voiceHistory = MutableStateFlow(VoiceHistory.fromPreference(Prefs.voiceHistory(app), Prefs.voiceHistoryOn(app)))

    init {
        // Android SpeechRecognizer is not a supported continuous hotword engine. Clear the old opt-in
        // so an upgrade never resumes the repeated microphone restart loop.
        Prefs.migrateLegacyVoiceWake(app)
        if (!Prefs.voiceHistoryOn(app)) {
            Prefs.setVoiceHistory(app, VoiceHistory.toJson(emptyList()))
            Prefs.setVoiceMisses(app, VoiceHistory.missesToJson(emptyList()))
        } else {
            // Migrate older local history in place so sensitive phrases are not left in preferences.
            val historyJson = VoiceHistory.toJson(voiceHistory.value)
            if (Prefs.voiceHistory(app) != historyJson) Prefs.setVoiceHistory(app, historyJson)
            val missesJson = VoiceHistory.missesToJson(VoiceHistory.missesFromJson(Prefs.voiceMisses(app)))
            if (Prefs.voiceMisses(app) != missesJson) Prefs.setVoiceMisses(app, missesJson)
        }
    }

    fun clearVoiceHistory() {
        voiceHistory.value = emptyList()
        Prefs.setVoiceHistory(getApplication(), VoiceHistory.toJson(emptyList()))
        Prefs.setVoiceMisses(getApplication(), VoiceHistory.missesToJson(emptyList()))
    }

    private fun recordVoice(heard: String, understood: Boolean) {
        if (heard.isBlank()) return
        if (!Prefs.voiceHistoryOn(getApplication())) return
        val safeHeard = VoiceHistory.safeTranscript(heard)
        if (!understood) {
            Prefs.setVoiceMisses(getApplication(), VoiceHistory.missesToJson(VoiceHistory.addMiss(VoiceHistory.missesFromJson(Prefs.voiceMisses(getApplication())), safeHeard)))
        }
        val next = VoiceHistory.add(voiceHistory.value, VoiceHistoryEntry(safeHeard, understood, System.currentTimeMillis()))
        voiceHistory.value = next
        Prefs.setVoiceHistory(getApplication(), VoiceHistory.toJson(next))
    }

    /** The sentences the assistant did not understand, one per line, for the share sheet (text only, never audio). */
    fun missedPhrases(): String = VoiceHistory.missesFromJson(Prefs.voiceMisses(getApplication())).joinToString("\n")

    val voiceProactiveOn = MutableStateFlow(Prefs.voiceProactive(app))
    val voiceProactiveKinds = MutableStateFlow(ProactiveKind.entries.associateWith { Prefs.voiceProactiveKind(app, it) })

    fun setVoiceProactiveKind(kind: ProactiveKind, enabled: Boolean) {
        Prefs.setVoiceProactiveKind(getApplication(), kind, enabled)
        voiceProactiveKinds.value = voiceProactiveKinds.value + (kind to enabled)
    }

    val voiceRoutines = MutableStateFlow(RideRoutines.fromJson(Prefs.voiceRoutines(app)))

    fun saveVoiceRoutine(routine: RideRoutine, replacingName: String? = null): Boolean {
        if (!RideRoutines.canEditAtSpeed(gps.value.speedKmh, gps.value.hasFix)) return false
        val updated = RideRoutines.upsert(voiceRoutines.value, routine, replacingName) ?: return false
        Prefs.setVoiceRoutines(getApplication(), RideRoutines.toJson(updated))
        voiceRoutines.value = updated
        return true
    }

    fun deleteVoiceRoutine(name: String) {
        if (!RideRoutines.canEditAtSpeed(gps.value.speedKmh, gps.value.hasFix)) return
        val updated = RideRoutines.delete(voiceRoutines.value, name)
        Prefs.setVoiceRoutines(getApplication(), RideRoutines.toJson(updated))
        voiceRoutines.value = updated
    }

    fun setVoiceProactive(on: Boolean) {
        voiceProactiveOn.value = on
        Prefs.setVoiceProactive(getApplication(), on)
    }

    /** Forget the people assigned to "annem", "babam" and the like. */
    fun clearNicknames() {
        Prefs.setNicknames(getApplication(), "{}")
    }

    /** Screen brightness the rider asked for by voice (0..1); null leaves the system setting. Heat protection still wins. */
    val brightnessChoice = MutableStateFlow<Float?>(null)

    private fun stepBrightness(delta: Int) {
        val base = brightnessChoice.value ?: DEFAULT_VOICE_BRIGHTNESS
        brightnessChoice.value = (base + delta * BRIGHTNESS_STEP).coerceIn(MIN_VOICE_BRIGHTNESS, 1f)
    }

    val voiceAssistant = VoiceAssistant(
        text = app::getString,
        scope = viewModelScope,
        listener = voiceInput,
        speaker = voiceSpeaker,
        actions = AndroidVoiceActions(
            context = app,
            nav = nav,
            routing = RouteRepository(),
            places = { places.value },
            savePlace = ::savePlace,
            location = location.state,
            phone = phone,
            call = call,
            dialNumber = ::dial,
            media = media,
            weather = { weather.snapshot.value },
            battery = { battery.state.value },
            speedLimit = { speedLimit.limit.value },
            setThemeMode = theme::setMode,
            startSosCountdown = { sos.trigger(SosReason.MANUAL) },
            showScreen = { voiceScreen.value = it },
            showHelpScreen = { voiceHelp.value = true },
            lastTrip = { trips.trips().firstOrNull() },
            changeBrightness = ::stepBrightness,
            warningVoice = ::setRainVoice,
            displaySpeedKmh = { location.displayKmh()?.roundToInt() },
            outlook = { day, from, to ->
                val fix = location.state.value
                if (fix.fixAtMs == 0L) null else WeatherRepository().outlook(GeoPoint(fix.lat, fix.lon), day, from, to).getOrNull()
            },
            proactiveSwitch = ::setVoiceProactive,
            rideContext = {
                RideContextProvider.capture(
                    nowMs = System.currentTimeMillis(),
                    nav = nav.ui.value,
                    location = location.state.value,
                    battery = battery.state.value,
                    bluetoothMicAvailable = BluetoothMicRouter.isBluetoothInputAvailable(app),
                    media = media.state.value,
                    weather = weather.snapshot.value,
                    vehicleProfile = nav.options.value.profile,
                )
            },
            routines = { voiceRoutines.value },
            saveRoutineSnapshot = { snapshot -> Prefs.setRoutineSnapshot(app, snapshot?.let(RideRoutines::snapshotToJson)) },
            routineSnapshot = { RideRoutines.snapshotFromJson(Prefs.routineSnapshot(app)) },
        ),
        history = { heard, understood -> recordVoice(heard, understood) },
        continuous = { Prefs.voiceContinuous(getApplication()) },
    )

    /** A single Compose snapshot for transcript/dialog content and the state-machine/audio owner. */
    val voicePresentation: StateFlow<VoicePresentationState> = combine(
        voiceAssistant.ui,
        AudioSessionManager.process.stateMachine.state,
    ) { assistant, runtime -> VoicePresentationState(assistant, runtime) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            VoicePresentationState(voiceAssistant.ui.value, AudioSessionManager.process.stateMachine.state.value),
        )

    // ---- the assistant's own questions (rain, low battery, nearly there, a long ride) --------------

    private val proactivePrompts = ProactivePrompts()
    private var proactiveJob: Job? = null

    private fun startProactive() {
        if (proactiveJob?.isActive == true) return
        proactiveJob = viewModelScope.launch {
            while (true) {
                delay(PROACTIVE_TICK_MS)
                val now = System.currentTimeMillis()
                val speed = gps.value.speedKmh
                proactivePrompts.onSpeed(now, speed)
                if (!voiceProactiveOn.value || !onScreen) continue
                val route = nav.ui.value
                val batt = battery.state.value
                val busy = call.state.value.phase != CallPhase.IDLE || voiceAssistant.ui.value.phase != VoicePhase.IDLE
                val kind = proactivePrompts.next(
                    ProactiveInput(
                        now, speed, weather.snapshot.value?.rainInMin, batt.percent, batt.charging, route.navigating,
                        route.remainingM, busy, batt.tempC, batt.hasReading, voiceProactiveKinds.value.filterValues { it }.keys,
                        BluetoothMicRouter.isBluetoothInputAvailable(getApplication()),
                        navigationTrackingError = route.termination == NavigationTermination.TRACKING_ERROR,
                    )
                ) ?: continue
                val (question, command) = when (kind) {
                    ProactiveKind.RAIN -> getApplication<Application>().getString(R.string.voice_proactive_rain) to VoiceCommand.Info(InfoQuestion.WEATHER)
                    ProactiveKind.BATTERY -> getApplication<Application>().getString(R.string.voice_proactive_battery, batt.percent) to VoiceCommand.Info(InfoQuestion.BATTERY)
                    ProactiveKind.TEMPERATURE -> getApplication<Application>().getString(R.string.voice_proactive_temperature, batt.tempC.toInt()) to VoiceCommand.Info(InfoQuestion.BATTERY)
                    ProactiveKind.ARRIVAL -> getApplication<Application>().getString(R.string.voice_proactive_arrival) to VoiceCommand.FindNearest("otopark", "otopark")
                    ProactiveKind.BREAK -> getApplication<Application>().getString(R.string.voice_proactive_break) to VoiceCommand.FindNearest("kafe", "kafe")
                    ProactiveKind.BLUETOOTH -> getApplication<Application>().getString(R.string.voice_proactive_bluetooth) to VoiceCommand.Info(InfoQuestion.RIDE_STATUS)
                    ProactiveKind.NAVIGATION_ERROR -> getApplication<Application>().getString(R.string.voice_proactive_navigation_error) to VoiceCommand.Info(InfoQuestion.RIDE_STATUS)
                }
                voiceAssistant.proactive(question, command)
            }
        }
    }

    val dashSlots = MutableStateFlow(DashLayout.fromString(Prefs.dashSlots(app)))

    fun setDashSlot(index: Int, card: DashCard) {
        val next = DashLayout.set(dashSlots.value, index, card)
        dashSlots.value = next
        Prefs.setDashSlots(getApplication(), DashLayout.toString(next))
    }

    init {
        viewModelScope.launch {
            var assistantWasBusy = false
            voiceAssistant.ui.collect { state ->
                if (state.phase != VoicePhase.IDLE) {
                    assistantWasBusy = true
                    wakeWord.stop()
                } else if (assistantWasBusy) {
                    assistantWasBusy = false
                    wakeWord.resumeAfterCommand()
                } else {
                    applyVoiceExtras()
                }
            }
        }
        viewModelScope.launch {
            AudioSessionManager.process.owner.collect { owner ->
                if (owner == null && voiceAssistant.ui.value.phase == VoicePhase.IDLE) applyVoiceExtras()
            }
        }
    }

    val pinnedApps = MutableStateFlow(PinnedApps.fromJson(Prefs.pinnedApps(app)))

    private fun updatePinned(list: List<String>) {
        pinnedApps.value = list
        Prefs.setPinnedApps(getApplication(), PinnedApps.toJson(list))
    }

    fun pinApp(pkg: String) = updatePinned(PinnedApps.add(pinnedApps.value, pkg))

    fun unpinApp(pkg: String) = updatePinned(PinnedApps.remove(pinnedApps.value, pkg))

    fun moveApp(pkg: String, delta: Int) = updatePinned(PinnedApps.move(pinnedApps.value, pkg, delta))

    val places = MutableStateFlow(SavedPlaces.fromJson(Prefs.savedPlaces(app)))

    fun savePlace(kind: PlaceKind, place: Place) = updatePlaces(SavedPlaces.put(places.value, SavedPlace(kind, place)))

    fun removePlace(item: SavedPlace) = updatePlaces(SavedPlaces.remove(places.value, item))

    private fun updatePlaces(list: List<SavedPlace>) {
        places.value = list
        Prefs.setSavedPlaces(getApplication(), SavedPlaces.toJson(list))
    }

    val weatherEnabled = MutableStateFlow(Prefs.weatherEnabled(app))
    val rainVoice = MutableStateFlow(Prefs.rainVoice(app))
    val weather = WeatherController(viewModelScope, location.state, weatherEnabled, windAlertKmh = { profileNow.config.windAlertKmh }) { alert, snapshot ->
        if (!rainVoice.value || call.state.value.phase != CallPhase.IDLE) return@WeatherController
        announcer.speak(
            when (alert) {
                WeatherAlert.RAIN -> snapshot.rainInMin?.takeIf { it > 0 }
                    ?.let { app.getString(R.string.rain_voice_fmt, it) } ?: app.getString(R.string.rain_voice_now)
                WeatherAlert.ICE -> app.getString(R.string.ice_voice_fmt, snapshot.tempC)
                WeatherAlert.WIND -> app.getString(R.string.wind_voice_fmt, snapshot.windKmh)
            }
        )
    }

    fun setWeatherEnabled(enabled: Boolean) {
        Prefs.setWeatherEnabled(getApplication(), enabled)
        weatherEnabled.value = enabled
    }

    fun setRainVoice(enabled: Boolean) {
        Prefs.setRainVoice(getApplication(), enabled)
        rainVoice.value = enabled
    }

    /** A number handed over by another app's dial link; the phone screen shows it on the keypad. */
    val dialRequest = MutableStateFlow<String?>(null)

    val speedLimitEnabled = MutableStateFlow(Prefs.speedLimit(app, profileNow))
    private val guiding: Flow<Boolean> = nav.ui.map { ui: NavUi -> ui.navigating }.distinctUntilChanged()
    private val limitActive: StateFlow<Boolean> =
        combine(speedLimitEnabled, guiding) { on: Boolean, navigating: Boolean -> on && navigating }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val speedLimit = SpeedLimitController(
        viewModelScope, location.state, limitActive,
        repo = com.ridedeck.app.data.SpeedLimitRepository { profileNow.config.costing },
    ) { limit ->
        if (call.state.value.phase == CallPhase.IDLE) {
            announcer.speak(app.getString(R.string.speed_limit_warning, limit))
        }
    }

    fun setSpeedLimitEnabled(enabled: Boolean) {
        Prefs.setSpeedLimit(getApplication(), enabled)
        speedLimitEnabled.value = enabled
    }

    val recordTrips = MutableStateFlow(Prefs.recordTrips(app))
    val trips = TripRecorder(TripStore(app), viewModelScope, location.state, recordTrips) { stop ->
        // Today only the time; an older spot also says which day, so a bike left yesterday is not mistaken for one just left.
        val pattern = if (android.text.format.DateUtils.isToday(stop.t)) "HH:mm" else "d MMM HH:mm"
        val time = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(stop.t))
        savePlace(PlaceKind.PARKED, Place(app.getString(R.string.saved_parked), time, GeoPoint(stop.lat, stop.lon)))
    }

    fun setRecordTrips(enabled: Boolean) {
        Prefs.setRecordTrips(getApplication(), enabled)
        recordTrips.value = enabled
    }

    /** Bumped on every onResume so permission-dependent UI re-checks its state. */
    val resumeTick = MutableStateFlow(0)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }

    val gps: StateFlow<GpsUi> = combine(location.state, ticker) { loc, now ->
        val fresh = now - loc.fixAtMs < FIX_TIMEOUT_MS
        GpsUi(if (fresh) loc.speedKmh else 0, fresh, loc.accuracyM, loc.fixAgeMs, loc.fixIntervalMs, loc.speedAccuracyMps, if (fresh) location.displayKmh()?.roundToInt() else null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GpsUi())

    val thermalEnabled = MutableStateFlow(Prefs.thermalGuard(app))
    val thermal = ThermalGuard(
        viewModelScope, battery.state,
        gps.map { it.speedKmh }.stateIn(viewModelScope, SharingStarted.Eagerly, 0),
        thermalEnabled,
    ) {
        if (call.state.value.phase == CallPhase.IDLE) announcer.speak(app.getString(R.string.thermal_voice))
    }

    fun setThermalEnabled(enabled: Boolean) {
        Prefs.setThermalGuard(getApplication(), enabled)
        thermalEnabled.value = enabled
    }

    val sosContacts = MutableStateFlow(SosMath.fromJson(Prefs.sosContacts(app)))
    val sosCrashEnabled = MutableStateFlow(Prefs.sosCrash(app))
    val sosButtonEnabled = MutableStateFlow(Prefs.sosButton(app))
    val sos = SosController(
        scope = viewModelScope,
        location = location.state,
        contacts = sosContacts,
        sms = AndroidSmsSender(app),
        alarm = AndroidSosAlarm(app),
        smsAllowed = { app.hasPermission(Manifest.permission.SEND_SMS) },
        speak = { reason, seconds ->
            announcer.speak(
                when (reason) {
                    SosReason.AUTO -> app.getString(R.string.sos_voice_auto, seconds)
                    SosReason.MANUAL -> app.getString(R.string.sos_voice_manual, seconds)
                    SosReason.TEST -> app.getString(R.string.sos_voice_test)
                }
            )
        },
        message = { reason, link, stale ->
            val head = if (reason == SosReason.AUTO) {
                val noun = when (profileNow) {
                    VehicleProfile.MOTORCYCLE -> R.string.sos_noun_moto
                    VehicleProfile.CAR -> R.string.sos_noun_car
                    VehicleProfile.BICYCLE -> R.string.sos_noun_bike
                }
                app.getString(R.string.sos_sms_auto, app.getString(noun))
            } else app.getString(R.string.sos_sms_manual)
            val where = when {
                link == null -> app.getString(R.string.sos_loc_none)
                stale -> app.getString(R.string.sos_loc_stale, link)
                else -> app.getString(R.string.sos_loc, link)
            }
            "$head $where"
        },
    )
    private val crashSensor = CrashSensor(app, speedKmh = { gps.value.speedKmh }, config = { profileNow.config }) { sos.trigger(SosReason.AUTO) }

    fun setSosCrashEnabled(enabled: Boolean) {
        Prefs.setSosCrash(getApplication(), enabled)
        sosCrashEnabled.value = enabled
        if (enabled) crashSensor.start() else crashSensor.stop()
    }

    fun setSosButtonEnabled(enabled: Boolean) {
        Prefs.setSosButton(getApplication(), enabled)
        sosButtonEnabled.value = enabled
    }

    fun addSosContact(contact: SosContact) = updateSosContacts(SosMath.addContact(sosContacts.value, contact))

    fun removeSosContact(contact: SosContact) = updateSosContacts(sosContacts.value - contact)

    private fun updateSosContacts(list: List<SosContact>) {
        sosContacts.value = list
        Prefs.setSosContacts(getApplication(), SosMath.toJson(list))
        // Without a contact there is nobody to alert, so the triggers switch themselves off.
        if (list.isEmpty()) {
            setSosCrashEnabled(false)
            setSosButtonEnabled(false)
        }
    }

    init {
        AndroidSosAlarm(app).restoreVolumeIfPending()
        weather.start()
        viewModelScope.launch {
            MessageBridge.latest.filterNotNull().collect { message ->
                val fresh = System.currentTimeMillis() - message.postedAtMs < FRESH_MESSAGE_MS
                if (fresh && onScreen && readMessagesAloud.value && call.state.value.phase == CallPhase.IDLE) speakMessage(message)
            }
        }
    }

    /** True between onResume and onPause: messages are only read aloud while RideDeck is the app on screen. */
    @Volatile private var onScreen = false

    init {
        // Music that was playing before a call goes on afterwards, for apps that do not resume by themselves.
        viewModelScope.launch {
            var resumeAfter = false
            var previous = CallPhase.IDLE
            call.state.collect { c ->
                if (previous == CallPhase.IDLE && c.phase != CallPhase.IDLE) {
                    resumeAfter = media.isPlaying
                    voiceAssistant.cancel()
                    announcer.stop()
                }
                if (previous != CallPhase.IDLE && c.phase == CallPhase.IDLE && resumeAfter) {
                    resumeAfter = false
                    delay(RESUME_AFTER_CALL_MS)
                    if (!media.isPlaying) media.play()
                }
                previous = c.phase
                applyVoiceExtras()
            }
        }
    }

    fun onResume() {
        onScreen = true
        startProactive()
        location.start()
        battery.start()
        media.start()
        theme.start()
        if (sosCrashEnabled.value) crashSensor.start()
        call.start()
        // The strip is a service: after a reboot or a killed process it has to be started again.
        if (Prefs.overlayEnabled(getApplication())) OverlayService.start(getApplication())
        resumeTick.value++
        applyVoiceExtras()
    }

    fun setReadMessagesAloud(enabled: Boolean) {
        Prefs.setReadMessagesAloud(getApplication(), enabled)
        readMessagesAloud.value = enabled
    }

    fun speakMessage(message: IncomingMessage) {
        val ctx = getApplication<Application>()
        val body = simplifyMessage(message.text, ctx.getString(R.string.message_link))
        val sender = message.sender.trim()
        announcer.speak(if (sender.isEmpty()) body else ctx.getString(R.string.message_speak_fmt, sender, body))
    }

    fun onPause() {
        onScreen = false
        voiceAssistant.cancel()
        voiceInput.cancel()
        wakeWord.stop()
        voiceButton.stop()
        AudioSessionManager.process.stateMachine.transition(VoiceRuntimeState.SUSPENDED)
        crashSensor.stop()
        theme.stop()
    }

    override fun onCleared() {
        voiceAssistant.cancel()
        wakeWord.stop()
        announcer.shutdown()
        theme.stop()
        nav.shutdown()
        location.stop()
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 5000L
        const val PROACTIVE_TICK_MS = 20_000L
        const val FRESH_MESSAGE_MS = 30_000L
        const val RESUME_AFTER_CALL_MS = 1500L
        const val DEFAULT_VOICE_BRIGHTNESS = 0.7f
        const val MIN_VOICE_BRIGHTNESS = 0.1f
        const val BRIGHTNESS_STEP = 0.2f
    }
}

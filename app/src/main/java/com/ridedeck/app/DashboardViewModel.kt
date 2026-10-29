package com.ridedeck.app

import android.Manifest
import com.ridedeck.app.data.AndroidSmsSender
import com.ridedeck.app.data.AndroidSosAlarm
import com.ridedeck.app.data.CrashSensor
import com.ridedeck.app.data.outlook
import com.ridedeck.app.data.WeatherRepository
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
import com.ridedeck.app.data.RecentPlaces
import com.ridedeck.app.data.SavedPlaces
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ridedeck.app.data.SpeedLimitController
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridedeck.app.data.BatteryRepository
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.hasFreshFix
import com.ridedeck.app.data.PhoneMath
import com.ridedeck.app.data.PhoneRepository
import com.ridedeck.app.data.ThemeController
import com.ridedeck.app.service.CallBridge
import com.ridedeck.app.service.OverlayService
import com.ridedeck.app.service.IncomingMessage
import com.ridedeck.app.service.MessageBridge
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.Units
import com.ridedeck.app.util.simplifyMessage
import kotlinx.coroutines.flow.Flow
import com.ridedeck.app.data.NavUi
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
    val rideTracking = (app as RideDeckApplication).rideTracking
    private val location = rideTracking.location
    val openTrips = MutableStateFlow(false)

    /** The speed the gauge draws, in km/h, smoothed and projected forward between fixes; null while the speed is unknown. */
    fun displaySpeedKmh(): Double? = location.displayKmh()
    val battery = BatteryRepository(app)
    private val navigation = (app as RideDeckApplication).rideNavigation
    val media = navigation.media
    val call = navigation.call
    val theme = ThemeController(app)

    val messages: StateFlow<IncomingMessage?> = MessageBridge.latest
    val readMessagesAloud = MutableStateFlow(Prefs.readMessagesAloud(app))
    val voiceTtsNames = navigation.voiceTtsNames
    val selectedTtsVoice = navigation.selectedTtsVoice
    private val announcer = navigation.voice

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

    val locationState: StateFlow<LocationState> = location.state

    /** Null until the first-launch chooser has been answered. */
    val vehicleChosen = MutableStateFlow(Prefs.vehicle(app))
    private val profileNow: VehicleProfile get() = vehicleChosen.value ?: VehicleProfile.MOTORCYCLE
    val profile: StateFlow<VehicleProfile> = vehicleChosen.map { it ?: VehicleProfile.MOTORCYCLE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, profileNow)

    val nav = navigation.nav

    /**
     * First-launch choice or a change in Settings. Settings the user set themselves stay as they are;
     * only what they never touched (the speed-limit display) follows the new vehicle.
     */
    fun setVehicle(profile: VehicleProfile) {
        val app = getApplication<Application>()
        Prefs.setVehicle(app, profile)
        vehicleChosen.value = profile
        nav.setProfile(profile)
        crashSensor.stop()
        if (onScreen && sosCrashEnabled.value) crashSensor.start()
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

    val voiceSpeechRate = MutableStateFlow(Prefs.voiceSpeechRate(app))

    fun setVoiceSpeechRate(rate: Float) {
        val bounded = rate.coerceIn(0.75f, 1.25f)
        voiceSpeechRate.value = bounded
        Prefs.setVoiceSpeechRate(getApplication(), bounded)
        announcer.setSpeechRate(bounded)
    }

    val dashSlots = MutableStateFlow(DashLayout.fromString(Prefs.dashSlots(app)))

    fun setDashSlot(index: Int, card: DashCard) {
        val next = DashLayout.set(dashSlots.value, index, card)
        dashSlots.value = next
        Prefs.setDashSlots(getApplication(), DashLayout.toString(next))
    }

    val pinnedApps = MutableStateFlow(PinnedApps.fromJson(Prefs.pinnedApps(app)))

    private fun updatePinned(list: List<String>) {
        pinnedApps.value = list
        Prefs.setPinnedApps(getApplication(), PinnedApps.toJson(list))
    }

    fun pinApp(pkg: String) = updatePinned(PinnedApps.add(pinnedApps.value, pkg))

    fun unpinApp(pkg: String) = updatePinned(PinnedApps.remove(pinnedApps.value, pkg))

    fun moveApp(pkg: String, delta: Int) = updatePinned(PinnedApps.move(pinnedApps.value, pkg, delta))

    val places = rideTracking.places

    /** Destinations the rider chose lately (this phone only); empty and not recorded while the history is off. */
    val recents = navigation.recents

    fun clearRecents() {
        recents.value = emptyList()
        Prefs.setRecentPlaces(getApplication(), RecentPlaces.toJson(emptyList()))
    }

    fun setRecentsOn(on: Boolean) {
        Prefs.setRecentPlacesOn(getApplication(), on)
        if (!on) clearRecents()
    }

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

    val recordTrips = rideTracking.recordTrips
    val trips = rideTracking.trips

    fun setRecordTrips(enabled: Boolean) = rideTracking.setRecording(enabled)

    /** Route Start is also an explicit ride action; preferences still decide whether points are saved. */
    fun startNavigation() {
        nav.startNavigation()
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
        val fresh = loc.hasFreshFix(now, android.os.SystemClock.elapsedRealtime())
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
    private val crashSensor = CrashSensor(app, speedKmh = {
        val fix = locationState.value
        if (fix.hasSpeed && fix.hasFreshFix(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())) fix.speedKmh else null
    }, config = { profileNow.config }) { sos.trigger(SosReason.AUTO) }

    fun setSosCrashEnabled(enabled: Boolean) {
        Prefs.setSosCrash(getApplication(), enabled)
        sosCrashEnabled.value = enabled
        if (enabled && onScreen) crashSensor.start() else crashSensor.stop()
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

    fun onResume() {
        onScreen = true
        rideTracking.refresh()
        battery.start()
        navigation.refreshAudio()
        theme.start()
        if (sosCrashEnabled.value) crashSensor.start()
        // The strip is a service: after a reboot or a killed process it has to be started again.
        if (Prefs.overlayEnabled(getApplication())) OverlayService.start(getApplication())
        resumeTick.value++
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
        crashSensor.stop()
        theme.stop()
    }

    override fun onCleared() {
        sos.close()
        battery.stop()
        crashSensor.stop()
        theme.stop()
    }

    private companion object {
        const val FRESH_MESSAGE_MS = 30_000L
    }
}

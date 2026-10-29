package com.ridedeck.app.data

import android.content.Context
import android.media.AudioManager
import com.ridedeck.app.service.CallBridge
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One in-memory navigation/TTS/audio session per process. Activity and ViewModel are only clients. */
class RideNavigation(context: Context, private val tracking: RideTracking) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableSetOf<Any>()
    val media = MediaRepository(app)
    val call = CallRepository(app, scope)
    val voiceTtsNames = MutableStateFlow<List<String>>(emptyList())
    val selectedTtsVoice = MutableStateFlow(Prefs.voiceTtsName(app))
    val voice = Voice(app) {
        voiceTtsNames.value = it
        selectedTtsVoice.value = Prefs.voiceTtsName(app)
    }
    val recents = MutableStateFlow(if (Prefs.recentPlacesOn(app)) RecentPlaces.fromJson(Prefs.recentPlaces(app)) else emptyList())
    val nav = NavigationController(
        app::getString, scope, tracking.location.state, voice,
        repo = RouteRepository(miles = { Units.miles }),
        initialOptions = Prefs.routeOptions(app, Prefs.vehicle(app) ?: VehicleProfile.MOTORCYCLE),
        onOptionsChanged = { Prefs.setRouteOptions(app, it) },
        knownPlaces = { tracking.places.value.map { it.place } + recents.value },
        onPlaceChosen = { place ->
            if (Prefs.recentPlacesOn(app)) {
                recents.value = RecentPlaces.put(recents.value, place)
                Prefs.setRecentPlaces(app, RecentPlaces.toJson(recents.value))
            }
        },
        beforeStart = { tracking.requestStart(screens.isNotEmpty()) },
    )
    private val lifecycle = NavigationRideLifecycle(scope, nav, tracking.session) { guiding ->
        tracking.navigating = guiding
        refreshAudio()
    }
    private val interruption = CallInterruptionController(
        scope, call.state.map { it.phase }.distinctUntilChanged(),
        isPlaying = { media.isPlaying }, player = { media.state.value.packageName },
        commandVersion = { media.commandVersion }, suspendSpeech = { active ->
            voice.suspendForCall(active)
            if (active) media.cancelPendingResume()
        },
        resume = media::play,
        canResume = { audioNeeded && app.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_NORMAL },
        outputDevices = media::externalOutputDevices,
    )

    init {
        scope.launch { call.state.collect { if (it.phase == CallPhase.IDLE) CallBridge.callerName.value = null } }
    }

    private val audioNeeded: Boolean get() = screens.isNotEmpty() || tracking.navigating

    fun setScreenVisible(owner: Any, visible: Boolean) {
        if (visible) screens.add(owner) else screens.remove(owner)
        refreshAudio()
    }

    fun refreshAudio() {
        if (audioNeeded) {
            media.start()
            call.start()
        } else {
            media.stop()
            call.stop()
            // Leave the final arrival announcement intact; it releases its focus on completion.
        }
    }
}

package com.ridedeck.app.data

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.ridedeck.app.service.CallBridge
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One in-memory navigation/TTS/audio session per process. Activity and ViewModel are only clients. */
class RideNavigation(
    context: Context,
    private val tracking: RideTracking,
    /** Injected owner fact; no second connectivity callback is created here. UNKNOWN is the safe default. */
    private val connectivityStatus: () -> ConnectivityStatus = { ConnectivityStatus.UNKNOWN },
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = mutableSetOf<Any>()
    private data class RetryContext(val savedJson: String, val saved: ActiveTrip, val choices: Long)
    private var retryContext: RetryContext? = null
    /** Weak-token registration from DashboardViewModel; missing/collected fails closed. */
    private val motionInput = MotionInputProviderRegistry()
    private val startupTrips = object : StartupTripStore {
        override fun read(): String? = Prefs.activeTrip(app)
        override fun clearIf(expected: String) {
            if (Prefs.activeTrip(app) == expected) Prefs.setActiveTrip(app, null)
        }
    }
    private val startupInvalidation = StartupRecordInvalidation(startupTrips) { geometryCommands.enqueueClear() }

    fun registerMotionInputLocked(provider: () -> Boolean): Any = motionInput.register(provider)

    fun unregisterMotionInputLocked(token: Any) = motionInput.unregister(token)
    val media = MediaRepository(app)
    val call = CallRepository(app, scope)
    val voiceTtsNames = MutableStateFlow<List<String>>(emptyList())
    val selectedTtsVoice = MutableStateFlow(Prefs.voiceTtsName(app))
    val voice = Voice(app) {
        voiceTtsNames.value = it
        selectedTtsVoice.value = Prefs.voiceTtsName(app)
    }
    private val geometryStore = RouteGeometryStore(AtomicFileRouteGeometryGateway(app))
    private val geometryCommands = RouteGeometryCommandQueue(geometryStore)
    val recents = MutableStateFlow(if (Prefs.recentPlacesOn(app)) RecentPlaces.fromJson(Prefs.recentPlaces(app)) else emptyList())
    val nav = NavigationController(
        app::getString, scope, tracking.location.state, voice,
        repo = RouteRepository(miles = { Units.miles }),
        initialOptions = Prefs.routeOptions(app),
        onOptionsChanged = { Prefs.setRouteOptions(app, it) },
        knownPlaces = { tracking.places.value.map { it.place } + recents.value },
        onPlaceChosen = { place ->
            if (Prefs.recentPlacesOn(app)) {
                recents.value = RecentPlaces.put(recents.value, place)
                Prefs.setRecentPlaces(app, RecentPlaces.toJson(recents.value))
            }
        },
        beforeStart = { tracking.requestStart(screens.isNotEmpty()) },
        miles = { Units.miles },
        inCall = { call.state.value.phase != CallPhase.IDLE },
        cue = { NavCue.pulse(app) },
        nowElapsedMs = SystemClock::elapsedRealtime,
        onUserRouteChoice = { reason ->
            retryContext = null
            startupInvalidation.onRouteChoice(reason)
        },
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
        // Keep the guided trip on disk; after a process kill the destination is offered again once GPS has a fix.
        val savedJson = Prefs.activeTrip(app)
        startupInvalidation.startupJson = savedJson
        val saved = ActiveTrip.restore(savedJson, System.currentTimeMillis())
        if (saved == null) Prefs.setActiveTrip(app, null)
        val routeChoicesAtStart = nav.userRouteChoiceCount()
        var wasGuiding = false
        scope.launch { geometryCommands.run() }
        DebugSim.hook = { route, multiplier ->
            val speed = 16.0
            tracking.location.simulate(RouteSimulator.fixes(route, RouteSimulator.Plan(speedMps = speed, noiseM = 4.0)), multiplier)
        }
        DebugSim.stopHook = { tracking.location.stopSimulation() }
        scope.launch {
            // Route options are part of the snapshot: an option change during guidance persists immediately.
            combine(nav.ui, nav.options) { ui, options -> ActiveTripSnapshot.from(ui, options) }.distinctUntilChanged().collect { snapshot ->
                when (val write = ActiveTripPersistence.next(wasGuiding, snapshot, System.currentTimeMillis())) {
                    is ActiveTripWrite.Save -> Prefs.setActiveTrip(app, write.trip.toJson())
                    ActiveTripWrite.Clear -> {
                        retryContext = null
                        Prefs.setActiveTrip(app, null)
                        tracking.location.stopSimulation()
                    }
                    ActiveTripWrite.None -> Unit
                }
                wasGuiding = snapshot.guiding
            }
        }
        var previousGeometryKey: RouteGeometryKey? = null
        var geometryCreatedAtMs = 0L
        scope.launch {
            // Progress-free key + conflate: point movement never writes, and only the latest route is committed.
            combine(nav.ui, nav.options) { ui, options -> RouteGeometryPersistence.decide(ui, options) }
                .distinctUntilChanged()
                .conflate()
                .collect { decision ->
                    when (decision) {
                        is RouteGeometryDecision.Save -> {
                            val key = decision.key
                            val createdAt = RouteGeometryPersistence.createdAtMs(
                                previousGeometryKey, geometryCreatedAtMs, key, System.currentTimeMillis(),
                            )
                            val snapshot = RouteGeometrySnapshot(
                                key.destination,
                                key.finalDestination,
                                key.stopsAhead,
                                key.options,
                                createdAt,
                                key.route,
                            )
                            val activeTrip = ActiveTrip(key.destination, key.finalDestination, createdAt, key.stopsAhead, key.options)
                            if (geometryCommands.enqueueSave(snapshot, activeTrip)) {
                                previousGeometryKey = key
                                geometryCreatedAtMs = createdAt
                            }
                        }
                        RouteGeometryDecision.Clear -> {
                            geometryCommands.enqueueClear()
                            previousGeometryKey = null
                            geometryCreatedAtMs = 0L
                        }
                        RouteGeometryDecision.None -> Unit
                    }
                }
        }
        val startupCoordinator = StartupRestoreCoordinator(
            trips = startupTrips,
            geometry = object : StartupGeometrySource {
                override suspend fun restore(activeTrip: ActiveTrip, nowMs: Long): RouteGeometryRestoreResult =
                    geometryStore.restore(activeTrip, nowMs)
            },
            navigation = object : StartupNavigationPort {
                override fun routeChoiceCount(): Long = nav.userRouteChoiceCount()
                override fun hasDestination(): Boolean = nav.ui.value.destination != null
                override fun isNavigating(): Boolean = nav.ui.value.navigating
                override fun restoreCachedPreview(snapshot: RouteGeometrySnapshot): Boolean = nav.restoreCachedPreview(
                    CachedRoutePreview(
                        destination = snapshot.destination,
                        remainingStops = snapshot.remainingStops,
                        options = snapshot.options,
                        route = snapshot.route,
                    )
                )
                override fun restoreNetworkTrip(activeTrip: ActiveTrip) {
                    nav.restoreTrip(activeTrip.destination, activeTrip.finalDestination, activeTrip.stops, activeTrip.options)
                }
                override fun markNeedsNetwork() = nav.markCachedRouteNeedsNetwork()
            },
            connectivity = connectivityStatus,
            nowMs = System::currentTimeMillis,
        )
        if (saved != null) scope.launch {
            tracking.location.state.first { it.hasFreshFix(System.currentTimeMillis()) }
            val outcome = startupCoordinator.restore(savedJson, saved, routeChoicesAtStart)
            retryContext = if (outcome == StartupRestoreOutcome.NEEDS_NETWORK && savedJson != null) {
                RetryContext(savedJson, saved, nav.userRouteChoiceCount())
            } else null
        }
        scope.launch { call.state.collect { if (it.phase == CallPhase.IDLE) CallBridge.callerName.value = null } }
    }

    private val audioNeeded: Boolean get() = screens.isNotEmpty() || tracking.navigating

    /**
     * Explicit user retry for a saved trip whose geometry was unavailable. Preview only; never starts guidance.
     * Requires validated network, fresh fine-location GPS (wall + elapsed), unchanged ActiveTrip and no user
     * route command since the startup context was captured.
     */
    fun retryCachedRouteRestore(): Boolean {
        val context = retryContext ?: return false
        if (connectivityStatus() != ConnectivityStatus.VALIDATED) return false
        val ui = nav.ui.value
        if (ui.navigating || ui.loading || ui.rerouting || ui.destination != null) return false
        if (nav.userRouteChoiceCount() != context.choices) {
            retryContext = null
            return false
        }
        if (Prefs.activeTrip(app) != context.savedJson) {
            retryContext = null
            return false
        }
        val now = System.currentTimeMillis()
        if (!context.saved.fresh(now)) {
            retryContext = null
            Prefs.setActiveTrip(app, null)
            geometryCommands.enqueueClear()
            return false
        }
        val fix = tracking.location.state.value
        if (!CachedRetryPolicy.allowed(
                contextPresent = true,
                connectivity = connectivityStatus(),
                inputLocked = motionInput.isLockedOrFailClosed(),
                freshFix = fix.hasFreshFix(now, SystemClock.elapsedRealtime()),
                permissionGranted = fix.locationPermissionGranted == true,
                providerEnabled = fix.gpsProviderEnabled == true,
            )
        ) return false

        nav.restoreTrip(context.saved.destination, context.saved.finalDestination, context.saved.stops, context.saved.options)
        return true
    }

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

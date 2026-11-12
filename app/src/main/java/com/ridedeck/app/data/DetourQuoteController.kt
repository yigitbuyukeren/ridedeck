package com.ridedeck.app.data

import com.ridedeck.app.util.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Snapshot made from the owner's existing NavUi/location/options state; no GPS or routing call. */
object DetourQuoteSnapshot {
    fun from(
        nav: NavUi,
        location: LocationState,
        options: RouteOptions,
        contextGeneration: Long,
        maxStops: Int,
        language: String = GuidanceLanguage.current(),
        miles: Boolean = Units.miles,
        nowMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): DetourContext? {
        if (!nav.navigating || nav.route == null) return null
        val final = nav.finalDestination ?: nav.destination ?: return null
        if (location.fixAtMs <= 0L || !location.lat.isFinite() || !location.lon.isFinite()) return null
        val stops = if (nav.finalDestination != null) nav.stops else nav.stops.drop(nav.stopsPassed)
        val fineGranted = location.locationPermissionGranted == true
        return DetourContext(
            origin = GeoPoint(location.lat, location.lon),
            fixAtMs = location.fixAtMs,
            final = final,
            remainingStops = stops,
            options = options,
            headingDeg = DetourContext.quantizeHeading(location.bearing?.toDouble()),
            language = language,
            miles = miles,
            maxStops = maxStops,
            contextGeneration = contextGeneration,
            // Unknown fine permission fails closed even if a caller sets a fresh-looking fix.
            freshFix = fineGranted && location.hasFreshFix(nowMs, elapsedMs),
        )
    }
}

data class DetourQuoteUi(
    val loading: Boolean = false,
    val station: Place? = null,
    val quote: DetourQuote? = null,
)

/**
 * App-owner wrapper for one explicit selected-station quote. It keeps cache/budget ownership in
 * one place, accepts an exact snapshot, and never mutates navigation or starts guidance.
 */
internal class DetourQuoteController(
    private val scope: CoroutineScope,
    private val routing: Routing,
    private val currentContext: () -> DetourContext?,
    private val motionLocked: () -> Boolean,
    cache: DetourQuoteCache = DetourQuoteCache(),
    requestGate: QuoteRequestGate = BudgetedQuoteRequestGate(
        ProviderRequestBudget(ProviderBudgetDefaults.CONSERVATIVE),
        nowMs = { System.nanoTime() / 1_000_000L },
        wait = { kotlinx.coroutines.delay(it) },
    ),
    private val wallNowMs: () -> Long = System::currentTimeMillis,
) {
    private val orchestrator = DetourQuoteOrchestrator(
        routing = routing,
        cache = cache,
        cacheNowMs = { System.nanoTime() / 1_000_000L },
        wallNowMs = wallNowMs,
        requestGate = requestGate,
    )
    private val _ui = MutableStateFlow(DetourQuoteUi())
    val ui: StateFlow<DetourQuoteUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var requestGeneration = 0L
    private var activeStation: Place? = null
    private var disposed = false

    /** Returns false for motion lock/same-station busy; a different station advances the token. */
    fun request(snapshot: DetourContext, station: Place): Boolean {
        if (disposed) return false
        if (motionLocked()) {
            _ui.value = DetourQuoteUi(
                station = station,
                quote = DetourQuote.unavailable(DetourUnavailableReason.MOTION_LOCKED),
            )
            return false
        }
        if (job?.isActive == true && activeStation == station) return false
        if (job?.isActive == true) job?.cancel()
        val generation = ++requestGeneration
        activeStation = station
        _ui.value = DetourQuoteUi(loading = true, station = station)
        val liveContext = { if (motionLocked()) null else currentContext() }
        job = scope.launch {
            val quote = orchestrator.quote(snapshot, station, liveContext)
            if (generation != requestGeneration || activeStation != station) return@launch
            if (motionLocked()) {
                _ui.value = DetourQuoteUi(
                    station = station,
                    quote = DetourQuote.unavailable(DetourUnavailableReason.MOTION_LOCKED),
                )
                return@launch
            }
            _ui.value = DetourQuoteUi(station = station, quote = quote)
        }
        return true
    }

    /** App owner calls this from the same motion-lock owner used by Settings. */
    fun onMotionLockChanged(locked: Boolean) {
        if (!locked) return
        requestGeneration++
        job?.cancel()
        job = null
        activeStation = null
        _ui.value = DetourQuoteUi(quote = DetourQuote.unavailable(DetourUnavailableReason.MOTION_LOCKED))
    }

    fun close() {
        requestGeneration++
        job?.cancel()
        job = null
        activeStation = null
        _ui.value = DetourQuoteUi()
    }

    /** Route/options/stop changes invalidate an in-flight or displayed quote. */
    fun invalidate() {
        requestGeneration++
        job?.cancel()
        job = null
        activeStation = null
        _ui.value = DetourQuoteUi(quote = DetourQuote.unavailable(DetourUnavailableReason.STALE_CONTEXT))
    }

    fun dispose() {
        disposed = true
        close()
    }
}

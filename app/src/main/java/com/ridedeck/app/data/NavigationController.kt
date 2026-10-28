package com.ridedeck.app.data

import com.ridedeck.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class NavigationTermination { NONE, USER_CANCELLED, ARRIVED, LOCATION_DISABLED, LOCATION_PERMISSION_REVOKED, TRACKING_ERROR }

data class NavUi(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val destination: Place? = null,
    /** Set while heading for a fuel stop: where the trip continues afterwards. */
    val finalDestination: Place? = null,
    val route: Route? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val navigating: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val maneuverIndex: Int = 0,
    val distToNextM: Int = 0,
    val remainingM: Int = 0,
    val remainingS: Int = 0,
    val termination: NavigationTermination = NavigationTermination.NONE,
    /** "Did you mean": a known name close to a query that found nothing; picking it runs a search, never a route. */
    val suggestion: String? = null,
    /** Set when [results] come from a corrected spelling of the query, so the panel can say so. */
    val resultsFor: String? = null,
)

data class FuelUi(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val stations: List<FuelStation> = emptyList(),
    val error: String? = null,
    /** True when the list covers the road ahead rather than the surroundings. */
    val alongRoute: Boolean = false,
)

/**
 * Search -> route -> turn-by-turn guidance. Matches the GPS fix to the route polyline,
 * announces manoeuvres and re-routes when the rider leaves the route. A fuel stop is routed as its
 * own short trip; arriving there offers to continue to the original destination.
 *
 * Everything outside (routing service, voice, strings, fuel lookup) is injected so the logic is
 * unit tested without Android.
 */
class NavigationController(
    private val text: (Int) -> String,
    private val scope: CoroutineScope,
    private val location: StateFlow<LocationState>,
    private val voice: Speaker,
    private val repo: Routing = RouteRepository(),
    private val fuelFinder: FuelFinder = FuelRepository(),
    initialOptions: RouteOptions = RouteOptions(),
    private val onOptionsChanged: (RouteOptions) -> Unit = {},
    private val locationUpdates: Flow<LocationState> = location,
    private val routeTimeoutMs: Long = ROUTE_TIMEOUT_MS,
    /** Saved and recent places, for fuzzy matching a mistyped query without the network. */
    private val knownPlaces: () -> List<Place> = { emptyList() },
    /** A place the rider picked as a destination (kept as a recent place when the rider allows it). */
    private val onPlaceChosen: (Place) -> Unit = {},
    /** Nominatim allows one request per second: the pause between a query and its corrected spellings. */
    private val searchGapMs: Long = SEARCH_GAP_MS,
) {
    private val _options = MutableStateFlow(initialOptions)
    val options: StateFlow<RouteOptions> = _options
    private var routeJob: Job? = null
    private var routeGeneration = 0L
    private data class DetourRequest(val stop: Place, val final: Place, val from: GeoPoint, val generation: Long)

    private val _ui = MutableStateFlow(NavUi())
    val ui: StateFlow<NavUi> = _ui

    private val _fuel = MutableStateFlow(FuelUi())
    val fuel: StateFlow<FuelUi> = _fuel

    private var searchJob: Job? = null
    private var fuelJob: Job? = null
    private var trackJob: Job? = null
    private var progressIndex = 0
    private var offRouteFixes = 0
    private val spoken = HashSet<Int>()
    /** "Re-routing" is announced once per time off the road, and a failed attempt waits before the next one. */
    private var rerouteSpoken = false
    private var nextRerouteAtMs = 0L

    private fun here(): GeoPoint? =
        location.value.takeIf { it.fixAtMs > 0 }?.let { GeoPoint(it.lat, it.lon) }

    fun onQueryChange(query: String) {
        _ui.update { it.copy(query = query, error = null, suggestion = null, resultsFor = null) }
    }

    private class SearchOutcome(val places: List<Place>, val resultsFor: String? = null, val suggestion: String? = null, val failed: Boolean = false)

    private fun knownNames(): List<String> = (knownPlaces().map { it.name } + PlaceQuery.WELL_KNOWN).distinct()

    /**
     * The typed query first; when it finds nothing, very close saved or recent places, then the two or three best
     * corrected spellings one second apart, and as a last resort a "did you mean" name. Results are only listed:
     * a route starts when the rider picks one.
     */
    private suspend fun resolve(query: String): SearchOutcome {
        val first = repo.search(query, here())
        val firstPlaces = first.getOrElse { return SearchOutcome(emptyList(), failed = true) }
        if (firstPlaces.isNotEmpty()) return SearchOutcome(firstPlaces)
        val local = PlaceQuery.strongLocal(query, knownPlaces())
        for (candidate in PlaceQuery.candidates(query, knownNames()).take(3)) {
            delay(searchGapMs)
            val places = repo.search(candidate, here()).getOrElse { return SearchOutcome(local, failed = local.isEmpty()) }
            if (places.isNotEmpty()) return SearchOutcome(local + places, resultsFor = candidate)
        }
        val suggestion = PlaceQuery.suggest(query, knownNames()).firstOrNull()?.name
        return SearchOutcome(local, suggestion = suggestion.takeIf { local.isEmpty() })
    }

    /** Runs on explicit submit only: Nominatim's usage policy forbids search-as-you-type. */
    fun search() {
        val query = _ui.value.query.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        _ui.update { it.copy(searching = true, results = emptyList(), error = null) }
        searchJob = scope.launch {
            val outcome = resolve(query)
            _ui.update {
                when {
                    outcome.failed -> it.copy(searching = false, error = text(R.string.search_failed))
                    else -> it.copy(
                        results = outcome.places, searching = false, resultsFor = outcome.resultsFor, suggestion = outcome.suggestion,
                        error = if (outcome.places.isEmpty()) text(R.string.no_results) else null,
                    )
                }
            }
        }
    }

    /** A spot picked on the map: routed at once under a generic name, renamed when its address arrives. */
    fun selectPoint(point: GeoPoint) {
        selectPlace(Place(text(R.string.selected_place), "", point))
        scope.launch {
            repo.reverse(point).onSuccess { named ->
                if (named == null) return@onSuccess
                _ui.update { if (it.destination?.point == point) it.copy(destination = named, query = named.name) else it }
            }
        }
    }

    fun selectPlace(place: Place) {
        rememberChosen(place)
        val request = preparePlaceRoute(place) ?: return
        routeJob = scope.launch { calculatePlaceRoute(place, request.first, request.second) }
    }

    private fun rememberChosen(place: Place) {
        if (place.name != text(R.string.selected_place)) onPlaceChosen(place)
    }

    private fun preparePlaceRoute(place: Place): Pair<GeoPoint, Long>? {
        searchJob?.cancel()
        closeFuel()
        val generation = invalidateRoute()
        val from = here()
        if (from == null) {
            _ui.update { it.copy(loading = false, error = text(R.string.gps_waiting)) }
            return null
        }
        _ui.update {
            it.copy(destination = place, finalDestination = null, route = null, query = place.name,
                results = emptyList(), loading = true, error = null)
        }
        return from to generation
    }

    private suspend fun calculatePlaceRoute(place: Place, from: GeoPoint, generation: Long): Boolean {
        val result = try {
            routeResult(from, place.point)
        } catch (cancelled: CancellationException) {
            if (generation == routeGeneration) {
                _ui.update { it.copy(loading = false, destination = null, route = null) }
            }
            throw cancelled
        }
        if (generation != routeGeneration) return false
        if (result == null) {
            _ui.update { it.copy(loading = false, destination = null, route = null, error = text(R.string.route_failed)) }
            return false
        }
        return result.fold(
            onSuccess = { route ->
                if (generation != routeGeneration) return@fold false
                _ui.update { it.copy(route = route, loading = false, error = null) }
                true
            },
            onFailure = {
                if (generation == routeGeneration) {
                    _ui.update { it.copy(loading = false, destination = null, route = null, error = text(R.string.route_failed)) }
                }
                false
            },
        )
    }

    private suspend fun routeResult(
        from: GeoPoint,
        to: GeoPoint,
        options: RouteOptions = _options.value,
    ): Result<Route>? = withTimeoutOrNull(routeTimeoutMs) {
        try {
            repo.route(from, to, options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun invalidateRoute(): Long {
        routeGeneration++
        routeJob?.cancel()
        routeJob = null
        return routeGeneration
    }

    /**
     * Avoid-highways / avoid-tolls. While a route is only being previewed it is recomputed at once;
     * during guidance the new choice applies to the next re-route.
     */
    fun setOptions(options: RouteOptions) {
        if (options == _options.value) return
        _options.value = options
        onOptionsChanged(options)
        val state = _ui.value
        val dest = state.destination
        val from = here()
        if (dest == null || from == null || state.route == null || state.navigating) return
        _ui.update { it.copy(loading = true, error = null) }
        val generation = invalidateRoute()
        routeJob = scope.launch {
            val result = routeResult(from, dest.point, options)
            if (generation != routeGeneration) return@launch
            result?.onSuccess { route -> _ui.update { it.copy(route = route, loading = false) } }
                ?.onFailure { _ui.update { s -> s.copy(loading = false, error = text(R.string.route_failed)) } }
                ?: _ui.update { it.copy(loading = false, error = text(R.string.route_failed)) }
        }
    }

    /**
     * The vehicle changed. A previewed route is recomputed at once; during guidance the route is
     * re-planned from where the rider is, since the old one was made for another kind of road user.
     */
    fun setProfile(profile: VehicleProfile) {
        val before = _options.value
        if (before.profile == profile) return
        val guiding = _ui.value.navigating
        closeFuel()
        setOptions(before.copy(profile = profile))
        if (guiding) here()?.let(::reroute)
    }

    fun startNavigation() {
        val route = _ui.value.route ?: return
        begin(route, 0)
    }

    private fun begin(route: Route, startIndex: Int) {
        progressIndex = startIndex
        offRouteFixes = 0
        spoken.clear()
        val next = route.maneuvers.indexOfFirst { it.beginIndex > startIndex }.takeIf { it >= 0 } ?: route.maneuvers.lastIndex
        val remaining = (route.distanceM - route.cumulative[startIndex]).coerceAtLeast(0.0)
        _ui.update {
            it.copy(navigating = true, arrived = false, maneuverIndex = next.coerceAtLeast(0),
                remainingM = remaining.toInt(), remainingS = (route.durationS * remaining / route.distanceM).toInt(),
                termination = NavigationTermination.NONE)
        }
        if (startIndex == 0) route.maneuvers.firstOrNull()?.let { voice.speak(it.verbal) }
        trackJob?.cancel()
        trackJob = scope.launch {
            try {
                locationUpdates.collect(::onFix)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_ui.value.navigating) {
                    voice.stop()
                    _ui.update {
                        it.copy(navigating = false, rerouting = false, error = text(R.string.voice_navigation_tracking_failed),
                            termination = NavigationTermination.TRACKING_ERROR)
                    }
                }
            }
        }
    }

    fun stopNavigation() {
        trackJob?.cancel()
        // A route still being computed (re-route, stop detour) must not come back to life after "Finish".
        invalidateRoute()
        fuelJob?.cancel()
        voice.stop()
        _ui.value = NavUi(termination = if (_ui.value.navigating) NavigationTermination.USER_CANCELLED else NavigationTermination.NONE)
        _fuel.value = FuelUi()
    }

    // ---- fuel -------------------------------------------------------------------------------

    /** Fuel stations along the road ahead while guiding, otherwise around the rider. */
    fun findFuel() {
        val from = here()
        if (from == null) {
            _fuel.value = FuelUi(visible = true, error = text(R.string.gps_waiting))
            return
        }
        val state = _ui.value
        val route = state.route.takeIf { state.navigating }
        val sources = _options.value.profile.config.stops
        fuelJob?.cancel()
        _fuel.value = FuelUi(visible = true, loading = true, alongRoute = route != null)
        fuelJob = scope.launch {
            val result = if (route != null) {
                fuelFinder.along(FuelMath.samplePoints(route.shape, route.cumulative, progressIndex), from, sources)
            } else {
                fuelFinder.near(from, sources)
            }
            result
                .onSuccess { list ->
                    _fuel.update {
                        it.copy(loading = false, stations = list, error = if (list.isEmpty()) text(R.string.fuel_none) else null)
                    }
                }
                .onFailure { _fuel.update { it.copy(loading = false, error = text(R.string.fuel_failed)) } }
        }
    }

    fun closeFuel() {
        fuelJob?.cancel()
        _fuel.value = FuelUi()
    }

    /** Turns the trip into "fuel stop first, then the original destination". */
    fun detourVia(stop: Place) {
        val request = prepareDetour(stop) ?: return
        routeJob = scope.launch { calculateDetour(request) }
    }

    private fun prepareDetour(stop: Place): DetourRequest? {
        val state = _ui.value
        val from = here() ?: return null
        val final = state.finalDestination ?: state.destination ?: return null
        closeFuel()
        val generation = invalidateRoute()
        _ui.update { it.copy(rerouting = true) }
        return DetourRequest(stop, final, from, generation)
    }

    private suspend fun calculateDetour(request: DetourRequest): Boolean {
        val result = try {
            routeResult(request.from, request.stop.point)
        } catch (cancelled: CancellationException) {
            if (request.generation == routeGeneration) _ui.update { it.copy(rerouting = false) }
            throw cancelled
        }
        if (request.generation != routeGeneration) return false
        if (result == null || result.isFailure) {
            _ui.update { it.copy(rerouting = false, loading = false, error = text(R.string.route_failed)) }
            return false
        }
        val route = result.getOrThrow()
        progressIndex = 0
        offRouteFixes = 0
        spoken.clear()
        _ui.update {
            it.copy(destination = request.stop, finalDestination = request.final, route = route, rerouting = false,
                maneuverIndex = 1.coerceAtMost(route.maneuvers.lastIndex),
                remainingM = route.distanceM.toInt(), remainingS = route.durationS.toInt())
        }
        route.maneuvers.firstOrNull()?.let { voice.speak(it.verbal) }
        return true
    }

    /** At the fuel stop: route on to the original destination and keep guiding. */
    fun continueToFinal() {
        val final = _ui.value.finalDestination ?: return
        val from = here() ?: return
        _ui.update { it.copy(loading = true, arrived = false) }
        val generation = invalidateRoute()
        routeJob = scope.launch {
            val result = routeResult(from, final.point)
            if (generation != routeGeneration) return@launch
            (result ?: Result.failure(IllegalStateException("routing timeout")))
                .onSuccess { route ->
                    _ui.update { it.copy(destination = final, finalDestination = null, route = route, loading = false) }
                    startNavigation()
                }
                .onFailure { _ui.update { s -> s.copy(loading = false, arrived = false, error = text(R.string.route_failed)) } }
        }
    }

    // ---- guidance ---------------------------------------------------------------------------

    private fun onFix(fix: LocationState) {
        val state = _ui.value
        val route = state.route ?: return
        if (state.navigating && fix.locationPermissionGranted == false) {
            voice.stop()
            _ui.update {
                it.copy(navigating = false, rerouting = false, error = text(R.string.voice_navigation_permission_revoked),
                    termination = NavigationTermination.LOCATION_PERMISSION_REVOKED)
            }
            trackJob?.cancel()
            return
        }
        if (state.navigating && fix.gpsProviderEnabled == false) {
            voice.stop()
            _ui.update {
                it.copy(navigating = false, rerouting = false, error = text(R.string.voice_navigation_location_disabled),
                    termination = NavigationTermination.LOCATION_DISABLED)
            }
            trackJob?.cancel()
            return
        }
        if (!state.navigating || fix.fixAtMs == 0L || route.shape.size < 2) return
        val p = GeoPoint(fix.lat, fix.lon)

        // Match to the nearest segment within a window ahead of the last known position.
        var bestIdx = progressIndex
        var best = Geo.Projection(Double.MAX_VALUE, 0.0)
        val end = minOf(route.shape.size - 2, progressIndex + MATCH_WINDOW)
        for (i in progressIndex..end) {
            val proj = Geo.project(p, route.shape[i], route.shape[i + 1])
            if (proj.distanceM < best.distanceM) {
                best = proj
                bestIdx = i
            }
        }

        if (best.distanceM > OFF_ROUTE_M) {
            offRouteFixes++
            if (offRouteFixes >= OFF_ROUTE_FIXES && !state.rerouting && fix.fixAtMs >= nextRerouteAtMs) {
                reroute(p)
            }
            return
        }
        offRouteFixes = 0
        rerouteSpoken = false
        progressIndex = bestIdx

        val travelled = route.cumulative[bestIdx] +
            best.fraction * (route.cumulative[bestIdx + 1] - route.cumulative[bestIdx])
        val remaining = (route.distanceM - travelled).coerceAtLeast(0.0)

        if (remaining <= ARRIVAL_M) {
            voice.speak(text(if (state.finalDestination != null) R.string.stop_arrived else R.string.arrived))
            trackJob?.cancel()
            invalidateRoute()
            _ui.update { it.copy(navigating = false, arrived = true, remainingM = 0, remainingS = 0, distToNextM = 0, termination = NavigationTermination.ARRIVED) }
            return
        }

        val next = route.maneuvers.indexOfFirst { it.beginIndex > bestIdx }
            .takeIf { it >= 0 } ?: route.maneuvers.lastIndex
        val maneuver = route.maneuvers[next]
        val distToNext = (route.cumulative[maneuver.beginIndex.coerceAtMost(route.shape.lastIndex)] - travelled)
            .coerceAtLeast(0.0)

        if (distToNext < ALERT_M && maneuver.alert.isNotBlank() && spoken.add(next * 10)) {
            if (distToNext > PRE_M + 50) voice.speak(maneuver.alert)
        }
        if (distToNext < PRE_M && spoken.add(next * 10 + 1)) voice.speak(maneuver.verbal)

        _ui.update {
            it.copy(
                maneuverIndex = next,
                distToNextM = distToNext.toInt(),
                remainingM = remaining.toInt(),
                remainingS = (route.durationS * remaining / route.distanceM).toInt(),
            )
        }
    }

    private fun reroute(from: GeoPoint) {
        val dest = _ui.value.destination ?: return
        _ui.update { it.copy(rerouting = true) }
        if (!rerouteSpoken) {
            rerouteSpoken = true
            voice.speak(text(R.string.rerouting_voice))
        }
        val generation = invalidateRoute()
        routeJob = scope.launch {
            val result = routeResult(from, dest.point)
            if (generation != routeGeneration) return@launch
            (result ?: Result.failure(IllegalStateException("routing timeout")))
                .onSuccess { route ->
                    progressIndex = 0
                    offRouteFixes = 0
                    nextRerouteAtMs = 0L
                    spoken.clear()
                    _ui.update { it.copy(route = route, rerouting = false) }
                }
                .onFailure {
                    offRouteFixes = 0
                    // Offline or the server is busy: try again in a few seconds, not on every fourth fix.
                    nextRerouteAtMs = location.value.fixAtMs + REROUTE_RETRY_MS
                    _ui.update { it.copy(rerouting = false) }
                }
        }
    }

    fun shutdown() {
        trackJob?.cancel()
        fuelJob?.cancel()
        invalidateRoute()
        voice.shutdown()
    }

    companion object {
        private const val ROUTE_TIMEOUT_MS = 30_000L
        private const val SEARCH_GAP_MS = 1_000L
        const val MATCH_WINDOW = 80
        const val OFF_ROUTE_M = 60.0
        const val OFF_ROUTE_FIXES = 4
        const val ARRIVAL_M = 25.0
        const val ALERT_M = 600.0
        const val PRE_M = 150.0
        const val REROUTE_RETRY_MS = 10_000L
    }
}

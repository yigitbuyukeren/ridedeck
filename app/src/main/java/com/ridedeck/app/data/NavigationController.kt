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
    /** Intermediate stops in order, between the start and [destination]; the first [stopsPassed] are behind the rider. */
    val stops: List<Place> = emptyList(),
    val stopsPassed: Int = 0,
    val route: Route? = null,
    /** While previewing: every route offered to this place (best first), including [route]; empty or one when there is no choice. */
    val choices: List<Route> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val navigating: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val maneuverIndex: Int = 0,
    /** Matched position on [route]: segment index and fraction along it; the map greys out what lies behind. */
    val progressIndex: Int = 0,
    val progressFraction: Double = 0.0,
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
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val beforeStart: () -> Boolean = { true },
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
        location.value.takeIf { it.hasFreshFix(nowMs()) }?.let { GeoPoint(it.lat, it.lon) }

    fun onQueryChange(query: String) {
        searchJob?.cancel()
        _ui.update { it.copy(query = query, searching = false, error = null, suggestion = null, resultsFor = null) }
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

    /** After a process kill: routes to the saved destination as a preview ("Start" resumes guidance); a saved fuel stop is not re-planned. */
    fun restoreTrip(destination: Place, finalDestination: Place?) {
        val target = finalDestination ?: destination
        val request = preparePlaceRoute(target) ?: return
        routeJob = scope.launch { calculatePlaceRoute(target, request.first, request.second) }
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
        trackJob?.cancel()
        voice.stop()
        _ui.update {
            it.copy(destination = place, finalDestination = null, stops = emptyList(), stopsPassed = 0, route = null, choices = emptyList(), query = place.name,
                results = emptyList(), searching = false, loading = true, error = null,
                navigating = false, rerouting = false, arrived = false, termination = NavigationTermination.NONE)
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
                _ui.update { it.copy(route = route, choices = emptyList(), loading = false, error = null) }
                loadChoices(generation, from, place.point, route)
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
        stops: List<Place> = emptyList(),
    ): Result<Route>? = withTimeoutOrNull(routeTimeoutMs) {
        try {
            val trip = if (stops.isEmpty()) repo.route(from, to, options)
            else repo.routeVia(listOf(from) + stops.map { it.point } + to, options)
            trip.mapCatching { route ->
                require(route.isUsable()) { "Invalid route geometry or maneuvers" }
                route
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    /** Asks for alternative routes after the main one is already on screen; a late or failed reply changes nothing. */
    private fun loadChoices(generation: Long, from: GeoPoint, to: GeoPoint, main: Route) {
        if (_ui.value.stops.isNotEmpty()) return
        scope.launch {
            val options = _options.value
            val found = try {
                repo.alternatives(from, to, options).getOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@launch
            val extra = found.filter { it.isUsable() && !it.sameAs(main) }.take(2)
            if (generation != routeGeneration || extra.isEmpty()) return@launch
            _ui.update { if (it.route === main && !it.navigating) it.copy(choices = listOf(main) + extra) else it }
        }
    }

    /** Most intermediate stops one trip may have. */
    private val maxStops = 3

    /** Adds an intermediate stop before the destination, in the preview or while guiding (the route is re-planned from here). */
    fun addStop(place: Place) {
        val state = _ui.value
        if (state.destination == null || state.stops.size - state.stopsPassed >= maxStops) return
        closeFuel()
        searchJob?.cancel()
        applyStops(state.stops.drop(state.stopsPassed) + place)
    }

    /** Removes the [index]th stop still ahead. */
    fun removeStop(index: Int) {
        val ahead = _ui.value.stops.drop(_ui.value.stopsPassed)
        if (index !in ahead.indices) return
        applyStops(ahead.filterIndexed { i, _ -> i != index })
    }

    private fun applyStops(newStops: List<Place>) {
        val state = _ui.value
        val dest = state.destination ?: return
        val from = here()
        if (from == null) {
            _ui.update { it.copy(error = text(R.string.gps_waiting)) }
            return
        }
        val guiding = state.navigating
        val previous = state
        val generation = invalidateRoute()
        _ui.update {
            it.copy(stops = newStops, stopsPassed = 0, choices = emptyList(), error = null,
                loading = !guiding, rerouting = guiding)
        }
        routeJob = scope.launch {
            val result = try {
                routeResult(from, dest.point, stops = newStops)
            } catch (cancelled: CancellationException) {
                if (generation == routeGeneration) _ui.update { it.copy(loading = false, rerouting = false) }
                throw cancelled
            }
            if (generation != routeGeneration) return@launch
            val route = result?.getOrNull()
            if (route == null) {
                // Keep the trip as it was.
                _ui.update { it.copy(stops = previous.stops, stopsPassed = previous.stopsPassed, loading = false, rerouting = false, error = text(R.string.route_failed)) }
                return@launch
            }
            if (guiding) {
                progressIndex = 0
                offRouteFixes = 0
                spoken.clear()
            }
            _ui.update { it.copy(route = route, loading = false, rerouting = false, progressIndex = 0, progressFraction = 0.0) }
        }
    }

    /** Picks one of the previewed alternatives. */
    fun chooseRoute(index: Int) {
        _ui.update {
            val picked = it.choices.getOrNull(index) ?: return@update it
            if (it.navigating) it else it.copy(route = picked)
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
        if (dest == null || from == null || (state.route == null && !state.loading) || state.navigating) return
        _ui.update { it.copy(loading = true, route = null, error = null) }
        val generation = invalidateRoute()
        routeJob = scope.launch {
            val result = routeResult(from, dest.point, options)
            if (generation != routeGeneration) return@launch
            result?.onSuccess { route ->
                _ui.update { it.copy(route = route, choices = emptyList(), loading = false) }
                loadChoices(generation, from, dest.point, route)
            }
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
        if (_ui.value.navigating || _ui.value.loading || _ui.value.rerouting) return
        val route = _ui.value.route ?: return
        if (here() == null) {
            _ui.update { it.copy(error = text(R.string.gps_waiting)) }
            return
        }
        if (!beforeStart()) {
            _ui.update { it.copy(error = text(R.string.ride_start_error)) }
            return
        }
        _ui.update { it.copy(choices = emptyList()) }
        begin(route, 0)
    }

    private fun begin(route: Route, startIndex: Int) {
        progressIndex = startIndex
        offRouteFixes = 0
        spoken.clear()
        val next = route.maneuvers.indexOfFirst { it.beginIndex > startIndex }.takeIf { it >= 0 } ?: route.maneuvers.lastIndex
        val remaining = (route.distanceM - route.cumulative[startIndex]).coerceAtLeast(0.0)
        val remainingS = route.remainingTimeS(route.cumulative[startIndex]).toInt()
        _ui.update {
            it.copy(navigating = true, arrived = false, maneuverIndex = next.coerceAtLeast(0),
                progressIndex = startIndex, progressFraction = 0.0,
                remainingM = remaining.toInt(), remainingS = remainingS,
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
                    invalidateRoute()
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
        searchJob?.cancel()
        trackJob?.cancel()
        // A route still being computed (re-route, stop detour) must not come back to life after "Finish".
        invalidateRoute()
        fuelJob?.cancel()
        voice.stop()
        _ui.value = NavUi(termination = NavigationTermination.USER_CANCELLED)
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
                progressIndex = 0, progressFraction = 0.0,
                maneuverIndex = 1.coerceAtMost(route.maneuvers.lastIndex),
                remainingM = route.distanceM.toInt(), remainingS = route.durationS.toInt())
        }
        route.maneuvers.firstOrNull()?.let { voice.speak(it.verbal) }
        return true
    }

    /** At the fuel stop: route on to the original destination and keep guiding. */
    fun continueToFinal() {
        if (_ui.value.loading || _ui.value.navigating) return
        val final = _ui.value.finalDestination ?: return
        val from = here() ?: return
        _ui.update { it.copy(loading = true, error = null) }
        val generation = invalidateRoute()
        routeJob = scope.launch {
            val result = routeResult(from, final.point)
            if (generation != routeGeneration) return@launch
            (result ?: Result.failure(IllegalStateException("routing timeout")))
                .onSuccess { route ->
                    _ui.update { it.copy(destination = final, finalDestination = null, route = route, loading = false,
                        arrived = false, termination = NavigationTermination.NONE) }
                    startNavigation()
                }
                .onFailure { _ui.update { s -> s.copy(loading = false, error = text(R.string.route_failed)) } }
        }
    }

    // ---- guidance ---------------------------------------------------------------------------

    private fun onFix(fix: LocationState) {
        val state = _ui.value
        val route = state.route ?: return
        if (state.navigating && fix.locationPermissionGranted == false) {
            invalidateRoute()
            voice.stop()
            _ui.update {
                it.copy(navigating = false, rerouting = false, error = text(R.string.voice_navigation_permission_revoked),
                    termination = NavigationTermination.LOCATION_PERMISSION_REVOKED)
            }
            trackJob?.cancel()
            return
        }
        if (state.navigating && fix.gpsProviderEnabled == false) {
            invalidateRoute()
            voice.stop()
            _ui.update {
                it.copy(navigating = false, rerouting = false, error = text(R.string.voice_navigation_location_disabled),
                    termination = NavigationTermination.LOCATION_DISABLED)
            }
            trackJob?.cancel()
            return
        }
        if (!state.navigating || !fix.hasFreshFix(nowMs()) || route.shape.size < 2) return
        val p = GeoPoint(fix.lat, fix.lon)

        // Match to the best segment near the last known position, preferring the direction of travel.
        val speedMps = fix.speedKmh / 3.6
        val match = RouteMatcher.match(route.shape, p, progressIndex, MATCH_WINDOW, fix.bearing?.toDouble(), speedMps)
        val bestIdx = match.index
        val best = match.projection

        if (best.distanceM > RouteMatcher.offRouteM(speedMps, fix.accuracyM)) {
            offRouteFixes++
            if (offRouteFixes >= RouteMatcher.offRouteFixes(speedMps) && !state.rerouting && fix.fixAtMs >= nextRerouteAtMs) {
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

        if (distToNext < PromptSchedule.alertM(speedMps) && maneuver.alert.isNotBlank() && spoken.add(next * 10)) {
            if (PromptSchedule.alertWorthIt(distToNext, speedMps)) voice.speak(maneuver.alert)
        }
        if (distToNext < PromptSchedule.nearM(speedMps) && spoken.add(next * 10 + 1)) voice.speak(
            if (maneuver.isStop) text(R.string.stop_arrived) else withFollowUp(route, next)
        )

        _ui.update {
            it.copy(
                maneuverIndex = next,
                progressIndex = bestIdx,
                progressFraction = best.fraction,
                stopsPassed = it.stopsPassed.coerceAtLeast(route.stopIndices.count { s -> s <= bestIdx }),
                distToNextM = distToNext.toInt(),
                remainingM = remaining.toInt(),
                remainingS = route.remainingTimeS(travelled).toInt(),
            )
        }
    }

    /** "Turn right now, then left": a second turn closer than [THEN_M] is announced together with the first. */
    private fun withFollowUp(route: Route, index: Int): String {
        val m = route.maneuvers[index]
        val following = route.maneuvers.getOrNull(index + 1) ?: return m.verbal
        if (m.lengthM >= THEN_M || following.type in ARRIVE_TYPES || following.verbal.isBlank()) return m.verbal
        return "${m.verbal.trimEnd('.', ' ')}, ${text(R.string.then_voice)} ${following.verbal.trimEnd('.', ' ')}"
    }

    private fun reroute(from: GeoPoint) {
        val dest = _ui.value.destination ?: return
        _ui.update { it.copy(rerouting = true) }
        if (!rerouteSpoken) {
            rerouteSpoken = true
            voice.speak(text(R.string.rerouting_voice))
        }
        val generation = invalidateRoute()
        val ahead = _ui.value.stops.drop(_ui.value.stopsPassed)
        routeJob = scope.launch {
            val result = routeResult(from, dest.point, stops = ahead)
            if (generation != routeGeneration) return@launch
            (result ?: Result.failure(IllegalStateException("routing timeout")))
                .onSuccess { route ->
                    progressIndex = 0
                    offRouteFixes = 0
                    nextRerouteAtMs = 0L
                    spoken.clear()
                    _ui.update { it.copy(route = route, stops = ahead, stopsPassed = 0, rerouting = false, progressIndex = 0, progressFraction = 0.0) }
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
        searchJob?.cancel()
        trackJob?.cancel()
        fuelJob?.cancel()
        invalidateRoute()
        voice.shutdown()
    }

    companion object {
        private const val ROUTE_TIMEOUT_MS = 30_000L
        private const val SEARCH_GAP_MS = 1_000L
        const val MATCH_WINDOW = 80
        const val ARRIVAL_M = 25.0
        const val THEN_M = 250.0
        private val ARRIVE_TYPES = 4..7
        const val REROUTE_RETRY_MS = 10_000L
    }
}

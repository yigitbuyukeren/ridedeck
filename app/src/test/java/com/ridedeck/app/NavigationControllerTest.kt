package com.ridedeck.app

import com.ridedeck.app.data.FuelFinder
import com.ridedeck.app.data.StopKind
import com.ridedeck.app.data.StopSource
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.NavigationTermination
import com.ridedeck.app.data.NavigationRideLifecycle
import com.ridedeck.app.data.RideSessionController
import com.ridedeck.app.data.RidePhase
import com.ridedeck.app.data.RideFailure
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val STEP = 0.0009 // about 100 m of latitude

/** A straight road north: depart at 0, turn at point 15, arrive at point 20 (2 km). */
private fun straightRoute(from: GeoPoint = GeoPoint(41.0, 29.0), points: Int = 21, turnAt: Int = 15): Route {
    val shape = (0 until points).map { GeoPoint(from.lat + it * STEP, from.lon) }
    val maneuvers = listOf(
        Maneuver(1, "Depart", "", "Head north", 1500.0, 0),
        Maneuver(10, "Turn right", "Turn right ahead", "Turn right now", 500.0, turnAt),
        Maneuver(4, "Arrive", "", "You have arrived", 0.0, points - 1),
    )
    val length = (points - 1) * 100.0
    return Route(shape, maneuvers, length, length / 10)
}

private class FakeRouting(private val routes: ArrayDeque<Route>) : Routing {
    val requests = mutableListOf<Pair<GeoPoint, GeoPoint>>()
    val optionsSeen = mutableListOf<RouteOptions>()
    var routeHandler: (suspend (GeoPoint, GeoPoint) -> Result<Route>)? = null
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
        requests += from to to
        optionsSeen += options
        routeHandler?.let { return it(from, to) }
        return Result.success(routes.removeFirstOrNull() ?: straightRoute(from))
    }
    var searchResults: List<Place> = emptyList()
    var searchHandler: (suspend (String) -> Result<List<Place>>)? = null
    val searches = mutableListOf<String>()
    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> {
        searches += query
        searchHandler?.let { return it(query) }
        return Result.success(searchResults)
    }
    var address: Place? = null
    override suspend fun reverse(point: GeoPoint): Result<Place?> = Result.success(address)
}

private class RecordingSpeaker : Speaker {
    val said = mutableListOf<String>()
    override fun speak(text: String) { said += text }
    override fun stop() {}
    override fun shutdown() {}
}

private class FakeFuel : FuelFinder {
    var nearCalls = 0
    var alongCalls = 0
    var alongPoints = 0
    var sourcesSeen: List<StopSource> = emptyList()
    private val station = FuelStation(Place("Opet", "", GeoPoint(41.05, 29.0)), 300)
    override suspend fun near(here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> { nearCalls++; sourcesSeen = sources; return Result.success(listOf(station)) }
    override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> {
        alongCalls++; sourcesSeen = sources; alongPoints = route.size; return Result.success(listOf(station))
    }
}

class NavigationControllerTest {
    private val start = GeoPoint(41.0, 29.0)
    private val dest = Place("Dest", "", GeoPoint(41.018, 29.0))
    private val text = { id: Int -> "t$id" }

    private lateinit var scope: CoroutineScope
    private lateinit var location: MutableStateFlow<LocationState>
    private lateinit var speaker: RecordingSpeaker
    private lateinit var routing: FakeRouting
    private lateinit var fuel: FakeFuel
    private lateinit var nav: NavigationController
    private var clock = 1L

    private fun fixAt(p: GeoPoint) = LocationState(speedKmh = 50, fixAtMs = ++clock, lat = p.lat, lon = p.lon)

    private fun drive(index: Int) {
        location.value = fixAt(GeoPoint(start.lat + index * STEP, start.lon))
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        location = MutableStateFlow(fixAt(start))
        speaker = RecordingSpeaker()
        routing = FakeRouting(ArrayDeque(listOf(straightRoute())))
        fuel = FakeFuel()
        nav = NavigationController(text, scope, location, speaker, routing, fuel, nowMs = { clock })
    }

    @After
    fun tearDown() { nav.shutdown(); scope.cancel() }

    private fun startGuidance() {
        nav.selectPlace(dest)
        nav.startNavigation()
    }

    @Test
    fun selectingAPlaceRoutesFromTheCurrentFix() {
        nav.selectPlace(dest)
        assertNotNull(nav.ui.value.route)
        assertEquals(start to dest.point, routing.requests.single())
        assertFalse(nav.ui.value.navigating)
    }

    @Test
    fun aPickedPointIsRenamedWhenItsAddressArrives() {
        routing.address = Place("Cadde 12", "Fatih, İstanbul", dest.point)
        nav.selectPoint(dest.point)
        assertEquals("Cadde 12", nav.ui.value.destination?.name)
        assertEquals("Cadde 12", nav.ui.value.query)
        assertNotNull(nav.ui.value.route)
    }

    @Test
    fun aPickedPointWithoutAnAddressKeepsTheGenericName() {
        nav.selectPoint(dest.point)
        assertEquals("t${R.string.selected_place}", nav.ui.value.destination?.name)
    }

    @Test
    fun changingTheOptionsInThePreviewRoutesAgainWithThem() {
        val saved = mutableListOf<RouteOptions>()
        nav = NavigationController(text, scope, location, speaker, routing, fuel, nowMs = { clock }, onOptionsChanged = { saved += it })
        nav.selectPlace(dest)
        assertEquals(RouteOptions(), routing.optionsSeen.last())

        nav.setOptions(RouteOptions(avoidHighways = true))
        assertEquals(2, routing.requests.size)
        assertEquals(RouteOptions(avoidHighways = true), routing.optionsSeen.last())
        assertEquals(listOf(RouteOptions(avoidHighways = true)), saved)
        assertFalse(nav.ui.value.loading)

        nav.setOptions(RouteOptions(avoidHighways = true)) // no change, no new request
        assertEquals(2, routing.requests.size)
    }

    @Test
    fun optionsChosenBeforeRoutingAreUsedForTheFirstRoute() {
        nav = NavigationController(text, scope, location, speaker, routing, fuel, nowMs = { clock }, initialOptions = RouteOptions(avoidTolls = true))
        nav.selectPlace(dest)
        assertEquals(RouteOptions(avoidTolls = true), routing.optionsSeen.single())
    }

    @Test
    fun optionsChangedWhileGuidingApplyToTheNextRerouteOnly() {
        startGuidance()
        nav.setOptions(RouteOptions(avoidTolls = true))
        assertEquals(1, routing.requests.size) // guidance is not interrupted
        val off = GeoPoint(start.lat + 5 * STEP, start.lon + 0.003)
        repeat(4) { location.value = fixAt(off) }
        assertEquals(RouteOptions(avoidTolls = true), routing.optionsSeen.last())
    }

    @Test
    fun withoutGpsFixSelectingShowsAnErrorAndDoesNotRoute() {
        location.value = LocationState()
        nav.selectPlace(dest)
        assertEquals("t${R.string.gps_waiting}", nav.ui.value.error)
        assertTrue(routing.requests.isEmpty())
    }

    @Test
    fun startingGuidanceSpeaksTheFirstInstruction() {
        startGuidance()
        assertTrue(nav.ui.value.navigating)
        assertEquals(listOf("Head north"), speaker.said)
    }

    @Test
    fun turnIsAnnouncedAt600AndAt150MetresOnlyOnce() {
        startGuidance()
        speaker.said.clear()
        // Turn at point 15 = 1500 m. Point 9 is 600 m away, point 14 is 100 m away.
        for (i in 1..14) drive(i)
        repeat(3) { drive(14) } // more fixes in the same spot must not repeat anything
        assertEquals(listOf("Turn right ahead", "Turn right now"), speaker.said)
    }

    @Test
    fun remainingDistanceAndNextManeuverFollowTheRider() {
        startGuidance()
        drive(10)
        val ui = nav.ui.value
        assertEquals(1, ui.maneuverIndex)
        assertEquals(1000.0, ui.remainingM.toDouble(), 30.0)
        assertEquals(500.0, ui.distToNextM.toDouble(), 30.0)
    }

    @Test
    fun fourFixesOffTheRouteTriggerOneReroute() {
        startGuidance()
        val off = GeoPoint(start.lat + 5 * STEP, start.lon + 0.003) // roughly 250 m east of the road
        repeat(3) { location.value = fixAt(off) }
        assertEquals(1, routing.requests.size)
        location.value = fixAt(off)
        assertEquals(2, routing.requests.size)
        assertEquals(off, routing.requests.last().first)
        assertTrue(speaker.said.contains("t${R.string.rerouting_voice}"))
        assertFalse(nav.ui.value.rerouting)
    }

    @Test
    fun aSingleStrayFixDoesNotReroute() {
        startGuidance()
        location.value = fixAt(GeoPoint(start.lat + 5 * STEP, start.lon + 0.003))
        drive(6)
        drive(7)
        drive(8)
        drive(9)
        assertEquals(1, routing.requests.size)
    }

    @Test
    fun reachingTheEndStopsGuidanceAndSaysArrived() {
        startGuidance()
        for (i in 1..20) drive(i)
        val ui = nav.ui.value
        assertTrue(ui.arrived)
        assertFalse(ui.navigating)
        assertEquals(NavigationTermination.ARRIVED, ui.termination)
        assertEquals("t${R.string.arrived}", speaker.said.last())
    }

    @Test
    fun stoppingResetsEverything() {
        startGuidance()
        nav.stopNavigation()
        assertNull(nav.ui.value.route)
        assertNull(nav.ui.value.destination)
        assertFalse(nav.ui.value.navigating)
        assertEquals(NavigationTermination.USER_CANCELLED, nav.ui.value.termination)
    }

    @Test
    fun locationTrackingFailureStopsGuidanceWithTypedErrorNotArrivalOrCancellation() {
        nav.shutdown()
        nav = NavigationController(
            text, scope, location, speaker, routing, fuel,
            nowMs = { clock },
            locationUpdates = flow { throw IllegalStateException("sensor stream failed") },
        )
        nav.selectPlace(dest)
        nav.startNavigation()
        assertFalse(nav.ui.value.navigating)
        assertFalse(nav.ui.value.arrived)
        assertEquals(NavigationTermination.TRACKING_ERROR, nav.ui.value.termination)
        assertEquals("t${R.string.voice_navigation_tracking_failed}", nav.ui.value.error)
    }

    @Test
    fun gpsProviderDisabledStopsGuidanceWithItsOwnReason() {
        startGuidance()
        location.value = location.value.copy(gpsProviderEnabled = false)
        assertFalse(nav.ui.value.navigating)
        assertFalse(nav.ui.value.arrived)
        assertEquals(NavigationTermination.LOCATION_DISABLED, nav.ui.value.termination)
        assertEquals("t${R.string.voice_navigation_location_disabled}", nav.ui.value.error)
    }

    @Test
    fun locationPermissionRevokedStopsGuidanceWithItsOwnReason() {
        startGuidance()
        location.value = location.value.copy(locationPermissionGranted = false)
        assertFalse(nav.ui.value.navigating)
        assertFalse(nav.ui.value.arrived)
        assertEquals(NavigationTermination.LOCATION_PERMISSION_REVOKED, nav.ui.value.termination)
        assertEquals("t${R.string.voice_navigation_permission_revoked}", nav.ui.value.error)
    }

    @Test
    fun fuelSearchUsesTheRoadAheadWhileGuidingAndTheSurroundingsOtherwise() {
        nav.findFuel()
        assertEquals(1, fuel.nearCalls)
        assertEquals(0, fuel.alongCalls)
        assertFalse(nav.fuel.value.alongRoute)

        startGuidance()
        nav.findFuel()
        assertEquals(1, fuel.alongCalls)
        assertTrue(fuel.alongPoints >= 2)
        assertTrue(nav.fuel.value.alongRoute)
        assertEquals("Opet", nav.fuel.value.stations.single().place.name)
    }

    @Test
    fun theStopSearchFollowsTheVehicle() {
        nav.findFuel()
        assertEquals(listOf(StopKind.FUEL), fuel.sourcesSeen.map { it.kind })
        nav.setProfile(VehicleProfile.CAR)
        nav.findFuel()
        assertEquals(listOf(StopKind.FUEL, StopKind.CHARGE), fuel.sourcesSeen.map { it.kind })
        nav.setProfile(VehicleProfile.BICYCLE)
        nav.findFuel()
        assertTrue(StopKind.WATER in fuel.sourcesSeen.map { it.kind })
    }

    @Test
    fun changingTheVehicleInThePreviewRoutesAgainForIt() {
        nav.selectPlace(dest)
        nav.setProfile(VehicleProfile.BICYCLE)
        assertEquals(2, routing.requests.size)
        assertEquals(VehicleProfile.BICYCLE, routing.optionsSeen.last().profile)
        nav.setProfile(VehicleProfile.BICYCLE) // same vehicle, nothing to redo
        assertEquals(2, routing.requests.size)
    }

    @Test
    fun changingTheVehicleWhileGuidingPlansTheRestOfTheTripForIt() {
        startGuidance()
        drive(3)
        val before = routing.requests.size
        nav.setProfile(VehicleProfile.CAR)
        assertEquals(before + 1, routing.requests.size)
        assertEquals(VehicleProfile.CAR, routing.optionsSeen.last().profile)
        assertTrue(nav.ui.value.navigating)
    }

    @Test
    fun detourSetsTheStopAsDestinationAndRemembersTheFinalOne() {
        startGuidance()
        drive(3)
        val stop = Place("Opet", "", GeoPoint(start.lat + 0.005 + 3 * STEP, start.lon))
        nav.detourVia(stop)
        val ui = nav.ui.value
        assertEquals(stop, ui.destination)
        assertEquals(dest, ui.finalDestination)
        assertTrue(ui.navigating)
        assertEquals(stop.point, routing.requests.last().second)
        assertFalse(nav.fuel.value.visible)
    }

    @Test
    fun arrivingAtTheStopOffersToContinueAndContinuingRoutesToTheFinalDestination() {
        startGuidance()
        drive(3)
        val stop = Place("Opet", "", GeoPoint(start.lat + 3 * STEP + 5 * STEP, start.lon))
        nav.detourVia(stop) // the fake returns a 2 km straight road from the rider; shape ends at point 23
        location.value = fixAt(nav.ui.value.route!!.shape.last())
        assertTrue(nav.ui.value.arrived)
        assertEquals("t${R.string.stop_arrived}", speaker.said.last())

        nav.continueToFinal()
        val ui = nav.ui.value
        assertTrue(ui.navigating)
        assertFalse(ui.arrived)
        assertEquals(dest, ui.destination)
        assertNull(ui.finalDestination)
        assertEquals(dest.point, routing.requests.last().second)
    }

    // ---- typo tolerance ----

    private fun typoNav(known: List<Place> = emptyList(), chosen: MutableList<Place> = mutableListOf()) {
        nav.shutdown()
        nav = NavigationController(text, scope, location, speaker, routing, fuel, nowMs = { clock }, knownPlaces = { known }, onPlaceChosen = { chosen += it }, searchGapMs = 0)
    }

    @Test
    fun aMistypedPlaceFindsResultsThroughACorrectedSpellingWithoutStartingARoute() {
        typoNav()
        val taksim = Place("Taksim Meydanı", "İstanbul", GeoPoint(41.037, 28.985))
        routing.searchHandler = { q -> Result.success(if (q == "Taksim") listOf(taksim) else emptyList()) }
        nav.onQueryChange("taksm")
        nav.search()
        assertEquals(listOf("taksm", "Taksim"), routing.searches)
        assertEquals(listOf(taksim), nav.ui.value.results)
        assertEquals("Taksim", nav.ui.value.resultsFor)
        assertNull(nav.ui.value.error)
        assertTrue(routing.requests.isEmpty())
        assertNull(nav.ui.value.route)
    }

    @Test
    fun anExactHitNeverTriggersExtraSearches() {
        typoNav()
        routing.searchResults = listOf(dest)
        nav.onQueryChange("Dest")
        nav.search()
        assertEquals(listOf("Dest"), routing.searches)
        assertNull(nav.ui.value.resultsFor)
    }

    @Test
    fun nothingFoundAnywhereOffersADidYouMeanWithoutRouting() {
        typoNav()
        nav.onQueryChange("taksm")
        nav.search()
        assertTrue(nav.ui.value.results.isEmpty())
        assertEquals("Taksim", nav.ui.value.suggestion)
        assertEquals("t${R.string.no_results}", nav.ui.value.error)
        assertTrue(routing.requests.isEmpty())
        assertTrue(routing.searches.size in 2..4)
    }

    @Test
    fun anUnrelatedQueryGetsNoSuggestion() {
        typoNav()
        nav.onQueryChange("qwzxv")
        nav.search()
        assertNull(nav.ui.value.suggestion)
        assertEquals("t${R.string.no_results}", nav.ui.value.error)
    }

    @Test
    fun editingTheQueryClearsTheSuggestionAndTheNote() {
        typoNav()
        nav.onQueryChange("taksm")
        nav.search()
        nav.onQueryChange("taksim")
        assertNull(nav.ui.value.suggestion)
        assertNull(nav.ui.value.resultsFor)
    }

    @Test
    fun aVeryCloseSavedPlaceIsListedEvenWhenTheNetworkFindsNothing() {
        val iskele = Place("Kadıköy İskele", "", GeoPoint(40.99, 29.02))
        typoNav(known = listOf(iskele))
        nav.onQueryChange("kadikoy iskele")
        nav.search()
        assertTrue(iskele in nav.ui.value.results)
        assertNull(nav.ui.value.suggestion)
        assertTrue(routing.requests.isEmpty())
    }

    @Test
    fun aFailedSearchDoesNotTryCorrectedSpellings() {
        typoNav()
        routing.searchHandler = { Result.failure(java.io.IOException("offline")) }
        nav.onQueryChange("taksm")
        nav.search()
        assertEquals(listOf("taksm"), routing.searches)
        assertEquals("t${R.string.search_failed}", nav.ui.value.error)
    }

    @Test
    fun chosenPlacesAreReportedButAMapPointIsNot() {
        val chosen = mutableListOf<Place>()
        typoNav(chosen = chosen)
        nav.selectPlace(dest)
        assertEquals(listOf(dest), chosen)
        nav.selectPoint(GeoPoint(41.02, 29.01))
        assertEquals(listOf(dest), chosen)
    }

    @Test fun staleFixCannotStartARouteOrAdvanceGuidance() {
        clock += 6_000
        nav.selectPlace(dest)
        assertTrue(routing.requests.isEmpty())
        assertEquals("t${R.string.gps_waiting}", nav.ui.value.error)
        drive(0)
        startGuidance()
        val remaining = nav.ui.value.remainingM
        location.value = location.value.copy(fixAtMs = clock - 6_000, lat = dest.point.lat)
        assertTrue(nav.ui.value.navigating)
        assertFalse(nav.ui.value.arrived)
        assertEquals(remaining, nav.ui.value.remainingM)
    }

    @Test fun selectingNewDestinationDuringGuidanceReturnsToPreview() {
        startGuidance()
        drive(10)
        speaker.said.clear()
        nav.selectPlace(Place("Other", "", GeoPoint(41.03, 29.0)))
        assertFalse(nav.ui.value.navigating)
        drive(14)
        assertTrue(speaker.said.isEmpty())
        nav.startNavigation()
        assertTrue(nav.ui.value.navigating)
    }

    @Test fun invalidRoutingRepliesBecomeErrorsInsteadOfCrashingGuidance() {
        val valid = straightRoute()
        for (bad in listOf(valid.copy(shape = emptyList()), valid.copy(maneuvers = emptyList()),
            valid.copy(distanceM = Double.NaN), valid.copy(maneuvers = listOf(valid.maneuvers[0].copy(beginIndex = 999))))) {
            routing.routeHandler = { _, _ -> Result.success(bad) }
            nav.selectPlace(dest)
            nav.startNavigation()
            assertNull(nav.ui.value.route)
            assertFalse(nav.ui.value.navigating)
            assertEquals("t${R.string.route_failed}", nav.ui.value.error)
        }
    }

    @Test fun providerLossCancelsAnInFlightDetour() = runBlocking {
        startGuidance()
        val result = CompletableDeferred<Result<Route>>()
        routing.routeHandler = { _, _ -> result.await() }
        nav.detourVia(Place("Stop", "", GeoPoint(41.01, 29.0)))
        assertTrue(nav.ui.value.rerouting)
        location.value = location.value.copy(gpsProviderEnabled = false)
        result.complete(Result.success(straightRoute()))
        assertFalse(nav.ui.value.navigating)
        assertFalse(nav.ui.value.rerouting)
        assertEquals(dest, nav.ui.value.destination)
        assertNull(nav.ui.value.finalDestination)
        assertEquals(NavigationTermination.LOCATION_DISABLED, nav.ui.value.termination)
    }

    @Test fun editingQueryCancelsTheOldSearchBeforeItCanPublish() = runBlocking {
        val result = CompletableDeferred<Result<List<Place>>>()
        routing.searchHandler = { result.await() }
        nav.onQueryChange("Old place")
        nav.search()
        assertTrue(nav.ui.value.searching)
        nav.onQueryChange("New place")
        result.complete(Result.success(listOf(dest)))
        assertFalse(nav.ui.value.searching)
        assertTrue(nav.ui.value.results.isEmpty())
        assertEquals("New place", nav.ui.value.query)
    }

    @Test fun routePreviewCannotStartGuidanceWithAnExpiredFix() {
        nav.selectPlace(dest)
        clock += 6_000
        nav.startNavigation()
        assertFalse(nav.ui.value.navigating)
        assertTrue(speaker.said.isEmpty())
        assertEquals("t${R.string.gps_waiting}", nav.ui.value.error)
    }

    @Test fun vehicleChangeWhileLoadingReplacesThePendingRouteRequest() = runBlocking {
        val first = CompletableDeferred<Result<Route>>()
        routing.routeHandler = { _, _ -> first.await() }
        nav.selectPlace(dest)
        routing.routeHandler = { from, _ -> Result.success(straightRoute(from)) }
        nav.setProfile(VehicleProfile.BICYCLE)
        assertEquals(VehicleProfile.BICYCLE, routing.optionsSeen.last().profile)
        assertEquals(2, routing.requests.size)
        assertFalse(nav.ui.value.loading)
        assertNotNull(nav.ui.value.route)
        first.complete(Result.success(straightRoute()))
        assertEquals(VehicleProfile.BICYCLE, nav.options.value.profile)
    }

    @Test fun failedOptionChangeCannotStartTheOldRouteWithDifferentOptions() {
        nav.selectPlace(dest)
        routing.routeHandler = { _, _ -> Result.failure(java.io.IOException("offline")) }
        nav.setOptions(RouteOptions(avoidTolls = true))
        assertNull(nav.ui.value.route)
        nav.startNavigation()
        assertFalse(nav.ui.value.navigating)
        assertEquals("t${R.string.route_failed}", nav.ui.value.error)
    }

    @Test fun rejectedForegroundTrackingDoesNotStartGuidanceOrSpeakDeparture() {
        nav.shutdown()
        nav = NavigationController(text, scope, location, speaker, routing, fuel, nowMs = { clock }, beforeStart = { false })
        nav.selectPlace(dest)
        nav.startNavigation()
        assertFalse(nav.ui.value.navigating)
        assertTrue(speaker.said.isEmpty())
        assertEquals("t${R.string.ride_start_error}", nav.ui.value.error)
    }

    @Test fun repeatedStartDoesNotResetProgressOrRepeatDeparture() {
        startGuidance()
        drive(10)
        val remaining = nav.ui.value.remainingM
        val said = speaker.said.toList()
        nav.startNavigation()
        assertEquals(remaining, nav.ui.value.remainingM)
        assertEquals(said, speaker.said)
    }

    @Test fun finishingAlsoCancelsAPendingSearch() = runBlocking {
        val result = CompletableDeferred<Result<List<Place>>>()
        routing.searchHandler = { result.await() }
        nav.onQueryChange("Old place")
        nav.search()
        nav.stopNavigation()
        result.complete(Result.success(listOf(dest)))
        assertTrue(nav.ui.value.results.isEmpty())
        assertFalse(nav.ui.value.searching)
    }

    @Test fun failedContinuationKeepsTheStopAndOffersRetry() {
        startGuidance()
        nav.detourVia(Place("Stop", "", dest.point))
        location.value = fixAt(nav.ui.value.route!!.shape.last())
        assertTrue(nav.ui.value.arrived)
        routing.routeHandler = { _, _ -> Result.failure(java.io.IOException("offline")) }
        nav.continueToFinal()
        assertTrue(nav.ui.value.arrived)
        assertEquals(dest, nav.ui.value.finalDestination)
        assertFalse(nav.ui.value.loading)
    }

    private var keepsRideAlive = false
    private var token = 0L
    private fun bindRide(): RideSessionController {
        val ride = RideSessionController(scope, { null }, { token = it }, {}, {}, { true }, {}, { clock })
        NavigationRideLifecycle(scope, nav, ride) { keepsRideAlive = it }
        assertTrue(ride.requestStart(true))
        assertTrue(ride.serviceStarted(token))
        return ride
    }

    @Test fun finalArrivalEndsTheRideWithoutClearingTheArrivalSummary() {
        val ride = bindRide()
        startGuidance()
        assertTrue(keepsRideAlive)
        drive(20)
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertTrue(nav.ui.value.arrived)
        assertEquals(NavigationTermination.ARRIVED, nav.ui.value.termination)
        assertFalse(keepsRideAlive)
    }

    @Test fun plannedStopAndFailedContinuationKeepTheRideAliveUntilUserFinishes() {
        val ride = bindRide()
        startGuidance()
        nav.detourVia(Place("Stop", "", dest.point))
        location.value = fixAt(nav.ui.value.route!!.shape.last())
        assertTrue(keepsRideAlive)
        assertEquals(RidePhase.ACTIVE, ride.state.value.phase)
        routing.routeHandler = { _, _ -> Result.failure(java.io.IOException("offline")) }
        nav.continueToFinal()
        assertTrue(keepsRideAlive)
        assertEquals(RidePhase.ACTIVE, ride.state.value.phase)
        nav.stopNavigation()
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertFalse(keepsRideAlive)
    }

    @Test fun stopArrivalAndContinuationDoNotEndTheRideBetweenLegs() {
        val ride = bindRide()
        startGuidance()
        nav.detourVia(Place("Stop", "", dest.point))
        location.value = fixAt(nav.ui.value.route!!.shape.last())
        nav.continueToFinal()
        assertTrue(nav.ui.value.navigating)
        assertEquals(RidePhase.ACTIVE, ride.state.value.phase)
        assertTrue(keepsRideAlive)
    }

    @Test fun notificationStopAtAStopCancelsThePendingContinuationAndLateReply() = runBlocking {
        val ride = bindRide()
        startGuidance()
        nav.detourVia(Place("Stop", "", dest.point))
        location.value = fixAt(nav.ui.value.route!!.shape.last())
        val result = CompletableDeferred<Result<Route>>()
        routing.routeHandler = { _, _ -> result.await() }
        nav.continueToFinal()
        assertTrue(nav.ui.value.loading)
        val announcements = speaker.said.toList()
        ride.stop()
        result.complete(Result.success(straightRoute()))
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertFalse(nav.ui.value.navigating)
        assertFalse(nav.ui.value.loading)
        assertNull(nav.ui.value.finalDestination)
        assertEquals(announcements, speaker.said)
        assertFalse(keepsRideAlive)
    }

    @Test fun serviceDestructionStopsGuidanceAndFreshGpsCannotReviveIt() {
        val ride = bindRide()
        startGuidance()
        ride.serviceDestroyed(token)
        drive(10)
        assertEquals(RideFailure.INTERRUPTED, ride.state.value.failure)
        assertFalse(nav.ui.value.navigating)
        assertFalse(keepsRideAlive)
    }

    @Test fun permissionLossEndsTheRideAndPreservesTheTypedNavigationError() {
        val ride = bindRide()
        startGuidance()
        location.value = location.value.copy(locationPermissionGranted = false)
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertEquals(RideFailure.PERMISSION, ride.state.value.failure)
        assertEquals(NavigationTermination.LOCATION_PERMISSION_REVOKED, nav.ui.value.termination)
        assertEquals("t${R.string.voice_navigation_permission_revoked}", nav.ui.value.error)
    }

    @Test fun oldCancellationUiDoesNotStopANewManualRideWhenQueryChanges() {
        val ride = bindRide()
        startGuidance()
        nav.stopNavigation()
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertTrue(ride.requestStart(true))
        assertTrue(ride.serviceStarted(token))
        nav.onQueryChange("Another destination")
        assertEquals(RidePhase.ACTIVE, ride.state.value.phase)
    }

    @Test fun permissionLossDuringADetourPreservesTheErrorAndReleasesGuidance() {
        val ride = bindRide()
        startGuidance()
        nav.detourVia(Place("Stop", "", dest.point))
        assertNotNull(nav.ui.value.finalDestination)
        location.value = location.value.copy(locationPermissionGranted = false)
        assertEquals(RidePhase.IDLE, ride.state.value.phase)
        assertEquals(NavigationTermination.LOCATION_PERMISSION_REVOKED, nav.ui.value.termination)
        assertFalse(keepsRideAlive)
    }
}

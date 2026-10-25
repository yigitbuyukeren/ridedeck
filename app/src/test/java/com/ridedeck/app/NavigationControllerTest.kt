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
        nav = NavigationController(text, scope, location, speaker, routing, fuel)
    }

    @After
    fun tearDown() = nav.shutdown()

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
    fun voiceDestinationWaitsForRoutingFailureInsteadOfReportingSelectionAsSuccess() = runBlocking {
        routing.searchResults = listOf(dest)
        routing.routeHandler = { _, _ -> Result.failure(IllegalStateException("offline")) }

        assertFalse(nav.goToFirstMatch(listOf("evim")))
        assertNull(nav.ui.value.route)
        assertFalse(nav.ui.value.loading)
        assertEquals("t${com.ridedeck.app.R.string.route_failed}", nav.ui.value.error)
    }

    @Test
    fun voiceDestinationTimeoutIsFailureAndNotARoutePreview() = runBlocking {
        nav.shutdown()
        nav = NavigationController(text, scope, location, speaker, routing, fuel, routeTimeoutMs = 15)
        routing.searchResults = listOf(dest)
        routing.routeHandler = { _, _ -> delay(200); Result.success(straightRoute()) }

        assertFalse(nav.goToFirstMatch(listOf("evim")))
        assertNull(nav.ui.value.route)
        assertFalse(nav.ui.value.loading)
    }

    @Test
    fun aLateVoiceRouteCannotReplaceANewerDestination() = runBlocking {
        val slow = CompletableDeferred<Result<Route>>()
        val newer = Place("New", "", GeoPoint(41.03, 29.0))
        routing.routeHandler = { _, to ->
            if (to == dest.point) slow.await() else Result.success(straightRoute(to))
        }
        val oldRequest = async(start = CoroutineStart.UNDISPATCHED) { nav.selectPlaceAndRoute(dest) }
        nav.selectPlace(newer)
        assertEquals(newer, nav.ui.value.destination)
        assertTrue(nav.ui.value.route != null)
        slow.complete(Result.success(straightRoute()))
        assertFalse(oldRequest.await())
        assertEquals(newer, nav.ui.value.destination)
        assertEquals(newer.point, nav.ui.value.route?.shape?.firstOrNull())
    }

    @Test
    fun cancelledVoiceRouteClearsPendingStateAndNeverReportsCompletion() = runBlocking {
        val gate = CompletableDeferred<Result<Route>>()
        routing.routeHandler = { _, _ -> gate.await() }
        val request = launch(start = CoroutineStart.UNDISPATCHED) { nav.selectPlaceAndRoute(dest) }
        assertTrue(nav.ui.value.loading)

        request.cancelAndJoin()

        assertFalse(nav.ui.value.loading)
        assertNull(nav.ui.value.destination)
        assertNull(nav.ui.value.route)
    }

    @Test
    fun stoppingNavigationWhileVoiceSearchIsPendingPreventsLateRouteStart() = runBlocking {
        val gate = CompletableDeferred<Result<List<Place>>>()
        routing.searchHandler = { gate.await() }
        val search = async(start = CoroutineStart.UNDISPATCHED) { nav.goToFirstMatch(listOf("ev")) }
        nav.stopNavigation()
        gate.complete(Result.success(listOf(dest)))

        assertFalse(search.await())
        assertNull(nav.ui.value.destination)
        assertNull(nav.ui.value.route)
    }

    @Test
    fun failedFuelStopRouteKeepsTheExistingDestinationAndReturnsNoSelection() = runBlocking {
        nav.selectPlace(dest)
        nav.searchFuelForVoice()
        routing.routeHandler = { _, _ -> Result.failure(IllegalStateException("offline")) }

        assertNull(nav.selectFuelVoiceResult(0))
        assertEquals(dest, nav.ui.value.destination)
        assertNotNull(nav.ui.value.route)
        assertFalse(nav.ui.value.loading)
        assertEquals("t${com.ridedeck.app.R.string.route_failed}", nav.ui.value.error)
    }

    @Test
    fun voiceSearchRetainsAlternativesAndStaleOrdinalsCannotSelect() = runBlocking {
        val first = Place("Kafe A", "", GeoPoint(41.01, 29.0))
        val second = Place("Kafe B", "", GeoPoint(41.02, 29.0))
        routing.searchResults = listOf(first, second)

        assertEquals(listOf("Kafe A", "Kafe B"), nav.searchVoicePlaces(listOf("kafe")).map { it.name })
        assertEquals(listOf(first, second), nav.ui.value.results)
        assertEquals("Kafe B", nav.selectVoiceResult(1)?.name)
        assertEquals(second, nav.ui.value.destination)
        assertNull(nav.selectVoiceResult(5))
    }

    @Test
    fun voiceFuelSearchRetainsStopsAndRoutesTheRequestedOne() = runBlocking {
        val stops = nav.searchFuelForVoice()
        assertEquals("Opet", stops.single().name)
        assertEquals("Opet", nav.selectFuelVoiceResult(0)?.name)
        assertEquals("Opet", nav.ui.value.destination?.name)
        assertNull(nav.selectFuelVoiceResult(1))
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
        nav = NavigationController(text, scope, location, speaker, routing, fuel, onOptionsChanged = { saved += it })
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
        nav = NavigationController(text, scope, location, speaker, routing, fuel, initialOptions = RouteOptions(avoidTolls = true))
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
}

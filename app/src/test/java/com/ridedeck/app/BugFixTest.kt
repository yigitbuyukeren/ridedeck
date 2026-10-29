package com.ridedeck.app

import com.ridedeck.app.data.CrashDetector
import com.ridedeck.app.data.FuelFinder
import com.ridedeck.app.data.FuelMath
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.StopKind
import com.ridedeck.app.data.StopSource
import com.ridedeck.app.data.TripMath
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.util.simplifyMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STEP = 0.0009 // about 100 m of latitude

private fun road(from: GeoPoint = GeoPoint(41.0, 29.0)): Route {
    val shape = (0 until 21).map { GeoPoint(from.lat + it * STEP, from.lon) }
    val maneuvers = listOf(
        Maneuver(1, "Depart", "", "Head north", 2000.0, 0),
        Maneuver(4, "Arrive", "", "You have arrived", 0.0, 20),
    )
    return Route(shape, maneuvers, 2000.0, 200.0)
}

/** First request succeeds at once; later ones wait on [gate] or fail, as the test wants. */
private class ScriptedRouting : Routing {
    var gate: CompletableDeferred<Result<Route>>? = null
    var failLater = false
    var calls = 0
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
        calls++
        if (calls == 1) return Result.success(road(from))
        if (failLater) return Result.failure(RuntimeException("offline"))
        return gate!!.await()
    }
    override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
}

private class Words : Speaker {
    val said = mutableListOf<String>()
    override fun speak(text: String) { said += text }
    override fun stop() {}
    override fun shutdown() {}
}

private object NoStops : FuelFinder {
    override suspend fun near(here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
    override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>) = Result.success(emptyList<FuelStation>())
}

class BugFixTest {
    private var clock = 1L
    private fun fix(p: GeoPoint, at: Long = clock + 1): LocationState {
        clock = at
        return LocationState(speedKmh = 30, fixAtMs = at, lat = p.lat, lon = p.lon)
    }
    private val off = GeoPoint(41.0045, 29.003) // about 250 m beside the road

    private fun guiding(routing: ScriptedRouting, words: Words = Words()): Pair<NavigationController, MutableStateFlow<LocationState>> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val loc = MutableStateFlow(fix(GeoPoint(41.0, 29.0)))
        val nav = NavigationController({ "t$it" }, scope, loc, words, routing, NoStops, nowMs = { clock })
        nav.selectPlace(Place("Dest", "", GeoPoint(41.018, 29.0)))
        nav.startNavigation()
        return nav to loc
    }

    // ---- re-routing ---------------------------------------------------------------------------

    @Test
    fun aRouteStillBeingComputedDoesNotComeBackAfterFinish() {
        val routing = ScriptedRouting().also { it.gate = CompletableDeferred() }
        val (nav, loc) = guiding(routing)
        repeat(4) { loc.value = fix(off) }          // off the road: a new route is requested and awaited
        nav.stopNavigation()                         // the rider presses Finish meanwhile
        routing.gate!!.complete(Result.success(road(off)))
        assertNull(nav.ui.value.route)
        assertNull(nav.ui.value.destination)
        assertFalse(nav.ui.value.navigating)
    }

    @Test
    fun offlineRerouteSaysItOnceAndWaitsBeforeTryingAgain() {
        val routing = ScriptedRouting().also { it.failLater = true }
        val words = Words()
        val (_, loc) = guiding(routing, words)
        repeat(30) { loc.value = fix(off) }
        assertEquals(1, words.said.count { it == "t${R.string.rerouting_voice}" })
        assertEquals(2, routing.calls) // the first route and a single failed re-route

        // Ten seconds on, it tries again, without announcing it a second time.
        val later = clock + 11_000
        repeat(4) { loc.value = fix(off, at = later + it) }
        assertEquals(3, routing.calls)
        assertEquals(1, words.said.count { it == "t${R.string.rerouting_voice}" })
    }

    // ---- trips --------------------------------------------------------------------------------

    @Test
    fun standingStillWithAWobblingGpsAddsNoDistance() {
        val points = ArrayList<TripPoint>()
        var t = 0L
        var lat = 41.0
        repeat(120) { points += TripPoint(t, lat, 29.0, 36); lat += 10.0 / 111_000; t += 1000 } // 2 min at 36 km/h = 1200 m
        repeat(180) { i -> points += TripPoint(t, lat + (if (i % 2 == 0) 4.0 else -4.0) / 111_000, 29.0, 0); t += 1000 }
        val stats = TripMath.compute(points)
        assertEquals(1200.0, stats.distanceM, 60.0)
        assertEquals(36.0, stats.avgKmh.toDouble(), 3.0)
    }

    // ---- crash detection ----------------------------------------------------------------------

    private class Sample(val g: Float, val kmh: Int, val vec: Triple<Float, Float, Float>)

    /** Rides, takes a hit, brakes to a stop and waits for 10 s with the phone lying as [rest]. */
    private fun alarm(detector: CrashDetector, hit: Float, rest: Triple<Float, Float, Float>): Boolean {
        val up = Triple(0f, 0f, 1f)
        val script = ArrayList<Sample>()
        repeat(30) { script += Sample(1f, 25, up) }
        script += Sample(hit, 25, Triple(0.3f, 0.3f, hit))
        repeat(15) { script += Sample(1f, 12, up) }
        repeat(120) { script += Sample(1f, 0, rest) }
        var t = 0L
        for (s in script) {
            if (detector.onSample(t, s.g, s.kmh, s.vec.first, s.vec.second, s.vec.third)) return true
            t += 100
        }
        return false
    }

    private fun twoWheels() = VehicleProfile.MOTORCYCLE.config.let { c -> CrashDetector({ c.crashImpactG }, { c.crashMovingKmh }, { c.crashNeedsTilt }) }

    @Test
    fun aPotholeFollowedByARedLightIsNotACrash() {
        assertFalse(alarm(twoWheels(), hit = 4.5f, rest = Triple(0f, 0f, 1f)))
    }

    @Test
    fun aHitThatLeavesThePhoneTippedOverIsACrash() {
        assertTrue(alarm(twoWheels(), hit = 4.5f, rest = Triple(1f, 0f, 0f)))
    }

    @Test
    fun aHitTwiceTheThresholdIsACrashEvenIfThePhoneStayedUpright() {
        assertTrue(alarm(twoWheels(), hit = 8.5f, rest = Triple(0f, 0f, 1f)))
    }

    @Test
    fun aCarDoesNotNeedTheTilt() {
        val car = VehicleProfile.CAR.config
        val detector = CrashDetector({ car.crashImpactG }, { car.crashMovingKmh }, { car.crashNeedsTilt })
        // Same story at car speeds: a hard hit, a stop, stillness; the cabin phone does not turn over.
        val up = Triple(0f, 0f, 1f)
        var t = 0L
        var fired = false
        fun feed(g: Float, kmh: Int) { if (detector.onSample(t, g, kmh, up.first, up.second, up.third)) fired = true; t += 100 }
        repeat(30) { feed(1f, 60) }
        feed(6f, 60)
        repeat(15) { feed(1f, 10) }
        repeat(120) { feed(1f, 0) }
        assertTrue(fired)
    }

    @Test
    fun missingTiltEvidenceDoesNotConfirmAnOrdinaryTwoWheelImpact() {
        val d = twoWheels()
        var t = 0L
        var fired = false
        fun feed(g: Float, kmh: Int) { if (d.onSample(t, g, kmh)) fired = true; t += 100 }
        repeat(30) { feed(1f, 30) }
        feed(4.5f, 30)
        repeat(15) { feed(1f, 10) }
        repeat(120) { feed(1f, 0) }
        assertFalse(fired)
    }

    // ---- messages and stops -------------------------------------------------------------------

    @Test
    fun theDegreeSignSurvivesSpeech() {
        assertEquals("Hava 15°C olacak", simplifyMessage("Hava 15°C olacak", "link"))
    }

    @Test
    fun emojiStillGoAway() {
        assertEquals("Selam", simplifyMessage("Selam 😀👍🏽❤️", "link"))
    }

    @Test
    fun eachKindOfStopGetsItsOwnCapInTheOverpassQuery() {
        val here = GeoPoint(41.0, 29.0)
        val many = FuelMath.nearQuery(here, VehicleProfile.BICYCLE.config.stops)
        assertEquals(3, Regex("out center 40;").findAll(many).count())
        assertEquals(1, Regex("out center 80;").findAll(FuelMath.nearQuery(here)).count())
    }

    @Test
    fun anOverpassAnswerAddsTheKindsNominatimLeftOut() {
        val here = GeoPoint(41.0, 29.0)
        val water = (1..12).map { FuelStation(Place("w$it", "", GeoPoint(41.0 + it * 0.0001, 29.0)), it * 10, StopKind.WATER) }
        val shop = FuelStation(Place("shop", "", GeoPoint(41.01, 29.0)), 1100, StopKind.BIKE_SHOP)
        val merged = FuelMath.combine(water, listOf(shop, water.first()))
        assertTrue(merged.any { it.kind == StopKind.BIKE_SHOP })
        assertEquals(1, merged.count { it.place.name == "w1" })
        assertTrue(merged.count { it.kind == StopKind.WATER } <= 8)
    }

    @Test
    fun theCarDoesNotNeedAFallToCountACrashButTwoWheelsDo() {
        assertFalse(VehicleProfile.CAR.config.crashNeedsTilt)
        assertTrue(VehicleProfile.MOTORCYCLE.config.crashNeedsTilt)
        assertTrue(VehicleProfile.BICYCLE.config.crashNeedsTilt)
    }
}

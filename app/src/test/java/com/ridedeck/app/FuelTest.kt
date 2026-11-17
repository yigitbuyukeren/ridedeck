package com.ridedeck.app

import com.ridedeck.app.data.FuelMath
import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.ServiceHttp
import com.ridedeck.app.data.firstAnswerOf
import com.ridedeck.app.data.overpassAttempts
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelTest {
    private val here = GeoPoint(41.0, 29.0)

    @Test
    fun parsesNodesAndWayCentersNearestFirst() {
        val json = """{"elements":[
            {"type":"way","id":2,"center":{"lat":41.02,"lon":29.0},"tags":{"amenity":"fuel","name":"Shell"}},
            {"type":"node","id":1,"lat":41.005,"lon":29.0,"tags":{"amenity":"fuel","brand":"Opet","name":"Opet Merkez","operator":"Alpet"}}
        ]}"""
        val list = FuelMath.parse(json, here)
        assertEquals(listOf("Opet Merkez", "Shell"), list.map { it.place.name })
        assertEquals("Opet", list[0].place.detail)
        assertEquals("", list[1].place.detail)
        assertTrue(list[0].distanceM in 500..600)
    }

    @Test
    fun skipsHotelsTaggedAsFuel() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":41.0,"lon":29.0,"tags":{"amenity":"fuel","tourism":"hotel","name":"Hotel"}},
            {"type":"node","id":2,"lat":41.001,"lon":29.0,"tags":{"amenity":"fuel","name":"BP"}}
        ]}"""
        assertEquals(listOf("BP"), FuelMath.parse(json, here).map { it.place.name })
    }

    @Test
    fun unnamedStationFallsBackToBrandThenBlank() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":41.001,"lon":29.0,"tags":{"amenity":"fuel","brand":"Petrol Ofisi"}},
            {"type":"node","id":2,"lat":41.002,"lon":29.0,"tags":{"amenity":"fuel"}}
        ]}"""
        val list = FuelMath.parse(json, here)
        assertEquals("Petrol Ofisi", list[0].place.name)
        assertEquals("", list[1].place.name)
    }

    @Test
    fun emptyOrMissingElementsGiveNothing() {
        assertTrue(FuelMath.parse("""{"elements":[]}""", here).isEmpty())
        assertTrue(FuelMath.parse("""{}""", here).isEmpty())
    }

    @Test
    fun routeSamplingKeepsEndsAndStaysUnderTheCap() {
        // 100 km straight north in 100 m steps.
        val shape = (0..1000).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        val cumulative = DoubleArray(shape.size).also { c -> for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.distance(shape[i - 1], shape[i]) }
        val points = FuelMath.samplePoints(shape, cumulative, 0)
        assertEquals(shape.first(), points.first())
        assertEquals(shape.last(), points.last())
        assertTrue(points.size <= FuelMath.MAX_ROUTE_POINTS + 1)
    }

    @Test
    fun shortRouteIsSampledFromTheCurrentPosition() {
        val shape = (0..100).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        val cumulative = DoubleArray(shape.size).also { c -> for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.distance(shape[i - 1], shape[i]) }
        val points = FuelMath.samplePoints(shape, cumulative, 90)
        assertEquals(shape[90], points.first())
        assertEquals(shape.last(), points.last())
    }

    @Test
    fun queriesNameTheRadiusAndCoordinates() {
        assertTrue(FuelMath.nearQuery(here).contains("(around:5000,41.00000,29.00000)"))
        assertTrue(FuelMath.routeQuery(listOf(here, GeoPoint(41.1, 29.1))).contains("(around:500,41.00000,29.00000,41.10000,29.10000)"))
    }

    @Test
    fun parsesNominatimFuelResultsNearestFirst() {
        val json = """[
            {"lat":"41.0200","lon":"29.0","name":"Shell","type":"fuel"},
            {"lat":"41.0050","lon":"29.0","name":"Opet","type":"fuel"},
            {"lat":"41.0010","lon":"29.0","name":"Cafe","type":"cafe"}
        ]"""
        assertEquals(listOf("Opet", "Shell"), FuelMath.parseNominatim(json, here).map { it.place.name })
    }

    @Test
    fun nominatimUrlPutsTheBoxInLeftTopRightBottomOrder() {
        val url = FuelMath.nominatimUrl(FuelMath.Box(south = 40.98, west = 28.93, north = 41.04, east = 29.02))
        assertTrue(url.contains("viewbox=28.93000,41.04000,29.02000,40.98000"))
        assertTrue(url.contains("bounded=1"))
    }

    @Test
    fun boundingBoxCoversEveryPointWithTheMargin() {
        val box = FuelMath.boundingBox(listOf(GeoPoint(41.0, 29.0), GeoPoint(41.1, 29.2)), 0.01)
        assertEquals(40.99, box.south, 1e-9)
        assertEquals(28.99, box.west, 1e-9)
        assertEquals(41.11, box.north, 1e-9)
        assertEquals(29.21, box.east, 1e-9)
    }

    @Test
    fun corridorFilterKeepsOnlyStationsBesideTheRoad() {
        val road = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.05, 29.0))
        val beside = com.ridedeck.app.data.FuelStation(com.ridedeck.app.data.Place("Near", "", GeoPoint(41.02, 29.003)), 0)   // about 250 m east
        val far = com.ridedeck.app.data.FuelStation(com.ridedeck.app.data.Place("Far", "", GeoPoint(41.02, 29.02)), 0)        // about 1.7 km east
        assertEquals(listOf("Near"), FuelMath.alongRoute(listOf(beside, far), road).map { it.place.name })
    }

    @Test
    fun overpassUsesAtMostTwoDistinctOperators() {
        val attempts = overpassAttempts()
        assertEquals(2, attempts.size)
        assertEquals(2, attempts.map { it.operator }.distinct().size)
        assertFalse("the same-operator pair must not both be used", attempts.any { it.url.contains("private.coffee") })
    }

    @Test
    fun firstOverpassAnswerWinsAndCancelsTheOtherMirror() = runBlocking {
        val bothStarted = Channel<Unit>(2)
        val releaseFirst = CompletableDeferred<String>()
        val http = object : ServiceHttp {
            val calls = CopyOnWriteArrayList<String>()
            val cancelled = AtomicInteger()

            override suspend fun postForm(
                url: String,
                body: String,
                connectMs: Int,
                readMs: Int,
                headers: Map<String, String>,
            ): String {
                calls += url
                bothStarted.send(Unit)
                try {
                    return if (url.contains("overpass-api.de")) releaseFirst.await() else awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.incrementAndGet()
                    throw e
                }
            }

            override suspend fun get(url: String, connectMs: Int, readMs: Int, headers: Map<String, String>): String =
                error("unused")

            override suspend fun postJson(
                url: String,
                body: String,
                connectMs: Int,
                readMs: Int,
                emptyOn400: Boolean,
                headers: Map<String, String>,
            ): String = error("unused")
        }

        var answer: String? = null
        val job = launch(Dispatchers.Default) {
            answer = withTimeout(5_000) { firstAnswerOf("data=x", overpassAttempts(), http) }
        }
        repeat(2) { withTimeout(5_000) { bothStarted.receive() } }
        releaseFirst.complete("""{"elements":[]}""")
        withTimeout(5_000) { job.join() }

        assertEquals("""{"elements":[]}""", answer)
        assertEquals("at most two operators may be contacted", 2, http.calls.size)
        assertTrue("the slower mirror must be cancelled", http.cancelled.get() >= 1)
    }
}

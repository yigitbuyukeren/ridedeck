package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Http
import com.ridedeck.app.data.ProviderPolicies
import com.ridedeck.app.data.ProviderThrottle
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.RouteRepository
import com.ridedeck.app.data.ServiceHttp
import com.ridedeck.app.data.SpeedLimitRepository
import com.ridedeck.app.data.WeatherRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderThrottleTest {
    @Test
    fun policiesMatchEachPublicProvider() {
        assertEquals(1, ProviderPolicies.forHost("nominatim.openstreetmap.org").maxConcurrent)
        assertEquals(1_000L, ProviderPolicies.forHost("nominatim.openstreetmap.org").minSpacingMs)
        assertEquals(2, ProviderPolicies.forHost("valhalla1.openstreetmap.de").maxConcurrent)
        assertEquals(1_000L, ProviderPolicies.forHost("valhalla1.openstreetmap.de").minSpacingMs)
        assertEquals(2, ProviderPolicies.forHost("overpass-api.de").maxConcurrent)
        assertEquals(0L, ProviderPolicies.forHost("overpass-api.de").minSpacingMs)
        assertEquals(1, ProviderPolicies.forHost("api.open-meteo.com").maxConcurrent)
        assertEquals(1, ProviderPolicies.forHost("unknown.example").maxConcurrent)
    }

    @Test
    fun nominatimStartsAreSpacedByTheFakeClock() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val throttle = ProviderThrottle(
            nowMs = { clock },
            delayMs = { wait -> waits += wait; clock += wait },
        )

        throttle.run("nominatim.openstreetmap.org") {}
        throttle.run("nominatim.openstreetmap.org") {}

        assertEquals(listOf(1_000L), waits)
    }

    @Test
    fun valhallaOverlapsAtMostTwoCalls() = runTest {
        val throttle = ProviderThrottle(nowMs = { 0L }, delayMs = { delay(it) })
        var active = 0
        var peak = 0
        val jobs = (1..6).map {
            launch {
                throttle.run("valhalla1.openstreetmap.de") {
                    active++
                    peak = maxOf(peak, active)
                    delay(1_500)
                    active--
                }
            }
        }
        jobs.joinAll()
        assertEquals(2, peak)
    }

    @Test
    fun cancellingAWaiterReleasesTheNominationSlot() = runTest {
        val throttle = ProviderThrottle(nowMs = { 0L }, delayMs = { delay(it) })
        val holder = launch {
            throttle.run("nominatim.openstreetmap.org") { awaitCancellation() }
        }
        runCurrent()
        holder.cancelAndJoin()

        val result = withTimeout(2_000) {
            throttle.run("nominatim.openstreetmap.org") { "ok" }
        }
        assertEquals("ok", result)
    }

    @Test
    fun cancellingWhileWaitingForSpacingDoesNotHoldAValhallaSlot() = runTest {
        val throttle = ProviderThrottle(nowMs = { 0L }, delayMs = { delay(it) })
        val holder = launch {
            throttle.run("valhalla1.openstreetmap.de") { awaitCancellation() }
        }
        runCurrent()
        val waiting = launch {
            throttle.run("valhalla1.openstreetmap.de") { awaitCancellation() }
        }
        runCurrent()
        waiting.cancelAndJoin()

        val result = withTimeout(3_000) {
            throttle.run("valhalla1.openstreetmap.de") { "ok" }
        }
        assertEquals("ok", result)
        holder.cancelAndJoin()
    }

    @Test
    fun valhallaHeadersAreSentButOtherHostsStayUnchanged() {
        val http = RecordingHttp()
        val route = RouteRepository(http = http)
        val speed = SpeedLimitRepository(http = http)
        val weather = WeatherRepository(http = http)
        val here = GeoPoint(41.0, 29.0)

        runBlocking {
            route.route(here, GeoPoint(41.01, 29.01), RouteOptions())
            speed.limitAt(listOf(here, GeoPoint(41.001, 29.0), GeoPoint(41.002, 29.0)))
            weather.fetch(here)
        }

        val routeCall = http.calls.single { it.url == "https://valhalla1.openstreetmap.de/route" }
        assertEquals("ridedeck", routeCall.headers["X-Client-Id"])
        val trace = http.calls.single { it.url == "https://valhalla1.openstreetmap.de/trace_attributes" }
        assertEquals("ridedeck", trace.headers["X-Client-Id"])
        val forecast = http.calls.single { it.url.startsWith("https://api.open-meteo.com/") }
        assertFalse(forecast.headers.containsKey("X-Client-Id"))

        // The central layer also identifies Valhalla calls that do not pass headers explicitly.
        assertEquals("ridedeck", Http.clientHeaders("https://valhalla1.openstreetmap.de/route")["X-Client-Id"])
        assertTrue(Http.clientHeaders("https://api.open-meteo.com/v1/forecast").isEmpty())
    }

    private class RecordingHttp : ServiceHttp {
        data class Call(val url: String, val headers: Map<String, String>)
        val calls = CopyOnWriteArrayList<Call>()

        private val routeReply =
            """{"trip":{"legs":[{"shape":"_p~iF~ps|U_ulLnnqC","maneuvers":[{"type":1,"instruction":"go","length":1.0,"begin_shape_index":0}]}],"summary":{"length":1.0,"time":10.0}}}"""
        private val weatherReply = """{"current":{"temperature_2m":10.0,"weather_code":0}}"""

        override suspend fun get(url: String, connectMs: Int, readMs: Int, headers: Map<String, String>): String {
            calls += Call(url, headers)
            return if (url.startsWith("https://api.open-meteo.com/")) weatherReply else "{}"
        }

        override suspend fun postJson(
            url: String,
            body: String,
            connectMs: Int,
            readMs: Int,
            emptyOn400: Boolean,
            headers: Map<String, String>,
        ): String {
            calls += Call(url, headers)
            return if (url.endsWith("/route")) routeReply else "{}"
        }

        override suspend fun postForm(
            url: String,
            body: String,
            connectMs: Int,
            readMs: Int,
            headers: Map<String, String>,
        ): String {
            calls += Call(url, headers)
            return "{}"
        }
    }
}

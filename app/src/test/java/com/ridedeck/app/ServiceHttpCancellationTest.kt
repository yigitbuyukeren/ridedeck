package com.ridedeck.app

import com.ridedeck.app.data.FuelMath
import com.ridedeck.app.data.FuelRepository
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.RouteRepository
import com.ridedeck.app.data.ServiceHttp
import com.ridedeck.app.data.SpeedLimitRepository
import com.ridedeck.app.data.WeatherRepository
import com.ridedeck.app.data.WeatherSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback-free checks that the service repositories use the cancellable transport and let
 * cancellation through instead of turning it into a failed [Result] or a provider fallback.
 * The injected transport never opens a socket.
 */
class ServiceHttpCancellationTest {
    private val here = GeoPoint(41.0, 29.0)

    @Test
    fun routeCancellationAbortsTheTransportInsteadOfReturningFailure() = runBlocking {
        val http = BlockingHttp()
        val repo = RouteRepository(http = http)
        var result: Result<Route>? = null
        val job = launch(Dispatchers.Default) {
            result = repo.route(here, GeoPoint(41.01, 29.01), RouteOptions())
        }
        try {
            assertTrue("route request never started", http.started.await(5, TimeUnit.SECONDS))
            val request = http.calls.single()
            assertEquals(Verb.POST_JSON, request.verb)
            assertEquals("https://valhalla1.openstreetmap.de/route", request.url)
            assertEquals(10_000, request.connectMs)
            assertEquals(12_000, request.readMs)
            assertFalse(request.emptyOn400)

            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("transport did not see the cancellation", http.cancelled.await(5, TimeUnit.SECONDS))
            assertNull("cancellation must not become a Result", result)
            assertEquals(1, http.calls.size)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun fuelNearCancellationDoesNotStartTheOverpassFallback() = runBlocking {
        val http = BlockingHttp()
        val repo = FuelRepository(http = http)
        var result: Result<List<FuelStation>>? = null
        val job = launch(Dispatchers.Default) { result = repo.near(here, FuelMath.FUEL_ONLY) }
        try {
            assertTrue("Nominatim request never started", http.started.await(5, TimeUnit.SECONDS))
            val request = http.calls.single()
            assertEquals(Verb.GET, request.verb)
            assertTrue(request.url.startsWith("https://nominatim.openstreetmap.org/search?"))
            assertEquals(10_000, request.connectMs)
            assertEquals(12_000, request.readMs)

            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("transport did not see the cancellation", http.cancelled.await(5, TimeUnit.SECONDS))
            assertNull("cancellation must not become a Result", result)
            assertTrue("Overpass fallback must not start", http.calls.none { it.verb == Verb.POST_FORM })
            assertEquals(1, http.calls.size)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun fuelAlongCancellationDoesNotStartTheNominatimFallback() = runBlocking {
        val http = BlockingHttp()
        val repo = FuelRepository(http = http)
        val route = listOf(here, GeoPoint(41.02, 29.0))
        var result: Result<List<FuelStation>>? = null
        val job = launch(Dispatchers.Default) { result = repo.along(route, here, FuelMath.FUEL_ONLY) }
        try {
            assertTrue("Overpass request never started", http.started.await(5, TimeUnit.SECONDS))
            val request = http.calls.first()
            assertEquals(Verb.POST_FORM, request.verb)
            assertTrue(request.url.contains("overpass"))
            assertEquals(5_000, request.connectMs)
            assertEquals(10_000, request.readMs)

            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("transport did not see the cancellation", http.cancelled.await(5, TimeUnit.SECONDS))
            assertNull("cancellation must not become a Result", result)
            assertTrue("Nominatim fallback must not start", http.calls.none { it.verb == Verb.GET })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun speedLimitCancellationPropagatesInsteadOfAFailedResult() = runBlocking {
        val http = BlockingHttp()
        val repo = SpeedLimitRepository(http = http)
        var result: Result<Int?>? = null
        val job = launch(Dispatchers.Default) { result = repo.limitAt(listOf(here, GeoPoint(41.001, 29.0), GeoPoint(41.002, 29.0))) }
        try {
            assertTrue("speed-limit request never started", http.started.await(5, TimeUnit.SECONDS))
            val request = http.calls.single()
            assertEquals(Verb.POST_JSON, request.verb)
            assertEquals("https://valhalla1.openstreetmap.de/trace_attributes", request.url)
            assertEquals(8_000, request.connectMs)
            assertEquals(10_000, request.readMs)
            assertTrue(request.emptyOn400)

            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("transport did not see the cancellation", http.cancelled.await(5, TimeUnit.SECONDS))
            assertNull("cancellation must not become a Result", result)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun weatherCancellationPropagatesInsteadOfAFailedResult() = runBlocking {
        val http = BlockingHttp()
        val repo = WeatherRepository(http = http)
        var result: Result<WeatherSnapshot>? = null
        val job = launch(Dispatchers.Default) { result = repo.fetch(here) }
        try {
            assertTrue("weather request never started", http.started.await(5, TimeUnit.SECONDS))
            val request = http.calls.single()
            assertEquals(Verb.GET, request.verb)
            assertTrue(request.url.startsWith("https://api.open-meteo.com/v1/forecast?"))
            assertEquals(8_000, request.connectMs)
            assertEquals(10_000, request.readMs)

            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue("transport did not see the cancellation", http.cancelled.await(5, TimeUnit.SECONDS))
            assertNull("cancellation must not become a Result", result)
        } finally {
            job.cancel()
        }
    }
}

private enum class Verb { GET, POST_JSON, POST_FORM }

private data class RecordedRequest(
    val verb: Verb,
    val url: String,
    val body: String?,
    val connectMs: Int,
    val readMs: Int,
    val emptyOn400: Boolean = false,
)

/** Accepts each request, then parks it until the calling coroutine is cancelled. */
private class BlockingHttp : ServiceHttp {
    val calls = CopyOnWriteArrayList<RecordedRequest>()
    val started = CountDownLatch(1)
    val cancelled = CountDownLatch(1)

    override suspend fun get(url: String, connectMs: Int, readMs: Int): String =
        block(RecordedRequest(Verb.GET, url, null, connectMs, readMs))

    override suspend fun postJson(
        url: String,
        body: String,
        connectMs: Int,
        readMs: Int,
        emptyOn400: Boolean,
    ): String = block(RecordedRequest(Verb.POST_JSON, url, body, connectMs, readMs, emptyOn400))

    override suspend fun postForm(url: String, body: String, connectMs: Int, readMs: Int): String =
        block(RecordedRequest(Verb.POST_FORM, url, body, connectMs, readMs))

    private suspend fun block(request: RecordedRequest): String {
        calls += request
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.countDown() }
            started.countDown()
        }
    }
}

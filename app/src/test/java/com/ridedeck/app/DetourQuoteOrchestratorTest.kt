package com.ridedeck.app

import com.ridedeck.app.data.DetourContext
import com.ridedeck.app.data.DetourQuote
import com.ridedeck.app.data.DirectQuoteRequestGate
import com.ridedeck.app.data.BudgetedQuoteRequestGate
import com.ridedeck.app.data.ProviderBudgetPolicy
import com.ridedeck.app.data.ProviderRequestBudget
import com.ridedeck.app.data.QuoteRequestGate
import com.ridedeck.app.data.DetourQuoteCache
import com.ridedeck.app.data.DetourQuoteOrchestrator
import com.ridedeck.app.data.DetourStatus
import com.ridedeck.app.data.DetourUnavailableReason
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetourQuoteOrchestratorTest {
    private val origin = GeoPoint(41.0, 29.0)
    private val final = Place("Final", "", GeoPoint(41.01, 29.01))
    private val station = Place("Fuel", "", GeoPoint(41.005, 29.005))

    @Test fun explicitQuoteUsesOneBaselineAndOneViaInTheCorrectOrder() {
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(routing, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
        val context = context()

        val quote = orchestrator.quoteBlocking(context, station, currentContext = { context })

        assertTrue(quote.available)
        assertEquals(2, routing.calls)
        assertEquals(listOf(origin, final.point), routing.points[0])
        assertEquals(listOf(origin, station.point, final.point), routing.points[1])
    }

    @Test fun sameContextAndStationReusesTheMemoryCacheWithoutAnotherRouteCall() {
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(routing, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
        val context = context()

        assertTrue(orchestrator.quoteBlocking(context, station) { context }.available)
        assertTrue(orchestrator.quoteBlocking(context, station) { context }.available)

        assertEquals(2, routing.calls)
    }

    @Test fun movingOrChangedContextRejectsTheQuoteAndDropsTheInFlightResult() {
        var current: DetourContext? = context()
        val routing = FakeRouting(afterFirstCall = { current = null })
        val orchestrator = DetourQuoteOrchestrator(routing, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)

        val quote = orchestrator.quoteBlocking(current!!, station, currentContext = { current })

        assertEquals(DetourStatus.UNAVAILABLE, quote.status)
        assertEquals(DetourUnavailableReason.STALE_CONTEXT, quote.reason)
        assertEquals(1, routing.calls)
    }

    @Test fun freshFlagDoesNotSurviveAgedFixWhileTheFirstRequestIsInFlight() {
        var wallNow = 10_000L
        val routing = FakeRouting(afterFirstCall = { wallNow = 16_000L })
        val orchestrator = DetourQuoteOrchestrator(
            routing, DetourQuoteCache(),
            cacheNowMs = { 1_000L },
            wallNowMs = { wallNow },
            requestGate = DirectQuoteRequestGate,
        )
        val context = context()

        val quote = orchestrator.quoteBlocking(context, station) { context }

        assertEquals(DetourUnavailableReason.STALE_CONTEXT, quote.reason)
        assertEquals(1, routing.calls)
    }

    @Test fun gpsFreshUpdatesWithoutMovementReuseTheSnapshotQuote() {
        var current = context().copy(origin = GeoPoint(41.0001, 29.0), fixAtMs = 11_000L)
        var wallNow = 11_000L
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(
            routing, DetourQuoteCache(),
            cacheNowMs = { 1_000L },
            wallNowMs = { wallNow },
            requestGate = DirectQuoteRequestGate,
        )

        assertTrue(orchestrator.quoteBlocking(context(), station) { current }.available)
        current = current.copy(origin = GeoPoint(41.00015, 29.0), fixAtMs = 12_000L)
        wallNow = 12_000L
        assertTrue(orchestrator.quoteBlocking(context(), station) { current }.available)

        assertEquals("one baseline + one via only, then snapshot cache", 2, routing.calls)
    }

    @Test fun movingBeyondTheBoundedOriginToleranceInvalidatesTheSnapshot() {
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(
            routing, DetourQuoteCache(),
            cacheNowMs = { 1_000L },
            wallNowMs = { 11_000L },
            requestGate = DirectQuoteRequestGate,
        )
        val current = context().copy(origin = GeoPoint(41.001, 29.0), fixAtMs = 11_000L)

        val quote = orchestrator.quoteBlocking(context(), station) { current }

        assertEquals(DetourUnavailableReason.STALE_CONTEXT, quote.reason)
        assertEquals(0, routing.calls)
    }

    @Test fun providerBudgetKeyIsSharedButEachQuoteRequestIdentityIsDistinct() {
        val gate = RecordingGate()
        val orchestrator = DetourQuoteOrchestrator(
            FakeRouting(), DetourQuoteCache(),
            cacheNowMs = { 1_000L },
            wallNowMs = { 10_000L },
            requestGate = gate,
        )

        assertTrue(orchestrator.quoteBlocking(context(), station) { context() }.available)

        assertEquals(listOf("provider:valhalla", "provider:valhalla"), gate.providerKeys)
        assertEquals(2, gate.requestKeys.distinct().size)
        assertTrue(gate.requestKeys[0].startsWith("quote:baseline"))
        assertTrue(gate.requestKeys[1].startsWith("quote:via"))
    }

    @Test fun quoteCallsUseActualBudgetAdmissionWithFakeClock() {
        var fakeClock = 1_000L
        val gate = BudgetedQuoteRequestGate(
            budget = ProviderRequestBudget(ProviderBudgetPolicy(maxConcurrent = 1, maxQueue = 2, minIntervalMs = 1_000L)),
            nowMs = { fakeClock },
            wait = { fakeClock += it },
        )
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(
            routing, DetourQuoteCache(),
            cacheNowMs = { fakeClock },
            wallNowMs = { 10_000L },
            requestGate = gate,
        )

        val quote = orchestrator.quoteBlocking(context(), station) { context() }

        assertTrue(quote.available)
        assertEquals(2, routing.calls)
        assertEquals(2_000L, fakeClock)
    }

    @Test fun stopLimitRefusesBeforeAnyProviderCallRatherThanDroppingPlannedStops() {
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(routing, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
        val context = context().copy(
            remainingStops = listOf(
                Place("A", "", GeoPoint(41.002, 29.002)),
                Place("B", "", GeoPoint(41.003, 29.003)),
                Place("C", "", GeoPoint(41.004, 29.004)),
            ),
            maxStops = 3,
        )

        val quote = orchestrator.quoteBlocking(context, station) { context }

        assertEquals(DetourUnavailableReason.STOP_LIMIT, quote.reason)
        assertEquals(0, routing.calls)
    }

    @Test fun baselineAndViaFailuresAreDistinctAndBounded() {
        val baseline = FakeRouting(failBaseline = true)
        val baselineQuote = DetourQuoteOrchestrator(baseline, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
            .quoteBlocking(context(), station) { context() }
        assertEquals(DetourUnavailableReason.BASELINE_FAILED, baselineQuote.reason)
        assertEquals(1, baseline.calls)

        val via = FakeRouting(failVia = true)
        val viaQuote = DetourQuoteOrchestrator(via, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
            .quoteBlocking(context(), station) { context() }
        assertEquals(DetourUnavailableReason.VIA_FAILED, viaQuote.reason)
        assertEquals(2, via.calls)
    }

    @Test fun quoteDoesNotStartNavigationOrMutateAnyNavigationState() {
        val routing = FakeRouting()
        val orchestrator = DetourQuoteOrchestrator(routing, DetourQuoteCache(), cacheNowMs = { 1_000L }, wallNowMs = { 10_000L }, requestGate = DirectQuoteRequestGate)
        val context = context()

        val quote = orchestrator.quoteBlocking(context, station) { context }

        assertTrue(quote.available)
        assertEquals(2, routing.calls)
        // The orchestrator exposes no navigation controller and only calls Routing.routeVia.
    }

    private fun context() = DetourContext(
        origin = origin,
        fixAtMs = 10_000L,
        final = final,
        remainingStops = emptyList(),
        options = RouteOptions(),
        headingDeg = 90.0,
        language = "tr",
        miles = false,
        maxStops = 3,
        contextGeneration = 1L,
        freshFix = true,
    )

    private fun DetourQuoteOrchestrator.quoteBlocking(
        context: DetourContext,
        station: Place,
        currentContext: (() -> DetourContext?)? = null,
    ): DetourQuote = runBlocking { quote(context, station, currentContext) }

    private class RecordingGate : QuoteRequestGate {
        val providerKeys = mutableListOf<String>()
        val requestKeys = mutableListOf<String>()

        override suspend fun <T> run(providerKey: String, requestKey: String, block: suspend () -> T): Result<T> {
            providerKeys += providerKey
            requestKeys += requestKey
            return Result.success(block())
        }
    }

    private class FakeRouting(
        private val failBaseline: Boolean = false,
        private val failVia: Boolean = false,
        private val afterFirstCall: (() -> Unit)? = null,
    ) : Routing {
        var calls = 0
            private set
        val points = mutableListOf<List<GeoPoint>>()

        override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> =
            error("not used")

        override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> =
            Result.success(emptyList())

        override suspend fun routeVia(
            points: List<GeoPoint>,
            options: RouteOptions,
            headingDeg: Double?,
        ): Result<Route> {
            calls++
            this.points += points
            afterFirstCall?.takeIf { calls == 1 }?.invoke()
            if (calls == 1 && failBaseline) return Result.failure(IllegalStateException("baseline"))
            if (calls == 2 && failVia) return Result.failure(IllegalStateException("via"))
            val distance = if (points.size == 2) 1_000.0 else 1_200.0
            val stopIndices = (1 until points.size - 1).toList()
            return Result.success(
                Route(
                    shape = points,
                    maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = 100.0)) +
                        stopIndices.map { Maneuver(0, "", "", "", 0.0, it, timeS = 100.0, isStop = true) },
                    distanceM = distance,
                    durationS = 100.0,
                    stopIndices = stopIndices,
                )
            )
        }
    }
}

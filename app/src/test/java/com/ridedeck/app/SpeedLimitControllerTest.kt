package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.SpeedLimitController
import com.ridedeck.app.data.SpeedLimitLookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedLimitControllerTest {
    /** Each call stays pending until the test answers it, so reply order is explicit. */
    private class ControlledLookup : SpeedLimitLookup {
        val pending = mutableListOf<CompletableDeferred<Result<Int?>>>()
        override suspend fun limitAt(trail: List<GeoPoint>): Result<Int?> {
            val reply = CompletableDeferred<Result<Int?>>()
            pending += reply
            return reply.await()
        }
    }

    private class Rig(scope: TestScope) {
        var clock = 1_000_000L
        val location = MutableStateFlow(LocationState())
        val active = MutableStateFlow(true)
        val lookup = ControlledLookup()
        val controller = SpeedLimitController(
            scope.backgroundScope, location, active, lookup, { clock }, {},
        )

        fun fix(atMs: Long, lonOffset: Double) {
            location.value = LocationState(fixAtMs = atMs, speedKmh = 40, lat = 41.0, lon = 29.0 + lonOffset)
        }
    }

    /** Advances the test scheduler and the injected elapsed clock together. */
    private fun TestScope.advanceBoth(rig: Rig, ms: Long) {
        rig.clock += ms
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Three spaced fixes moving fast enough to ask for a limit once, delivered one by one. */
    private fun TestScope.requestLimit(rig: Rig, startMs: Long, baseLon: Double = 0.0) {
        rig.fix(startMs, baseLon)
        runCurrent()
        rig.fix(startMs + 1_000, baseLon + 0.0005)
        runCurrent()
        rig.fix(startMs + 2_000, baseLon + 0.0010)
        runCurrent()
    }

    @Test
    fun lateReplyAfterDeactivationIsIgnored() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        assertEquals(1, rig.lookup.pending.size)

        rig.active.value = false
        runCurrent()
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun oldOverlappingReplyCannotOverwriteTheNewSession() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        val old = rig.lookup.pending[0]

        rig.active.value = false
        runCurrent()
        rig.active.value = true
        runCurrent()
        rig.clock += 1_000
        requestLimit(rig, 20_000, baseLon = 0.0020)
        assertEquals(2, rig.lookup.pending.size)

        val newer = rig.lookup.pending[1]
        newer.complete(Result.success(90))
        runCurrent()
        assertEquals(90, rig.controller.limit.value)

        old.complete(Result.success(50))
        runCurrent()
        assertEquals(90, rig.controller.limit.value)
    }

    @Test
    fun replyOlderThanFreshnessWindowIsIgnored() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        val issuedAt = rig.clock

        rig.clock = issuedAt + 60_001
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun replyAtFreshnessBoundaryIsAccepted() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        val issuedAt = rig.clock

        rig.clock = issuedAt + 60_000
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()

        assertEquals(50, rig.controller.limit.value)
    }

    @Test
    fun negativeElapsedIsRejected() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        val issuedAt = rig.clock

        rig.clock = issuedAt - 1
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun displayedLimitExpiresFromTheRequestFixNotTheReplyFix() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        // The request's own fix was 12_000; an unrelated later fix must not make that road data look fresh.
        rig.fix(72_001, 0.0010)
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun acceptedLimitExpiresWithoutAnotherGpsFix() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        // No further fix arrives; only elapsed time and the scheduled expiry act.
        advanceBoth(rig, 60_001)

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun elapsedClockJumpToOneHourClearsBeforeTheUptimeDeadline() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        // Only one second of scheduler/Handler uptime has passed; a one-shot 60 s delay is still far away.
        advanceBoth(rig, 1_000)
        assertEquals(50, rig.controller.limit.value)
        rig.clock += 60 * 60_000

        // The next short check must use the elapsed clock and clear well before that uptime deadline.
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun negativeExpiryClockHidesTheInvalidReading() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        rig.clock -= 1
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(rig.controller.limit.value)
    }

    @Test
    fun newerResultSurvivesTheOldResultsExpiry() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        advanceBoth(rig, 10_000)
        rig.fix(20_000, 0.0020)
        runCurrent()
        assertEquals(2, rig.lookup.pending.size)
        rig.lookup.pending[1].complete(Result.success(90))
        runCurrent()
        assertEquals(90, rig.controller.limit.value)

        // The first result's 60 s expiry has passed, but the newer result replaced it.
        advanceBoth(rig, 55_000)
        assertEquals(90, rig.controller.limit.value)

        advanceBoth(rig, 6_000)
        assertNull(rig.controller.limit.value)
    }

    @Test
    fun failedLookupDoesNotRefreshTheAgeOfTheDisplayedLimit() = runTest {
        val rig = Rig(this)
        runCurrent()
        requestLimit(rig, 10_000)
        rig.lookup.pending[0].complete(Result.success(50))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        rig.fix(20_000, 0.0020)
        runCurrent()
        assertEquals(2, rig.lookup.pending.size)
        rig.lookup.pending[1].complete(Result.failure(IllegalStateException("lookup failed")))
        runCurrent()
        assertEquals(50, rig.controller.limit.value)

        // Still measured at 12_000, even though a request ran and failed at 20_000.
        rig.fix(72_001, 0.0020)
        runCurrent()
        assertNull(rig.controller.limit.value)
    }
}

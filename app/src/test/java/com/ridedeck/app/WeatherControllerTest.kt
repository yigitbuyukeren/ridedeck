package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.WeatherAlert
import com.ridedeck.app.data.WeatherController
import com.ridedeck.app.data.WeatherLookup
import com.ridedeck.app.data.WeatherSnapshot
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
class WeatherControllerTest {
    /** Each call stays pending until the test answers it, so fetch overlap is explicit. */
    private class ControlledWeather : WeatherLookup {
        val pending = mutableListOf<CompletableDeferred<Result<WeatherSnapshot>>>()
        override suspend fun fetch(at: GeoPoint, nowMs: Long): Result<WeatherSnapshot> {
            val reply = CompletableDeferred<Result<WeatherSnapshot>>()
            pending += reply
            return reply.await()
        }
    }

    private class Rig(scope: TestScope) {
        var clock = 1_000_000L
        val location = MutableStateFlow(LocationState(fixAtMs = 1_000, lat = 41.0, lon = 29.0))
        val enabled = MutableStateFlow(true)
        val lookup = ControlledWeather()
        val alerts = mutableListOf<WeatherAlert>()
        val controller = WeatherController(
            scope.backgroundScope, location, enabled, lookup, { clock },
        ) { alert, _ -> alerts += alert }
    }

    private fun TestScope.tickAfter(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun rainy() = WeatherSnapshot(tempC = 12, code = 61, rainInMin = 10)

    private fun dry() = WeatherSnapshot(tempC = 18, code = 1, rainInMin = null)

    @Test
    fun expiredSnapshotIsClearedWhileAFetchIsStillPending() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        assertEquals(1, rig.lookup.pending.size)

        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()
        assertEquals(10, rig.controller.snapshot.value?.rainInMin)

        // A refresh starts but never answers.
        rig.clock = 1_000_000L + 16 * 60_000L
        tickAfter(5_000)
        assertEquals(2, rig.lookup.pending.size)

        // The tick loop must still expire the old reading even though that fetch is blocked.
        rig.clock = 1_000_000L + WeatherController.SNAPSHOT_TTL_MS + 1
        tickAfter(60_000)

        assertNull(rig.controller.snapshot.value)
        assertEquals(2, rig.lookup.pending.size)
    }

    @Test
    fun failedRefreshCannotKeepAnOldSnapshotBeyondItsLifetime() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        rig.lookup.pending[0].complete(Result.success(dry()))
        runCurrent()
        assertEquals(18, rig.controller.snapshot.value?.tempC)

        rig.clock = 1_000_000L + 16 * 60_000L
        tickAfter(5_000)
        assertEquals(2, rig.lookup.pending.size)
        rig.lookup.pending[1].complete(Result.failure(IllegalStateException("weather failed")))
        runCurrent()
        assertEquals(18, rig.controller.snapshot.value?.tempC)

        rig.clock = 1_000_000L + WeatherController.SNAPSHOT_TTL_MS + 1
        tickAfter(60_000)

        assertNull(rig.controller.snapshot.value)
    }

    @Test
    fun disablingClearsImmediatelyAndRejectsThePendingReply() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        assertEquals(1, rig.lookup.pending.size)

        rig.enabled.value = false
        runCurrent()
        assertNull(rig.controller.snapshot.value)

        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()
        assertNull(rig.controller.snapshot.value)
        assertEquals(emptyList<WeatherAlert>(), rig.alerts)
    }

    @Test
    fun enablingAfterDisableFetchesAFreshReadingAndAlertsAgain() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()
        assertEquals(listOf(WeatherAlert.RAIN), rig.alerts)

        rig.enabled.value = false
        runCurrent()
        rig.enabled.value = true
        runCurrent()
        tickAfter(5_000)
        assertEquals(2, rig.lookup.pending.size)

        rig.lookup.pending[1].complete(Result.success(rainy()))
        runCurrent()
        assertEquals(10, rig.controller.snapshot.value?.rainInMin)
        assertEquals(listOf(WeatherAlert.RAIN, WeatherAlert.RAIN), rig.alerts)
    }

    @Test
    fun repeatedRefreshesOfTheSameRainSpellAlertOnlyOnce() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()

        rig.clock = 1_000_000L + 16 * 60_000L
        tickAfter(5_000)
        assertEquals(2, rig.lookup.pending.size)
        rig.lookup.pending[1].complete(Result.success(rainy()))
        runCurrent()

        assertEquals(listOf(WeatherAlert.RAIN), rig.alerts)
    }

    @Test
    fun negativeClockExpiresTheDisplayedSnapshot() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        rig.lookup.pending[0].complete(Result.success(dry()))
        runCurrent()

        rig.clock -= 1
        tickAfter(5_000)

        assertNull(rig.controller.snapshot.value)
    }

    @Test
    fun negativeRequestAgeIsRejected() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        assertEquals(1, rig.lookup.pending.size)

        rig.clock -= 1
        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()

        assertNull(rig.controller.snapshot.value)
        assertEquals(emptyList<WeatherAlert>(), rig.alerts)
    }

    @Test
    fun replyOlderThanSnapshotLifetimeIsRejectedWithoutAnAlert() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        runCurrent()
        val requestedAt = rig.clock
        assertEquals(1, rig.lookup.pending.size)

        rig.clock = requestedAt + WeatherController.SNAPSHOT_TTL_MS + 1
        rig.lookup.pending[0].complete(Result.success(rainy()))
        runCurrent()

        assertNull(rig.controller.snapshot.value)
        assertEquals(emptyList<WeatherAlert>(), rig.alerts)
    }

    @Test
    fun startIsIdempotent() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        rig.controller.start()
        runCurrent()

        assertEquals(1, rig.lookup.pending.size)
        tickAfter(5_000)
        tickAfter(5_000)
        assertEquals(1, rig.lookup.pending.size)
    }
}

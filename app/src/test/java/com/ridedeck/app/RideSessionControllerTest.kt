package com.ridedeck.app

import com.ridedeck.app.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideSessionControllerTest {
    private class Rig(scope: TestScope) {
        var failure: RideFailure? = null
        var token = 0L
        var launches = 0
        var acquired = 0
        var released = 0
        var finished = 0
        var closed = 0
        var rejectLaunch = false
        var saved = true
        var finishGate: CompletableDeferred<Unit>? = null
        val controller = RideSessionController(scope.backgroundScope,
            prerequisites = { failure },
            launchService = { if (rejectLaunch) throw IllegalStateException("OS rejected start"); token = it; launches++ },
            acquireTracking = { acquired++ }, releaseTracking = { released++ },
            finishRecording = { finished++; finishGate?.await(); saved }, closeService = { closed++ },
            nowMs = { scope.testScheduler.currentTime })
        fun start() { assertTrue(controller.requestStart(true)); assertTrue(controller.serviceStarted(token)) }
    }

    @Test fun requiresVisibleExplicitStartAndGrantedPrerequisites() = runTest {
        val r = Rig(this)
        assertFalse(r.controller.requestStart(false))
        assertEquals(RideFailure.NOT_VISIBLE, r.controller.state.value.failure)
        for (error in listOf(RideFailure.PERMISSION, RideFailure.GPS_DISABLED, RideFailure.STORAGE)) {
            r.failure = error
            assertFalse(r.controller.requestStart(true))
            assertEquals(error, r.controller.state.value.failure)
        }
        assertEquals(0, r.launches)
        assertEquals(0, r.acquired)
    }

    @Test fun activityRecreationAndRepeatedStartDoNotDuplicateGpsOrRecording() = runTest {
        val r = Rig(this)
        r.start()
        assertTrue(r.controller.requestStart(false)) // Existing service survives UI absence, no new start.
        assertTrue(r.controller.requestStart(true))
        assertFalse(r.controller.serviceStarted(r.token))
        assertEquals(1, r.launches)
        assertEquals(1, r.acquired)
        advanceTimeBy(10_000)
        assertEquals(RidePhase.ACTIVE, r.controller.state.value.phase)
    }

    @Test fun finishingWaitsForDatabaseFlushThenStopsServiceExactlyOnce() = runTest {
        val r = Rig(this)
        r.start()
        r.finishGate = CompletableDeferred()
        r.controller.stop()
        r.controller.stop()
        runCurrent()
        assertEquals(RidePhase.STOPPING, r.controller.state.value.phase)
        assertEquals(1, r.released)
        assertEquals(0, r.closed)
        assertFalse(r.controller.requestStart(true))
        r.finishGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, r.finished)
        assertEquals(1, r.closed)
        assertEquals(RideSessionState(), r.controller.state.value)
    }

    @Test fun pendingOrTimedOutServiceStartCannotResurrectAnEndedRide() = runTest {
        val r = Rig(this)
        r.controller.requestStart(true)
        val old = r.token
        r.controller.stop()
        runCurrent()
        assertFalse(r.controller.serviceStarted(old))
        r.controller.requestStart(true)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(RideFailure.START_FAILED, r.controller.state.value.failure)
        assertFalse(r.controller.serviceStarted(r.token))
    }

    @Test fun rejectedStartAndPermissionRaceDoNotLeaveRunningSession() = runTest {
        val r = Rig(this)
        r.rejectLaunch = true
        assertFalse(r.controller.requestStart(true))
        runCurrent()
        assertEquals(RidePhase.IDLE, r.controller.state.value.phase)
        r.rejectLaunch = false
        r.controller.requestStart(true)
        r.failure = RideFailure.PERMISSION
        assertFalse(r.controller.serviceStarted(r.token))
        runCurrent()
        assertEquals(RideFailure.PERMISSION, r.controller.state.value.failure)
        assertEquals(0, r.acquired)
    }

    @Test fun locationAndStorageFailuresFinishAndReleaseTracking() = runTest {
        for (failure in listOf(RideFailure.PERMISSION, RideFailure.GPS_DISABLED, RideFailure.STORAGE)) {
            val r = Rig(this)
            r.start()
            r.failure = failure
            r.controller.tick(LocationState(), failure == RideFailure.STORAGE)
            runCurrent()
            assertEquals(failure, r.controller.state.value.failure)
            assertEquals(1, r.released)
            assertEquals(1, r.closed)
        }
    }

    @Test fun idleAndMissingGpsDoNotLeaveServiceRunningForever() = runTest {
        val lost = Rig(this)
        lost.start()
        advanceTimeBy(180_001)
        lost.controller.tick(LocationState(), false)
        runCurrent()
        assertEquals(RideFailure.GPS_LOST, lost.controller.state.value.failure)
        val idle = Rig(this)
        idle.start()
        advanceTimeBy(180_001)
        idle.controller.tick(LocationState(speedKmh = 0, elapsedRealtimeMs = testScheduler.currentTime), false)
        runCurrent()
        assertEquals(RideSessionState(), idle.controller.state.value)
    }

    @Test fun freshMovementKeepsTrackingWhileOldMovingFixesDoNot() = runTest {
        val r = Rig(this)
        r.start()
        advanceTimeBy(120_000)
        val fix = LocationState(speedKmh = 40, elapsedRealtimeMs = testScheduler.currentTime)
        r.controller.tick(fix, false)
        advanceTimeBy(120_000)
        r.controller.tick(fix, false)
        assertEquals(RidePhase.ACTIVE, r.controller.state.value.phase)
        advanceTimeBy(60_001)
        r.controller.tick(fix, false)
        runCurrent()
        assertEquals(RideFailure.GPS_LOST, r.controller.state.value.failure)
    }

    @Test fun unexpectedServiceDestructionFlushesButNeverRestartsIt() = runTest {
        val r = Rig(this)
        r.start()
        val old = r.token
        r.controller.serviceDestroyed(old)
        runCurrent()
        assertEquals(RideFailure.INTERRUPTED, r.controller.state.value.failure)
        assertEquals(1, r.launches)
        r.start()
        r.controller.serviceDestroyed(old)
        assertEquals(RidePhase.ACTIVE, r.controller.state.value.phase)
    }

    @Test fun failedFlushStillStopsServiceAndReportsStorageError() = runTest {
        val r = Rig(this)
        r.start()
        r.saved = false
        r.controller.stop()
        runCurrent()
        assertEquals(RideFailure.STORAGE, r.controller.state.value.failure)
        assertEquals(1, r.closed)
    }

    @Test fun navigationCanWaitAtAStopButNotKeepAliveWithoutGps() = runTest {
        val r = Rig(this)
        r.start()
        advanceTimeBy(200_000)
        r.controller.tick(LocationState(elapsedRealtimeMs = testScheduler.currentTime), false, navigating = true)
        assertEquals(RidePhase.ACTIVE, r.controller.state.value.phase)
        advanceTimeBy(180_001)
        r.controller.tick(LocationState(), false, navigating = true)
        runCurrent()
        assertEquals(RideFailure.GPS_LOST, r.controller.state.value.failure)
    }

    @Test fun gpsCallbackPermissionLossStopsEvenBeforePermissionCacheUpdates() = runTest {
        val r = Rig(this)
        r.start()
        r.controller.tick(LocationState(locationPermissionGranted = false), false)
        runCurrent()
        assertEquals(RideFailure.PERMISSION, r.controller.state.value.failure)
        assertEquals(1, r.closed)
    }
}

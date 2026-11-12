package com.ridedeck.app

import com.ridedeck.app.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private class MemoryTrips : TripStorage {
    val rows = linkedMapOf<Long, Trip>()
    val samples = linkedMapOf<Long, MutableList<TripPoint>>()
    var failWrites = false
    var failDeletes = false
    private var next = 0L
    override fun startTrip(startedAt: Long): Long = (++next).also {
        rows[it] = Trip(it, startedAt, 0, TripStats(0.0, 0, 0))
        samples[it] = mutableListOf()
    }
    override fun addPoint(tripId: Long, p: TripPoint) {
        if (failWrites) throw IOException("disk full")
        check(rows.containsKey(tripId)) { "orphan point" }
        samples.getValue(tripId).add(p)
    }
    override fun finishTrip(tripId: Long, endedAt: Long, stats: TripStats) {
        rows[tripId] = rows.getValue(tripId).copy(endedAt = endedAt, stats = stats)
        samples.getValue(tripId).removeAll { it.t > endedAt }
    }
    override fun trips() = rows.values.filter { it.endedAt > 0 }
    override fun points(tripId: Long) = samples[tripId]?.toList().orEmpty()
    override fun unfinishedIds() = rows.values.filter { it.endedAt == 0L }.map { it.id }
    override fun lastPointTime(tripId: Long) = points(tripId).lastOrNull()?.t ?: 0
    override fun delete(tripId: Long) {
        if (failDeletes) throw IOException("delete failed")
        rows.remove(tripId)
        samples.remove(tripId)
    }
    override fun deleteAll() {
        if (failDeletes) throw IOException("delete failed")
        rows.clear()
        samples.clear()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderTest {
    private class Ride(scope: TestScope, val store: MemoryTrips = MemoryTrips()) {
        val location = MutableStateFlow(LocationState())
        val enabled = MutableStateFlow(true)
        val recorder = TripRecorder(store, scope.backgroundScope, location, enabled, StandardTestDispatcher(scope.testScheduler))
    }

    private fun TestScope.drive(ride: Ride, seconds: IntRange) {
        for (sec in seconds) {
            ride.location.value = LocationState(fixAtMs = sec * 1000L, speedKmh = 36, lat = 0.0, lon = sec * 0.00009)
            runCurrent()
        }
    }

    private fun finishedTrip(store: MemoryTrips, id: Long, startedAt: Long = 1_000L) {
        store.rows[id] = Trip(id, startedAt, startedAt + 60_000, TripStats(400.0, 60_000, 40))
        store.samples[id] = mutableListOf(TripPoint(startedAt, 0.0, 0.0, 20))
    }

    @Test fun disablingRecordingWithoutAnotherGpsFixClosesTheTripImmediately() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..75)
        assertTrue(ride.recorder.recording.value)
        ride.enabled.value = false
        runCurrent()
        assertFalse(ride.recorder.recording.value)
        assertEquals(1, ride.store.trips().size)
        assertEquals(73, ride.store.points(1).size)
    }

    @Test fun gpsLossForSeveralMinutesSeparatesRidesAndStartStreaks() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..75)
        drive(ride, 600..600)
        assertEquals(1, ride.store.trips().size)
        assertFalse(ride.recorder.recording.value)
        drive(ride, 601..602)
        assertTrue(ride.recorder.recording.value)
        assertEquals(602_000L, ride.store.points(2).single().t)
    }

    @Test fun widelySpacedMovingFixesDoNotStartATrip() = runTest {
        val ride = Ride(this)
        runCurrent()
        for (sec in listOf(1, 20, 40)) drive(ride, sec..sec)
        assertFalse(ride.recorder.recording.value)
        assertTrue(ride.store.rows.isEmpty())
    }

    @Test fun deletingAllDuringRecordingDoesNotLeaveAnActiveIdOrOrphanPoints() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..75)
        assertTrue(ride.recorder.deleteAll())
        assertFalse(ride.recorder.recording.value)
        drive(ride, 76..78)
        assertTrue(ride.recorder.recording.value)
        assertEquals(1, ride.store.rows.size)
        assertEquals(78_000L, ride.store.points(2).single().t)
    }

    @Test fun writeFailureStopsRecordingAndIsReportedWithoutCrashingCollector() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..10)
        ride.store.failWrites = true
        drive(ride, 11..12)
        assertTrue(ride.recorder.error.value)
        assertFalse(ride.recorder.recording.value)
        assertEquals(1, ride.store.rows.size)
    }

    @Test fun unfinishedRideIsRecoveredAfterProcessRestart() = runTest {
        val store = MemoryTrips()
        val id = store.startTrip(1_000)
        for (sec in 1..75) store.addPoint(id, TripPoint(sec * 1000L, 0.0, sec * 0.00009, 36))
        val ride = Ride(this, store)
        runCurrent()
        assertEquals(75_000L, store.trips().single().endedAt)
        assertFalse(ride.recorder.recording.value)
    }

    @Test fun duplicateAndReorderedFixesAreNotPersisted() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..10)
        drive(ride, 9..10)
        assertEquals((3..10).map { it * 1000L }, ride.store.points(1).map { it.t })
    }

    @Test fun explicitFinishFlushesTheCurrentRideWithoutWaitingForAnotherFix() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..75)
        assertTrue(ride.recorder.finishCurrent())
        assertFalse(ride.recorder.recording.value)
        assertEquals(75_000L, ride.store.trips().single().endedAt)
        val version = ride.recorder.version.value
        assertTrue(ride.recorder.finishCurrent())
        assertEquals(version, ride.recorder.version.value)
    }

    @Test fun deletingATripReportsSuccessAndOnlyRemovesThatTrip() = runTest {
        val store = MemoryTrips()
        finishedTrip(store, 1)
        finishedTrip(store, 2, startedAt = 2_000L)
        val ride = Ride(this, store)
        runCurrent()
        val version = ride.recorder.version.value

        assertTrue(ride.recorder.delete(1))
        assertFalse(store.rows.containsKey(1))
        assertFalse(store.samples.containsKey(1))
        assertTrue(store.rows.containsKey(2))
        assertTrue(store.samples.containsKey(2))
        assertEquals(version + 1, ride.recorder.version.value)
    }

    @Test fun failedDeleteReportsFailureAndKeepsTheStoredRide() = runTest {
        val store = MemoryTrips()
        finishedTrip(store, 1)
        val ride = Ride(this, store)
        runCurrent()
        ride.store.failDeletes = true
        val version = ride.recorder.version.value

        assertFalse(ride.recorder.delete(1))
        assertTrue(store.rows.containsKey(1))
        assertTrue(store.samples.getValue(1).isNotEmpty())
        assertTrue(ride.recorder.error.value)
        assertEquals(version, ride.recorder.version.value)
    }

    @Test fun deletingAnotherTripWhileRecordingKeepsTheActiveRide() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..10)
        assertTrue(ride.recorder.recording.value)
        finishedTrip(ride.store, 99)

        assertTrue(ride.recorder.delete(99))
        assertTrue(ride.recorder.recording.value)
        drive(ride, 11..11)
        assertEquals(11_000L, ride.store.points(1).last().t)
    }

    @Test fun failedDeleteOfTheActiveTripKeepsItsRowForRecoveryAndStopsRecording() = runTest {
        val ride = Ride(this)
        runCurrent()
        drive(ride, 1..10)
        val active = ride.store.rows.keys.single()
        ride.store.failDeletes = true

        assertFalse(ride.recorder.delete(active))
        assertTrue(ride.recorder.error.value)
        assertFalse(ride.recorder.recording.value)
        assertTrue(ride.store.rows.containsKey(active))
        assertTrue(ride.store.points(active).isNotEmpty())
    }

    @Test fun failedDeleteAllReportsFailureAndKeepsEveryStoredRide() = runTest {
        val store = MemoryTrips()
        finishedTrip(store, 1)
        finishedTrip(store, 2, startedAt = 2_000L)
        val ride = Ride(this, store)
        runCurrent()
        ride.store.failDeletes = true
        val version = ride.recorder.version.value

        assertFalse(ride.recorder.deleteAll())
        assertTrue(store.rows.containsKey(1))
        assertTrue(store.rows.containsKey(2))
        assertTrue(store.samples.containsKey(1))
        assertTrue(store.samples.containsKey(2))
        assertTrue(ride.recorder.error.value)
        assertEquals(version, ride.recorder.version.value)
    }
}

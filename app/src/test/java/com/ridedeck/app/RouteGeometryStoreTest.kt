package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometryFileGateway
import com.ridedeck.app.data.RouteGeometryReject
import com.ridedeck.app.data.RouteGeometryRestoreResult
import com.ridedeck.app.data.RouteGeometrySnapshot
import com.ridedeck.app.data.RouteGeometryStore
import com.ridedeck.app.data.RouteGeometryWriteResult
import com.ridedeck.app.data.RouteGeometryWriteSession
import com.ridedeck.app.data.RouteOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RouteGeometryStoreTest {
    private val shape = listOf(
        GeoPoint(41.0, 29.0),
        GeoPoint(41.001, 29.0),
        GeoPoint(41.002, 29.0),
    )
    private val maneuvers = listOf(
        Maneuver(1, "Go", "", "Head north", 100.0, 0),
        Maneuver(4, "Arrive", "", "Arrived", 0.0, 2),
    )
    private val route = Route(shape, maneuvers, 200.0, 30.0)
    private val dest = Place("D", "", shape.last())

    private fun snapshot(stops: List<Place> = emptyList(), options: RouteOptions = RouteOptions(), route: Route = this.route) =
        RouteGeometrySnapshot(dest, null, stops, options, 1_000, route)

    private fun active(stops: List<Place> = emptyList(), options: RouteOptions = RouteOptions()) =
        ActiveTrip(dest, null, 1_000, stops, options)

    private fun newStore(
        gateway: FakeGateway,
        maxFileBytes: Int = RouteGeometryStore.MAX_FILE_BYTES,
        beforeCommit: () -> Unit = {},
    ) = RouteGeometryStore(gateway, Dispatchers.Unconfined, maxFileBytes, beforeCommit)

    @Test
    fun byteBoundRejectsBeforeAtomicBegin() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway, maxFileBytes = 10)
        assertEquals(RouteGeometryWriteResult.RejectedSize, store.save(snapshot(), active()))
        assertEquals(0, gateway.beginCount)
    }

    @Test
    fun commitFailurePreservesLastGoodRecord() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        assertEquals(RouteGeometryWriteResult.Saved, store.save(snapshot(), active()))
        val good = gateway.current

        gateway.commitFailure = IOException("commit failed")
        assertEquals(RouteGeometryWriteResult.Failed, store.save(snapshot().copy(savedAtMs = 2_000), active()))
        assertEquals(good, gateway.current)
        assertTrue(gateway.abortCount > 0)
    }

    @Test
    fun cancellationBetweenFsyncAndCommitPreservesLastGoodRecord() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        assertEquals(RouteGeometryWriteResult.Saved, store.save(snapshot(), active()))
        val good = gateway.current

        val cancelling = newStore(gateway, beforeCommit = { throw CancellationException("between fsync and commit") })
        try {
            cancelling.save(snapshot().copy(savedAtMs = 2_000), active())
            throw AssertionError("cancellation did not propagate")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(good, gateway.current)
        assertTrue(gateway.abortCount > 0)
    }

    @Test
    fun saveThenRestoreRoundTrips() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val trip = snapshot(options = RouteOptions(avoidTolls = true))
        val tripState = active(options = RouteOptions(avoidTolls = true))

        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))
        val restored = store.restore(tripState, 1_000)
        assertTrue(restored is RouteGeometryRestoreResult.Restored)
        assertEquals(trip, (restored as RouteGeometryRestoreResult.Restored).snapshot)
    }

    @Test
    fun interruptedWriteAndFailingFsyncPreserveLastGoodRecord() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val trip = snapshot()
        val tripState = active()
        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))
        val good = gateway.current

        gateway.writeFailure = IOException("write interrupted")
        assertEquals(RouteGeometryWriteResult.Failed, store.save(trip, tripState))
        assertEquals(good, gateway.current)
        assertTrue(gateway.abortCount > 0)

        gateway.writeFailure = null
        gateway.syncFailure = IOException("fsync failed")
        assertEquals(RouteGeometryWriteResult.Failed, store.save(trip, tripState))
        assertEquals(good, gateway.current)
    }

    @Test
    fun cancellationBeforeCommitAbortsAndPreservesLastGoodRecord() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val trip = snapshot()
        val tripState = active()
        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))
        val good = gateway.current

        gateway.syncFailure = CancellationException("cancelled before commit")
        try {
            store.save(trip, tripState)
            throw AssertionError("cancellation did not propagate")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(good, gateway.current)
        assertTrue(gateway.abortCount > 0)
    }

    @Test
    fun oversizedReadIsRejectedWithoutDeletingTheFile() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        gateway.current = "x".repeat(RouteGeometryStore.MAX_FILE_BYTES + 1)

        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.OVERSIZED),
            store.restore(active(), 1_000),
        )
        assertTrue(gateway.current != null)
    }

    @Test
    fun staleFutureChecksumAndIdentityAreRejectedWithoutClearing() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val trip = snapshot()
        val tripState = active()
        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))
        val saved = gateway.current

        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.STALE),
            store.restore(tripState, 1_000 + RouteGeometrySnapshot.MAX_AGE_MS + 1),
        )
        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.STALE),
            store.restore(tripState, 999),
        )
        assertEquals(saved, gateway.current)

        gateway.current = saved!!.replace("\"hash\":\"", "\"hash\":\"0")
        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.MALFORMED),
            store.restore(tripState, 1_000),
        )
        assertTrue(gateway.current != null)
    }

    @Test
    fun encodeFailureBeforeAtomicBeginPreservesLastGoodRecord() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val trip = snapshot()
        val tripState = active()
        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))
        val good = gateway.current
        val begins = gateway.beginCount

        val invalid = snapshot(route = route.copy(distanceM = 5_000.0))
        assertEquals(RouteGeometryWriteResult.Failed, store.save(invalid, tripState))
        assertEquals(good, gateway.current)
        assertEquals(begins, gateway.beginCount)
    }

    @Test
    fun postFuelFinalDestinationRejectsPumpGeometrySaveAndRestore() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val finalPlace = Place("Final", "", GeoPoint(41.01, 29.0))
        val pumpGeometry = snapshot()
        val fuelState = active().copy(finalDestination = finalPlace)

        assertEquals(RouteGeometryWriteResult.RejectedIdentity, store.save(pumpGeometry, fuelState))

        gateway.current = pumpGeometry.toJson()
        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.IDENTITY_MISMATCH),
            store.restore(fuelState, 1_000),
        )
    }

    @Test
    fun explicitClearDeletesBaseAndBackupThenRestoreIsMissing() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        assertEquals(RouteGeometryWriteResult.Saved, store.save(snapshot(), active()))

        assertTrue(store.clear())
        assertTrue(gateway.current == null)
        assertEquals(RouteGeometryRestoreResult.Missing, store.restore(active(), 1_000))
    }

    @Test
    fun interruptedClearReportsFailureAndCanBeRetriedWithoutResurrection() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        assertEquals(RouteGeometryWriteResult.Saved, store.save(snapshot(), active()))
        val good = gateway.current

        gateway.deleteFailure = IOException("clear interrupted")
        assertTrue(!store.clear())
        assertEquals(good, gateway.current)

        gateway.deleteFailure = null
        assertTrue(store.clear())
        assertEquals(RouteGeometryRestoreResult.Missing, store.restore(active(), 1_000))
    }

    @Test
    fun passedStopsAndOptionsChangesInvalidateButNameOnlyDoesNot() = runBlocking {
        val gateway = FakeGateway()
        val store = newStore(gateway)
        val stop = Place("S", "", shape[1])
        val stopRoute = route.copy(maneuvers = listOf(maneuvers[0], Maneuver(4, "Stop", "", "Stop", 0.0, 1, isStop = true), maneuvers[1]), stopIndices = listOf(1))
        val trip = snapshot(stops = listOf(stop), route = stopRoute)
        val tripState = active(stops = listOf(stop))
        assertEquals(RouteGeometryWriteResult.Saved, store.save(trip, tripState))

        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.IDENTITY_MISMATCH),
            store.restore(tripState.copy(stops = emptyList()), 1_000),
        )
        assertEquals(
            RouteGeometryRestoreResult.Rejected(RouteGeometryReject.IDENTITY_MISMATCH),
            store.restore(tripState.copy(options = RouteOptions(avoidHighways = true)), 1_000),
        )
        assertTrue(store.restore(tripState.copy(destination = dest.copy(name = "Renamed")), 1_000) is RouteGeometryRestoreResult.Restored)
    }

    private class FakeGateway(initial: String? = null) : RouteGeometryFileGateway {
        var current: String? = initial
        var temp: String? = null
        var writeFailure: Throwable? = null
        var syncFailure: Throwable? = null
        var commitFailure: Throwable? = null
        var deleteFailure: Throwable? = null
        var abortCount = 0
        var commitCount = 0
        var beginCount = 0
        var deleteCount = 0

        override fun lengthBytes(): Long = current?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

        override fun readText(maxBytes: Int): String? {
            val value = current ?: return null
            return value.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
        }

        override fun beginWrite(): RouteGeometryWriteSession {
            beginCount++
            return object : RouteGeometryWriteSession {
                override fun write(bytes: ByteArray) {
                    writeFailure?.let { throw it }
                    temp = String(bytes, Charsets.UTF_8)
                }

                override fun sync() {
                    syncFailure?.let { throw it }
                }

                override fun commit() {
                    commitFailure?.let { throw it }
                    current = temp
                    temp = null
                    commitCount++
                }

                override fun abort() {
                    temp = null
                    abortCount++
                }
            }
        }

        override fun delete() {
            deleteFailure?.let { throw it }
            current = null
            temp = null
            deleteCount++
        }
    }
}

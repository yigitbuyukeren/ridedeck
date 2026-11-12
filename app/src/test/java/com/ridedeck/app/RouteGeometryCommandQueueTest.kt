package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometryCommandQueue
import com.ridedeck.app.data.RouteGeometryFileGateway
import com.ridedeck.app.data.RouteGeometrySnapshot
import com.ridedeck.app.data.RouteGeometryStore
import com.ridedeck.app.data.RouteGeometryWriteSession
import com.ridedeck.app.data.RouteOptions
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RouteGeometryCommandQueueTest {
    private val shape = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.002, 0.0))
    private val route = Route(
        shape,
        listOf(Maneuver(1, "Go", "", "Go", 100.0, 0), Maneuver(4, "Arrive", "", "Arrive", 0.0, 2)),
        200.0,
        30.0,
    )
    private val dest = Place("D", "", shape.last())
    private val trip = RouteGeometrySnapshot(dest, null, emptyList(), RouteOptions(), 1_000, route)
    private val active = ActiveTrip(dest, null, 1_000, emptyList(), RouteOptions())

    @Test
    fun clearThenSaveKeepsTheNewRecord() = runBlocking {
        val gateway = MemoryGateway()
        val queue = RouteGeometryCommandQueue(RouteGeometryStore(gateway, Dispatchers.Unconfined), Dispatchers.Unconfined)
        val worker = launch { queue.run() }

        queue.enqueueClear()
        queue.enqueueSave(trip, active)
        yield()

        assertEquals(trip, RouteGeometrySnapshot.fromJson(gateway.current))
        queue.close()
        worker.join()
    }

    @Test
    fun saveThenClearRemovesTheOldRecord() = runBlocking {
        val gateway = MemoryGateway()
        val queue = RouteGeometryCommandQueue(RouteGeometryStore(gateway, Dispatchers.Unconfined), Dispatchers.Unconfined)
        val worker = launch { queue.run() }

        queue.enqueueSave(trip, active)
        queue.enqueueClear()
        yield()

        assertNull(gateway.current)
        queue.close()
        worker.join()
    }

    @Test
    fun boundedBacklogDropsIntermediateCommandsAndLatestRevisionWins() = runBlocking {
        val gateway = MemoryGateway()
        val queue = RouteGeometryCommandQueue(RouteGeometryStore(gateway, Dispatchers.Unconfined), Dispatchers.Unconfined)
        // No consumer yet: capacity 1 must retain only the latest command.
        queue.enqueueSave(trip.copy(savedAtMs = 1_000), active.copy(savedAtMs = 1_000))
        queue.enqueueClear()
        queue.enqueueSave(trip.copy(savedAtMs = 3_000), active.copy(savedAtMs = 3_000))

        val worker = launch { queue.run() }
        yield()

        assertEquals(3_000L, RouteGeometrySnapshot.fromJson(gateway.current)!!.savedAtMs)
        queue.close()
        worker.join()
    }

    @Test
    fun cancelledRunnerClosesQueueAndCannotReplayPendingCommands() = runBlocking {
        val gateway = MemoryGateway()
        val queue = RouteGeometryCommandQueue(RouteGeometryStore(gateway, Dispatchers.Unconfined), Dispatchers.Unconfined)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { queue.run() }
        queue.enqueueSave(trip, active)
        worker.cancelAndJoin()

        assertFalse(queue.enqueueClear())
        assertFalse(queue.enqueueSave(trip, active))
    }

    /** Simulates old clear -> refresh/start save and save -> old clear under one serialized queue. */
    private class MemoryGateway : RouteGeometryFileGateway {
        var current: String? = null
        private var temp: String? = null

        override fun lengthBytes(): Long = current?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        override fun readText(maxBytes: Int): String? = current
        override fun beginWrite(): RouteGeometryWriteSession = object : RouteGeometryWriteSession {
            override fun write(bytes: ByteArray) { temp = bytes.toString(Charsets.UTF_8) }
            override fun sync() = Unit
            override fun commit() { current = temp; temp = null }
            override fun abort() { temp = null }
        }
        override fun delete() { current = null; temp = null }
    }
}

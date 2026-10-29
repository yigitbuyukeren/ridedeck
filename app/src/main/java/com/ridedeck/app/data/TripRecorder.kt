package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Records rides automatically: a trip starts after a few consecutive fixes above [START_KMH] and
 * ends after [STOP_AFTER_MS] without movement. Trips too short to mean anything are discarded.
 * Points are written as they arrive, so a killed process loses at most the last fix; unfinished
 * trips are closed the next time the recorder starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorder(
    private val store: TripStorage,
    scope: CoroutineScope,
    location: StateFlow<LocationState>,
    private val enabled: StateFlow<Boolean>,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /** Called with the last moving point of every kept trip: that is where the bike was left. */
    private val onParked: (TripPoint) -> Unit = {},
) {
    private val mutex = Mutex()
    val error = MutableStateFlow(false)

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    /** Bumped whenever the list of trips changes. */
    val version = MutableStateFlow(0)

    private var tripId: Long? = null
    private var startStreak = 0
    private var lastFixAt = 0L
    private var lastMovingAt = 0L

    init {
        scope.launch {
            val ready = mutex.withLock {
                storageAction { withContext(io) { store.unfinishedIds().forEach { finish(it, store.lastPointTime(it)) } } }
            }
            if (!ready) return@launch
            version.value++
            combine(location, enabled) { fix, _ -> fix }.collect { fix ->
                mutex.withLock { if (!error.value) storageAction { onFix(fix) } }
            }
        }
    }

    private suspend fun storageAction(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Stop after a disk/database failure. Unfinished rows are recovered on the next start.
        error.value = true
        _recording.value = false
        tripId = null
        startStreak = 0
        false
    }

    private suspend fun onFix(fix: LocationState) {
        if (!enabled.value) {
            tripId?.let { end(it, lastMovingAt) }
            startStreak = 0
            lastFixAt = fix.fixAtMs
            return
        }
        if (fix.fixAtMs == 0L || fix.fixAtMs <= lastFixAt || fix.gpsProviderEnabled == false || fix.locationPermissionGranted == false) return
        if (lastFixAt > 0L && fix.fixAtMs - lastFixAt > STOP_AFTER_MS) {
            tripId?.let { end(it, lastMovingAt) }
            startStreak = 0
        }
        if (fix.fixAtMs - lastFixAt > LocationFixPolicy.MAX_AGE_MS) startStreak = 0
        lastFixAt = fix.fixAtMs
        val point = TripPoint(fix.fixAtMs, fix.lat, fix.lon, fix.speedKmh)
        val active = tripId
        if (active == null) {
            startStreak = if (fix.speedKmh >= START_KMH) startStreak + 1 else 0
            if (startStreak >= START_FIXES) {
                startStreak = 0
                val id = withContext(io) { store.startTrip(point.t).also { store.addPoint(it, point) } }
                tripId = id
                lastMovingAt = point.t
                _recording.value = true
            }
            return
        }
        withContext(io) { store.addPoint(active, point) }
        if (fix.speedKmh >= MOVING_KMH) lastMovingAt = point.t
        if (point.t - lastMovingAt > STOP_AFTER_MS) end(active, lastMovingAt)
    }

    private suspend fun end(id: Long, endedAt: Long) {
        tripId = null
        _recording.value = false
        val parked = withContext(io) { finish(id, endedAt) }
        version.value++
        parked?.let(onParked)
    }

    /** Flush before a user-ended foreground session releases its notification/process priority. */
    suspend fun finishCurrent(): Boolean = mutex.withLock {
        startStreak = 0
        storageAction { tripId?.let { end(it, lastMovingAt) } } && !error.value
    }

    /** Runs on [io]. Returns the point where the bike stopped when the trip was kept, null when it was discarded. */
    private fun finish(id: Long, endedAt: Long): TripPoint? {
        val all = store.points(id)
        // The minutes spent standing still before the trip was closed are not part of the ride.
        val points = all.filter { it.t <= endedAt }.ifEmpty { all }
        val stats = TripMath.compute(points)
        val started = points.firstOrNull()?.t ?: endedAt
        if (stats.distanceM < MIN_DISTANCE_M || stats.movingMs < MIN_MOVING_MS) {
            store.delete(id)
            return null
        }
        store.finishTrip(id, maxOf(endedAt, started + 1), stats)
        return points.lastOrNull { it.t <= endedAt } ?: points.lastOrNull()
    }

    suspend fun delete(id: Long) = mutex.withLock {
        if (storageAction { withContext(io) { store.delete(id) } } && tripId == id) {
            tripId = null
            _recording.value = false
            startStreak = 0
        }
        version.value++
    }

    suspend fun deleteAll() = mutex.withLock {
        if (storageAction { withContext(io) { store.deleteAll() } }) {
            tripId = null
            _recording.value = false
            startStreak = 0
        }
        version.value++
    }

    suspend fun trips(): List<Trip> = mutex.withLock {
        var result = emptyList<Trip>()
        storageAction { result = withContext(io) { store.trips() } }
        result
    }

    suspend fun points(id: Long): List<TripPoint> = mutex.withLock {
        var result = emptyList<TripPoint>()
        storageAction { result = withContext(io) { store.points(id) } }
        result
    }

    private companion object {
        const val START_KMH = 8
        const val START_FIXES = 3
        const val MOVING_KMH = 2
        const val STOP_AFTER_MS = 3 * 60_000L
        const val MIN_DISTANCE_M = 300.0
        const val MIN_MOVING_MS = 60_000L
    }
}

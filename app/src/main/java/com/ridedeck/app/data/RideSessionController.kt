package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class RidePhase { IDLE, STARTING, ACTIVE, STOPPING }
enum class RideFailure { NOT_VISIBLE, PERMISSION, GPS_DISABLED, START_FAILED, STORAGE, GPS_LOST, INTERRUPTED }
data class RideSessionState(val phase: RidePhase = RidePhase.IDLE, val failure: RideFailure? = null) {
    val running: Boolean get() = phase != RidePhase.IDLE
}

/** Main-thread session ownership. The Android shell promotes the service before accepting its token. */
class RideSessionController(
    private val scope: CoroutineScope,
    private val prerequisites: () -> RideFailure?,
    private val launchService: (Long) -> Unit,
    private val acquireTracking: () -> Unit,
    private val releaseTracking: () -> Unit,
    private val finishRecording: suspend () -> Boolean,
    private val closeService: () -> Unit,
    private val nowMs: () -> Long,
    private val idleMs: Long = 180_000L,
    private val startupMs: Long = 5_000L,
) {
    private val mutableState = MutableStateFlow(RideSessionState())
    val state: StateFlow<RideSessionState> = mutableState
    private var token = 0L
    private var startup: Job? = null
    private var lastMovement = 0L
    private var lastMeasurement = 0L
    private var startedAt = 0L

    fun requestStart(visible: Boolean): Boolean {
        if (state.value.phase == RidePhase.STOPPING) return false
        if (state.value.running) return true
        val failure = if (!visible) RideFailure.NOT_VISIBLE else prerequisites()
        if (failure != null) {
            mutableState.value = RideSessionState(failure = failure)
            return false
        }
        val request = ++token
        mutableState.value = RideSessionState(RidePhase.STARTING)
        startup = scope.launch {
            delay(startupMs)
            if (accepts(request)) stop(RideFailure.START_FAILED)
        }
        try { launchService(request) } catch (_: RuntimeException) { stop(RideFailure.START_FAILED); return false }
        return true
    }

    fun accepts(request: Long): Boolean = request == token && state.value.phase == RidePhase.STARTING

    fun serviceStarted(request: Long): Boolean {
        if (!accepts(request)) return false
        startup?.cancel()
        val failure = prerequisites()
        if (failure != null) { stop(failure); return false }
        try { acquireTracking() } catch (_: RuntimeException) { stop(RideFailure.START_FAILED); return false }
        lastMovement = nowMs()
        startedAt = lastMovement
        lastMeasurement = 0L
        mutableState.value = RideSessionState(RidePhase.ACTIVE)
        return true
    }

    fun isActive(request: Long): Boolean = request == token && state.value.phase == RidePhase.ACTIVE

    /** Timeouts use elapsed time, including sleep. Duplicate fixes never keep an abandoned service alive. */
    fun tick(fix: LocationState, storageFailed: Boolean, navigating: Boolean = false) {
        if (state.value.phase != RidePhase.ACTIVE) return
        val failure = prerequisites() ?: when {
            storageFailed -> RideFailure.STORAGE
            fix.locationPermissionGranted == false -> RideFailure.PERMISSION
            fix.gpsProviderEnabled == false -> RideFailure.GPS_DISABLED
            else -> null
        }
        if (failure != null) { stop(failure); return }
        val now = nowMs()
        if (fix.elapsedRealtimeMs > lastMeasurement && now - fix.elapsedRealtimeMs in 0..5_000) {
            lastMeasurement = fix.elapsedRealtimeMs
            if (fix.hasSpeed && fix.speedKmh >= 2) lastMovement = fix.elapsedRealtimeMs
        }
        val lost = now - (lastMeasurement.takeIf { it > 0L } ?: startedAt) >= idleMs
        if (lost) stop(RideFailure.GPS_LOST)
        else if (!navigating && now - lastMovement >= idleMs) stop()
    }

    fun serviceDestroyed(request: Long) {
        if (request == token && state.value.running) stop(RideFailure.INTERRUPTED)
    }

    fun stop(failure: RideFailure? = null) {
        if (!state.value.running || state.value.phase == RidePhase.STOPPING) return
        ++token // A queued start, old notification, or late callback cannot restart this ride.
        startup?.cancel()
        mutableState.value = RideSessionState(RidePhase.STOPPING, failure)
        releaseTracking()
        scope.launch {
            val saved = try { finishRecording() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            try { closeService() } finally {
                mutableState.value = RideSessionState(failure = if (saved) failure else RideFailure.STORAGE)
            }
        }
    }
}

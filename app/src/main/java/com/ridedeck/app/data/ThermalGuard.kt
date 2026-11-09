package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * What the screen should do to keep a phone in the sun on a handlebar from overheating.
 * [brightness] is the window brightness to apply, or [NO_OVERRIDE] to follow the system setting.
 */
data class ThermalState(
    val brightness: Float = NO_OVERRIDE,
    /** Slow the map down when true. */
    val lowPower: Boolean = false,
    /** Hot enough to tell the rider. */
    val warn: Boolean = false,
    val stoppedSinceMs: Long = 0L,
) {
    companion object {
        const val NO_OVERRIDE = -1f
    }
}

object ThermalMath {
    const val DIM_FROM_C = 42f
    const val DIM_FULL_C = 46f
    const val WARN_C = 45f
    /** The warning clears only once the phone cooled this far, so it does not flicker around 45. */
    const val WARN_CLEAR_C = 43f
    const val MIN_BRIGHTNESS = 0.35f
    const val IDLE_BRIGHTNESS = 0.1f
    const val IDLE_AFTER_MS = 60_000L
    const val STOPPED_KMH = 2

    /** 1 up to [DIM_FROM_C], then a straight line down to [MIN_BRIGHTNESS] at [DIM_FULL_C]. */
    fun brightnessCap(tempC: Float): Float = when {
        tempC <= DIM_FROM_C -> 1f
        tempC >= DIM_FULL_C -> MIN_BRIGHTNESS
        else -> 1f - (tempC - DIM_FROM_C) / (DIM_FULL_C - DIM_FROM_C) * (1f - MIN_BRIGHTNESS)
    }

    /**
     * One step of the guard. A cool phone is never touched: the screen only dims when the battery is
     * hot, and goes nearly dark only if it is hot *and* the bike has stood still for a minute.
     */
    fun next(prev: ThermalState, tempC: Float, speedKmh: Int?, nowMs: Long, enabled: Boolean): ThermalState {
        if (!enabled) return ThermalState()
        // Unknown speed (no GPS) is not evidence of a stop: the idle timer does not run on it.
        val stoppedSince = if (speedKmh != null && speedKmh < STOPPED_KMH) prev.stoppedSinceMs.takeIf { it > 0 } ?: nowMs else 0L
        val hot = tempC > DIM_FROM_C
        val idle = hot && stoppedSince > 0 && nowMs - stoppedSince >= IDLE_AFTER_MS
        val brightness = when {
            idle -> IDLE_BRIGHTNESS
            hot -> brightnessCap(tempC)
            else -> ThermalState.NO_OVERRIDE
        }
        val warn = if (prev.warn) tempC > WARN_CLEAR_C else tempC >= WARN_C
        return ThermalState(brightness, lowPower = hot, warn = warn, stoppedSinceMs = stoppedSince)
    }
}

class ThermalGuard(
    scope: CoroutineScope,
    battery: StateFlow<BatteryState>,
    speedKmh: StateFlow<Int?>,
    enabled: StateFlow<Boolean>,
    private val onWarn: () -> Unit,
) {
    private val _state = MutableStateFlow(ThermalState())
    val state: StateFlow<ThermalState> = _state

    /**
     * StateFlows only emit on change, and a bike standing still changes nothing, so without this
     * beat the "still for a minute" timer would never advance.
     */
    private val beat = flow {
        while (true) {
            emit(Unit)
            delay(BEAT_MS)
        }
    }

    init {
        scope.launch {
            combine(battery, speedKmh, enabled, beat) { b, speed, on, _ -> Triple(b.tempC, speed, on) }
                .collect { (temp, speed, on) ->
                    val prev = _state.value
                    val next = ThermalMath.next(prev, temp, speed, System.currentTimeMillis(), on)
                    _state.value = next
                    if (next.warn && !prev.warn) onWarn()
                }
        }
    }

    private companion object {
        const val BEAT_MS = 5_000L
    }
}

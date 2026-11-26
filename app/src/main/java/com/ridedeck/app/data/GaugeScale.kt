package com.ridedeck.app.data

import kotlin.math.floor
import kotlin.math.roundToInt

/** One numbered tick on the speed dial: where it sits (0..1 of the full scale) and the number shown in the driver's unit. */
data class GaugeLabel(val fraction: Float, val text: String)

/** Which numbers go around the dial, for the vehicle's full scale and the unit in use. */
object GaugeScale {
    private const val KM_PER_MILE = 1.609344
    /** Preferred label steps, largest first: the dial should carry four to six numbers. */
    private val STEPS = listOf(50, 40, 30, 25, 20, 10, 5)

    fun step(maxKmh: Float, miles: Boolean): Int {
        val maxDisplay = if (miles) maxKmh / KM_PER_MILE else maxKmh.toDouble()
        val whole = floor(maxDisplay + 1e-6).toInt()
        // A step that divides the scale exactly wins (50 on a 200 dial, 40 on 240, 10 on 60); mph rarely divides, so the next best does.
        STEPS.firstOrNull { whole % it == 0 && whole / it in 4..6 }?.let { return it }
        return STEPS.firstOrNull { whole / it in 4..6 } ?: STEPS.last()
    }

    fun labels(maxKmh: Float, miles: Boolean): List<GaugeLabel> {
        if (maxKmh <= 0f) return emptyList()
        val step = step(maxKmh, miles)
        val maxDisplay = if (miles) maxKmh / KM_PER_MILE else maxKmh.toDouble()
        val out = ArrayList<GaugeLabel>()
        var value = 0
        while (value <= maxDisplay + 1e-6) {
            val kmh = if (miles) value * KM_PER_MILE else value.toDouble()
            out += GaugeLabel((kmh / maxKmh).toFloat().coerceIn(0f, 1f), value.toString())
            value += step
        }
        return out
    }

    /** Fractions of the minor ticks: halfway between two numbers. */
    fun minorTicks(maxKmh: Float, miles: Boolean): List<Float> {
        val labels = labels(maxKmh, miles)
        return labels.zipWithNext { a, b -> (a.fraction + b.fraction) / 2 }
    }
}

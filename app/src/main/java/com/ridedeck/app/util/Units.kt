package com.ridedeck.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

/** Display-only conversion: everything inside the app stays in km/h and metres. */
object UnitMath {
    private const val KM_PER_MILE = 1.609344
    private const val FEET_PER_METRE = 3.28084

    fun speed(kmh: Int, miles: Boolean): Int = if (miles) (kmh / KM_PER_MILE).roundToInt() else kmh

    fun distance(meters: Int, miles: Boolean): String {
        if (!miles) {
            return when {
                meters < 1000 -> "${(meters / 10 * 10).coerceAtLeast(10)} m"
                else -> "%.1f km".format(Locale.getDefault(), meters / 1000.0)
            }
        }
        val feet = meters * FEET_PER_METRE
        return when {
            feet < 500 -> "${((feet / 10).toInt() * 10).coerceAtLeast(10)} ft"
            else -> "%.1f mi".format(Locale.getDefault(), meters / 1000.0 / KM_PER_MILE)
        }
    }
}

/** The rider's choice, observable so every screen redraws when it changes. */
object Units {
    var miles by mutableStateOf(false)
        private set

    fun load(context: Context) {
        miles = Prefs.milesUnit(context)
    }

    fun set(context: Context, value: Boolean) {
        Prefs.setMilesUnit(context, value)
        miles = value
    }

    fun speed(kmh: Int): Int = UnitMath.speed(kmh, miles)

    fun distance(meters: Int): String = UnitMath.distance(meters, miles)

    /** Valhalla `units` value for route texts. */
    fun routingUnits(context: Context): String = if (Prefs.milesUnit(context)) "miles" else "kilometers"
}

package com.ridedeck.app

import com.ridedeck.app.util.UnitMath
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class UnitsTest {
    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun kilometresPassThrough() {
        Locale.setDefault(Locale.US)
        assertEquals(90, UnitMath.speed(90, miles = false))
        assertEquals("450 m", UnitMath.distance(453, miles = false))
        assertEquals("1.5 km", UnitMath.distance(1500, miles = false))
    }

    @Test
    fun decimalSeparatorFollowsDefaultLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals("1,5 km", UnitMath.distance(1500, miles = false))
        assertEquals("0,6 mi", UnitMath.distance(1000, miles = true))

        Locale.setDefault(Locale.US)
        assertEquals("1.5 km", UnitMath.distance(1500, miles = false))
        assertEquals("0.6 mi", UnitMath.distance(1000, miles = true))
    }

    @Test
    fun speedConvertsToMph() {
        assertEquals(56, UnitMath.speed(90, miles = true))
        assertEquals(0, UnitMath.speed(0, miles = true))
        assertEquals(62, UnitMath.speed(100, miles = true))
    }

    @Test
    fun thresholdSpeedInMilesIsFive() {
        // TripRecorder.START_KMH = 8 km/h shown to a mile-based rider
        assertEquals(5, UnitMath.speed(8, miles = true))
    }

    @Test
    fun shortDistancesUseFeetAndLongOnesMiles() {
        Locale.setDefault(Locale.US)
        assertEquals("150 ft", UnitMath.distance(46, miles = true))
        assertEquals("10 ft", UnitMath.distance(1, miles = true))
        assertEquals("0.6 mi", UnitMath.distance(1000, miles = true))
        assertEquals("3.1 mi", UnitMath.distance(5000, miles = true))
    }
}

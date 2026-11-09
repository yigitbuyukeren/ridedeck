package com.ridedeck.app

import com.ridedeck.app.data.SpokenDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenDistanceTest {
    private val t: (Int) -> String = { id ->
        when (id) {
            R.string.spoken_m -> "%1\$d metre"
            R.string.spoken_km -> "%1\$s kilometre"
            R.string.spoken_ft -> "%1\$d feet"
            R.string.spoken_mi -> "%1\$s miles"
            else -> "t$id"
        }
    }

    @Test
    fun roundsLikeAPersonWould() {
        assertEquals(1500, SpokenDistance.roundMetres(1480.0))
        assertEquals(1000, SpokenDistance.roundMetres(1040.0))
        assertEquals(800, SpokenDistance.roundMetres(812.0))
        assertEquals(450, SpokenDistance.roundMetres(430.0))
        assertEquals(150, SpokenDistance.roundMetres(149.0))
        assertEquals(10, SpokenDistance.roundMetres(2.0))
    }

    @Test
    fun metricAndImperialTexts() {
        java.util.Locale.setDefault(java.util.Locale.US)
        assertEquals("800 metre", SpokenDistance.text(812.0, false, t))
        assertEquals("1.5 kilometre", SpokenDistance.text(1480.0, false, t))
        assertEquals("2 kilometre", SpokenDistance.text(2040.0, false, t))
        assertEquals("500 feet", SpokenDistance.text(150.0, true, t))
        assertEquals("0.5 miles", SpokenDistance.text(800.0, true, t))
        assertEquals("2 miles", SpokenDistance.text(3219.0, true, t))
    }
}

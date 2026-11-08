package com.ridedeck.app

import com.ridedeck.app.data.GaugeScale
import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeScaleTest {
    private fun texts(max: Float, miles: Boolean) = GaugeScale.labels(max, miles).map { it.text }

    @Test
    fun motorcycleDialRunsInFifties() = assertEquals(listOf("0", "50", "100", "150", "200"), texts(200f, false))

    @Test
    fun milesLabelsAreConvertedAndSitAtTheirKmhPosition() {
        val labels = GaugeScale.labels(200f, true)
        assertEquals("0", labels.first().text)
        assertEquals(labels.size, texts(200f, true).size)
        // 60 mph is 96.56 km/h of a 200 km/h dial.
        val sixty = labels.first { it.text == "60" }
        assertEquals(96.56f / 200f, sixty.fraction, 0.001f)
    }

    @Test
    fun minorTicksSitBetweenTheNumbers() {
        val minors = GaugeScale.minorTicks(200f, false)
        assertEquals(listOf(0.125f, 0.375f, 0.625f, 0.875f), minors)
    }
}

package com.ridedeck.app

import com.ridedeck.app.ui.StatFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class StatFitTest {
    /** Linear text model: widths scale exactly with the nominal sizes. */
    private fun linearMeasure(valueWidth: Float, unitWidth: Float): (Float, Float) -> Float =
        { valueSp, unitSp -> valueWidth * (valueSp / 34f) + unitWidth * (unitSp / 17f) }

    @Test
    fun wideCardKeepsTheDesignSizes() {
        val s = StatFit.fitMeasured(
            availableWidth = 300f,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 122f, unitWidth = 90f),
        )
        assertEquals(34f, s.valueSp, 0.01f)
        assertEquals(17f, s.unitSp, 0.01f)
    }

    @Test
    fun narrowCardShrinksOnlyTheTextAndKeepsTheGapFixed() {
        val value = 204f
        val unit = 95f
        val gap = 12f
        val available = 280f
        val s = StatFit.fitMeasured(
            availableWidth = available,
            gap = gap,
            headroom = 0f,
            measure = linearMeasure(value, unit),
        )
        val scale = (available - gap) / (value + unit)
        assertEquals(34f * scale, s.valueSp, 0.01f)
        assertEquals(17f * scale, s.unitSp, 0.01f)
        // Actual scaled widths plus the unscaled gap must still fit.
        assertTrue(
            "scaled text + fixed gap must fit",
            value * (s.valueSp / 34f) + unit * (s.unitSp / 17f) + gap <= available + 0.01f,
        )
        assertTrue(s.valueSp < 34f)
    }

    @Test
    fun unitAlwaysStaysSmallerThanTheValue() {
        for (available in listOf(400f, 260f, 180f, 110f)) {
            val s = StatFit.fitMeasured(
                availableWidth = available,
                gap = 12f,
                headroom = 0f,
                measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
            )
            assertEquals(s.valueSp / 2f, s.unitSp, 0.01f)
        }
    }

    @Test
    fun largerFontOrThreeDigitValueNeverGrowsText() {
        val base = StatFit.fitMeasured(
            availableWidth = 300f,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 122f, unitWidth = 90f),
        )
        val threeDigits = StatFit.fitMeasured(
            availableWidth = 300f,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
        )
        // A larger font scale shows up as wider measured text at the same nominal size.
        val largeFont = StatFit.fitMeasured(
            availableWidth = 300f,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 122f * 1.5f, unitWidth = 90f * 1.5f),
        )
        assertTrue(threeDigits.valueSp <= base.valueSp)
        assertTrue(largeFont.valueSp <= base.valueSp)
    }

    @Test
    fun boundsSmallRoomAndNonFiniteInputsStayMeaningful() {
        // No room beyond the gap: text is scaled to zero, never negative.
        val noRoom = StatFit.fitMeasured(
            availableWidth = 12f,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
        )
        assertEquals(0f, noRoom.valueSp, 0.01f)
        assertEquals(0f, noRoom.unitSp, 0.01f)

        val zero = StatFit.fitMeasured(
            availableWidth = 0f,
            gap = 0f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
        )
        assertEquals(0f, zero.valueSp, 0.01f)

        // A zero-width measurement and non-finite layout input keep the design size.
        val unmeasured = StatFit.fitMeasured(availableWidth = 100f, gap = 12f, headroom = 0f) { _, _ -> 0f }
        assertEquals(34f, unmeasured.valueSp, 0.01f)
        assertEquals(17f, unmeasured.unitSp, 0.01f)
        val infinite = StatFit.fitMeasured(
            availableWidth = Float.POSITIVE_INFINITY,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
        )
        assertEquals(34f, infinite.valueSp, 0.01f)
        val notANumber = StatFit.fitMeasured(
            availableWidth = Float.NaN,
            gap = 12f,
            headroom = 0f,
            measure = linearMeasure(valueWidth = 204f, unitWidth = 95f),
        )
        assertEquals(34f, notANumber.valueSp, 0.01f)
    }

    @Test
    fun measuredFitHandlesConcaveAndroidFontScalingThatBreaksLinearShrink() {
        fun width(sp: Float): Float = 4f * sp.pow(0.8f)
        fun measured(valueSp: Float, unitSp: Float): Float = width(valueSp) + width(unitSp)
        val available = 100f
        val gap = 8f

        // Android 14's converter is concave: shrinking sp reduces px less than proportionally.
        val baseWidth = measured(34f, 17f)
        val linearScale = (available - gap) / baseWidth
        val linearValueSp = 34f * linearScale
        val linearUnitSp = 17f * linearScale
        assertTrue(
            "the old linear estimate must overflow a concave curve",
            measured(linearValueSp, linearUnitSp) + gap > available,
        )

        val s = StatFit.fitMeasured(
            availableWidth = available,
            gap = gap,
            headroom = 0f,
            measure = ::measured,
        )
        // Re-measuring at the chosen candidate really fits.
        assertTrue(measured(s.valueSp, s.unitSp) + gap <= available + 0.01f)
        assertEquals(2f, s.valueSp / s.unitSp, 0.01f)
    }
}

package com.ridedeck.app

import com.ridedeck.app.ui.GaugeFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeFitTest {
    private fun fit(h: Float, w: Float, pad: Float, stroke: Float, digits: Int, scale: Float = 1f, below: Float = 68f) =
        GaugeFit.fit(h, w, pad, stroke, 14.4f * scale, scale, digits, below, 92f)

    @Test
    fun landscapeKeepsFullSizeAndCentring() {
        val f = fit(390f, 400f, 20f, 26f, 3)
        assertEquals(92f, f.sp, 0.01f)
        assertNull(f.topFromCentre)
    }

    @Test
    fun portraitNumberStartsBelowTopLabel() {
        for (digits in 1..3) {
            val f = fit(263f, 336f, 12f, 22f, digits)
            val trackInner = (263f - 24f) / 2 - 22f
            val labelBottom = -(trackInner - 16f - 14.4f)
            val top = f.topFromCentre ?: (-(GaugeFit.LINE * f.sp + 68f) / 2)
            assertTrue("digits=$digits top=$top label=$labelBottom", top >= labelBottom)
            // Column stays inside the card.
            assertTrue(top + GaugeFit.LINE * f.sp + 68f <= 263f / 2)
        }
        assertNotNull(fit(263f, 336f, 12f, 22f, 2).topFromCentre)
        assertTrue(fit(263f, 336f, 12f, 22f, 3).sp < 92f)
    }

    @Test
    fun smallerCardOrLargerFontNeverGetsBiggerNumber() {
        val a = fit(263f, 336f, 12f, 22f, 2)
        assertTrue(fit(230f, 336f, 12f, 22f, 2).sp <= a.sp)
        assertTrue(fit(263f, 336f, 12f, 22f, 2, scale = 1.15f).sp <= a.sp)
    }
}

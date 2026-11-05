package com.ridedeck.app.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Sizes the big speed number so it stays inside the dial, below the scale labels.
 * All lengths are dp; "nominal" ones are before the (capped) font scale is applied.
 */
object GaugeFit {
    /** Height of a text line relative to its font size. */
    const val LINE = 1.17f
    private const val DIGIT_WIDTH = 0.6f
    private const val MIN_SP = 32f
    private const val EDGE_GAP = 6f

    /**
     * @property sp nominal font size of the number.
     * @property topFromCentre where the text column must start, measured from the card centre (negative = above);
     * null when the centred column already clears the labels, so the card keeps its plain centred look.
     */
    data class Fit(val sp: Float, val topFromCentre: Float?)

    fun fit(
        cardHeight: Float,
        cardWidth: Float,
        padding: Float,
        stroke: Float,
        labelHeight: Float,
        fontScale: Float,
        digits: Int,
        belowNominal: Float,
        baseSp: Float,
    ): Fit {
        val side = min(cardHeight, cardWidth)
        val trackInner = (side - 2 * padding) / 2 - stroke
        val labelCentre = trackInner - 16f - labelHeight / 2
        // Lowest edge of the 12 o'clock label, plus a small gap.
        val top = -(trackInner - 16f - labelHeight) + 2f
        // The labels at both ends of the scale sit lower, beside the number: stay between them.
        val halfWidth = max(0f, 0.866f * labelCentre - labelHeight * 0.6f)
        val widthCap = 2 * halfWidth / (DIGIT_WIDTH * max(1, digits)) / fontScale
        val bottom = cardHeight / 2 - EDGE_GAP
        val heightCap = ((bottom - top) / fontScale - belowNominal) / LINE
        val sp = max(MIN_SP, min(baseSp, min(heightCap, widthCap)))
        val columnHeight = (LINE * sp + belowNominal) * fontScale
        val centredTop = -columnHeight / 2
        return Fit(sp, if (centredTop >= top) null else top)
    }
}

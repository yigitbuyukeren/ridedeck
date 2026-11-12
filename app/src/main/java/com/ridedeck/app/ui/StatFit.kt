package com.ridedeck.app.ui

/**
 * Finds a value/unit pair that fits on one line while keeping the unit smaller than the value.
 *
 * Android 14+ maps sp to px through a non-proportional font-scale converter, so a linear estimate
 * from the nominal widths is not reliable. [fitMeasured] therefore measures the real text at
 * candidate sizes and uses a bounded binary search with pixel headroom. The gap is fixed and is
 * subtracted before comparing widths.
 */
object StatFit {
    data class Sizes(val valueSp: Float, val unitSp: Float)

    /**
     * @param measure returns the combined width in px of the value and unit drawn at the candidate
     *   sizes. Callers pass styles built from the same merged [androidx.compose.ui.text.TextStyle]
     *   that will be rendered, including an explicit line height.
     * @param headroom extra px kept free for rounding, so the result stays strictly inside the card.
     * @param iterations bounded so a pathological measurement callback cannot loop forever.
     */
    fun fitMeasured(
        availableWidth: Float,
        gap: Float,
        baseValueSp: Float = 34f,
        baseUnitSp: Float = 17f,
        headroom: Float = 1.5f,
        iterations: Int = 12,
        measure: (valueSp: Float, unitSp: Float) -> Float,
    ): Sizes {
        // Non-finite layout input keeps the design size instead of producing NaN sizes.
        if (!availableWidth.isFinite() || !gap.isFinite() || !headroom.isFinite()) {
            return Sizes(baseValueSp, baseUnitSp)
        }
        val room = (availableWidth - gap - headroom).coerceAtLeast(0f)
        val widthsAt = { scale: Float -> measure(baseValueSp * scale, baseUnitSp * scale) }
        if (widthsAt(1f) <= room) return Sizes(baseValueSp, baseUnitSp)
        var low = 0f
        var high = 1f
        repeat(iterations.coerceIn(0, 24)) {
            val mid = (low + high) / 2f
            if (widthsAt(mid) <= room) low = mid else high = mid
        }
        return Sizes(baseValueSp * low, baseUnitSp * low)
    }
}

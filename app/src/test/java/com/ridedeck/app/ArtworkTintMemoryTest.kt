package com.ridedeck.app

import androidx.compose.ui.graphics.Color
import com.ridedeck.app.ui.ArtworkTintMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Palette work is keyed on artwork identity, not on every recomposition of the card. */
class ArtworkTintMemoryTest {
    private val first = Any()
    private val second = Any()
    private val night = Color(0xFF102030)
    private val day = Color(0xFFF0E0D0)

    @Test
    fun sameArtworkAndThemeReusesTheTint() {
        val memory = ArtworkTintMemory()
        memory.put(first, false, night)
        assertEquals(night, memory.cached(first, false))
    }

    @Test
    fun themeOrArtworkChangeMissesTheCache() {
        val memory = ArtworkTintMemory()
        memory.put(first, false, night)
        assertNull(memory.cached(first, true))
        assertNull(memory.cached(second, false))
    }

    @Test
    fun emptyCacheDoesNotPretendToHaveATint() {
        assertNull(ArtworkTintMemory().cached(first, false))
    }

    @Test
    fun latestArtworkReplacesThePreviousOne() {
        val memory = ArtworkTintMemory()
        memory.put(first, false, night)
        memory.put(second, true, day)
        assertNull(memory.cached(first, false))
        assertEquals(day, memory.cached(second, true))
    }
}

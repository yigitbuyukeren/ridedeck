package com.ridedeck.app

import com.ridedeck.app.ui.ArtworkMemory
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The card tint must not follow the artwork a player drops or replaces while paused. */
class ArtworkMemoryTest {
    private val first = Any()
    private val second = Any()
    private val track = "Title" to "Artist"

    @Test
    fun keepsArtworkWhenPausedAndThePlayerDropsIt() {
        val memory = ArtworkMemory<Any>()
        assertSame(first, memory.pick(track, first, hasSession = true))
        assertSame(first, memory.pick(track, null, hasSession = true))
    }

    @Test
    fun ignoresReplacementArtworkForTheSameTrack() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, hasSession = true)
        assertSame(first, memory.pick(track, second, hasSession = true))
    }

    @Test
    fun acceptsArtworkThatArrivesLateForTheSameTrack() {
        val memory = ArtworkMemory<Any>()
        assertNull(memory.pick(track, null, hasSession = true))
        assertSame(first, memory.pick(track, first, hasSession = true))
        assertSame(first, memory.pick(track, second, hasSession = true))
    }

    @Test
    fun takesTheNewArtworkOnATrackChange() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, hasSession = true)
        assertSame(second, memory.pick("Next" to "Artist", second, hasSession = true))
    }

    @Test
    fun keepsTheOldArtworkUntilTheNewTrackHasOne() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, hasSession = true)
        // The new title arrives before its cover: keep the old colour instead of flashing dark.
        assertSame(first, memory.pick("Next" to "Artist", null, hasSession = true))
        assertSame(second, memory.pick("Next" to "Artist", second, hasSession = true))
    }

    @Test
    fun resetsWhenTheSessionEnds() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, hasSession = true)
        assertNull(memory.pick("" to "", null, hasSession = false))
    }
}

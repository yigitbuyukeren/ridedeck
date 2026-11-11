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
        assertSame(first, memory.pick(track, first, playing = true, hasSession = true))
        assertSame(first, memory.pick(track, null, playing = false, hasSession = true))
    }

    @Test
    fun ignoresReplacementArtworkWhilePaused() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, playing = true, hasSession = true)
        assertSame(first, memory.pick(track, second, playing = false, hasSession = true))
    }

    @Test
    fun acceptsArtworkThatArrivesLateWhilePlaying() {
        val memory = ArtworkMemory<Any>()
        assertNull(memory.pick(track, null, playing = true, hasSession = true))
        assertSame(first, memory.pick(track, first, playing = true, hasSession = true))
        assertSame(second, memory.pick(track, second, playing = true, hasSession = true))
    }

    @Test
    fun takesTheNewArtworkOnATrackChange() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, playing = true, hasSession = true)
        assertSame(second, memory.pick("Next" to "Artist", second, playing = false, hasSession = true))
    }

    @Test
    fun resetsWhenTheSessionEnds() {
        val memory = ArtworkMemory<Any>()
        memory.pick(track, first, playing = true, hasSession = true)
        assertNull(memory.pick("" to "", null, playing = false, hasSession = false))
    }
}

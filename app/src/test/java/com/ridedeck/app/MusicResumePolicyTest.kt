package com.ridedeck.app

import com.ridedeck.app.data.MusicResumePolicy
import com.ridedeck.app.data.MediaSessionActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicResumePolicyTest {
    @Test fun staleLastPlayerFallsBackToInstalledMusicApp() {
        assertEquals("player.available", MusicResumePolicy.select("player.removed", listOf("player.available")) { it == "player.available" })
    }

    @Test fun unavailablePlayersDoNotPretendMusicCanStart() {
        assertNull(MusicResumePolicy.select("player.removed", listOf("player.other")) { false })
    }

    @Test fun installedLastPlayerHasPriority() {
        assertEquals("player.last", MusicResumePolicy.select("player.last", listOf("player.other")) { true })
    }

    @Test fun unsupportedSessionActionIsNeverPresentedAsAvailable() {
        val play = 1L shl 2
        val skip = 1L shl 4
        assertEquals(true, MediaSessionActionPolicy.supports(play or skip, skip))
        assertEquals(false, MediaSessionActionPolicy.supports(play, skip))
        assertEquals(false, MediaSessionActionPolicy.supports(play, 0L))
    }
}

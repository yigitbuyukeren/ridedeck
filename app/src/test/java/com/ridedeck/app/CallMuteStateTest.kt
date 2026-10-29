package com.ridedeck.app

import com.ridedeck.app.data.CallMuteState
import org.junit.Assert.*
import org.junit.Test

class CallMuteStateTest {
    @Test fun modernMuteAndUnmuteDoNotWaitForDeprecatedAudioCallbacks() {
        val state = CallMuteState(modernApi = true)
        state.modernChanged(true)
        assertTrue(state.muted)
        state.modernChanged(false)
        assertFalse(state.muted)
    }

    @Test fun lateLegacyCallbackCannotReverseConfirmedModernMicrophoneState() {
        val state = CallMuteState(modernApi = true)
        state.modernChanged(true)
        state.legacyChanged(false)
        assertTrue(state.muted)
        state.modernChanged(false)
        state.legacyChanged(true)
        assertFalse(state.muted)
    }

    @Test fun olderAndroidKeepsLegacyMuteAndUnmute() {
        val state = CallMuteState(modernApi = false)
        state.legacyChanged(true)
        assertTrue(state.muted)
        state.modernChanged(false)
        assertTrue(state.muted)
        state.legacyChanged(false)
        assertFalse(state.muted)
    }
}

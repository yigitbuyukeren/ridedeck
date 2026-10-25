package com.ridedeck.app

import com.ridedeck.app.data.AudioSessionManager
import com.ridedeck.app.data.VoiceRuntimeState
import com.ridedeck.app.data.VoiceStateMachine
import com.ridedeck.app.data.VoiceRetryBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionManagerTest {
    @Test
    fun aNewSessionRevokesTheOldOwnerAndOnlyOneOwnerRemains() {
        val manager = AudioSessionManager()
        var revoked = 0
        val wake = manager.acquire(AudioSessionManager.Owner.VOSK_WAKE) { revoked++ }
        val command = manager.acquire(AudioSessionManager.Owner.ANDROID_STT) { }

        assertEquals(1, revoked)
        assertEquals(AudioSessionManager.Owner.ANDROID_STT, manager.currentOwner())
        assertFalse(manager.isCurrent(wake))
        assertTrue(manager.isCurrent(command))
    }

    @Test
    fun aLateReleaseCannotClearTheNewSession() {
        val manager = AudioSessionManager()
        val old = manager.acquire(AudioSessionManager.Owner.ANDROID_STT) { }
        val newest = manager.acquire(AudioSessionManager.Owner.VOSK_STT) { }

        assertFalse(manager.release(old))
        assertEquals(AudioSessionManager.Owner.VOSK_STT, manager.currentOwner())
        assertTrue(manager.release(newest))
        assertEquals(null, manager.currentOwner())
        assertEquals(null, manager.owner.value)
    }

    @Test
    fun revocationCallbackMayReenterManager() {
        val manager = AudioSessionManager()
        var revoked = false
        manager.acquire(AudioSessionManager.Owner.ANDROID_WAKE) { revoked = manager.currentOwner() == AudioSessionManager.Owner.VOSK_WAKE }
        manager.acquire(AudioSessionManager.Owner.VOSK_WAKE) { }
        assertTrue(revoked)
    }

    @Test
    fun publishedOwnerTracksAcquireAndRelease() {
        val manager = AudioSessionManager()
        val token = manager.acquire(AudioSessionManager.Owner.TTS) { }
        assertEquals(AudioSessionManager.Owner.TTS, manager.owner.value)
        manager.release(token)
        assertEquals(null, manager.owner.value)
    }

    @Test
    fun voiceStateMachineAllowsExpectedConversationAndRejectsInvalidJump() {
        val machine = VoiceStateMachine()
        assertTrue(machine.transition(VoiceRuntimeState.WAKE_LISTENING))
        assertFalse(machine.transition(VoiceRuntimeState.FOLLOW_UP))
        assertTrue(machine.transition(VoiceRuntimeState.ACTIVATING))
        assertTrue(machine.transition(VoiceRuntimeState.COMMAND_LISTENING))
        assertTrue(machine.transition(VoiceRuntimeState.PROCESSING))
        assertTrue(machine.transition(VoiceRuntimeState.CONFIRMING))
        assertTrue(machine.transition(VoiceRuntimeState.SPEAKING))
        assertTrue(machine.transition(VoiceRuntimeState.FOLLOW_UP))
        assertTrue(machine.transition(VoiceRuntimeState.ERROR))
        assertTrue(machine.transition(VoiceRuntimeState.SUSPENDED))
        assertTrue(machine.transition(VoiceRuntimeState.IDLE))
        assertEquals(VoiceRuntimeState.IDLE, machine.state.value)
    }

    @Test
    fun followUpAudioOwnerCanStartFromIdleAndPublishesTheState() {
        val manager = AudioSessionManager()
        val token = manager.acquire(AudioSessionManager.Owner.FOLLOW_UP_STT) { }
        assertEquals(VoiceRuntimeState.FOLLOW_UP, manager.stateMachine.state.value)
        manager.release(token)
    }

    @Test
    fun releasingAnAudioSessionDoesNotUndoLifecycleSuspension() {
        val manager = AudioSessionManager()
        val token = manager.acquire(AudioSessionManager.Owner.ANDROID_STT) { }
        assertTrue(manager.stateMachine.transition(VoiceRuntimeState.SUSPENDED))
        manager.release(token)
        assertEquals(VoiceRuntimeState.SUSPENDED, manager.stateMachine.state.value)
    }

    @Test
    fun wakeRetryBackoffGrowsAndCaps() {
        assertEquals(0L, VoiceRetryBackoff.delay(0, 500, 30_000))
        assertEquals(500L, VoiceRetryBackoff.delay(1, 500, 30_000))
        assertEquals(2_000L, VoiceRetryBackoff.delay(3, 500, 30_000))
        assertEquals(30_000L, VoiceRetryBackoff.delay(20, 500, 30_000))
    }
}

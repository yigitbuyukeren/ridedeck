package com.ridedeck.app.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceRuntimeState {
    IDLE, WAKE_LISTENING, ACTIVATING, COMMAND_LISTENING, PROCESSING, CONFIRMING, SPEAKING, FOLLOW_UP, ERROR, SUSPENDED,
}

object VoiceRetryBackoff {
    fun delay(attempt: Int, initialMs: Long, maximumMs: Long): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceAtMost(30)
        return (initialMs * (1L shl shift)).coerceAtMost(maximumMs)
    }
}

/** Single observable state for the assistant and all audio owners. Transitions are synchronized and testable. */
class VoiceStateMachine {
    private val lock = Any()
    private val _state = MutableStateFlow(VoiceRuntimeState.IDLE)
    val state: StateFlow<VoiceRuntimeState> = _state

    fun transition(next: VoiceRuntimeState): Boolean = synchronized(lock) {
        val current = _state.value
        if (current == next) return@synchronized true
        if (next !in ALLOWED.getValue(current)) return@synchronized false
        _state.value = next
        true
    }

    private companion object {
        private val ALLOWED = VoiceRuntimeState.entries.associateWith { state ->
            when (state) {
                VoiceRuntimeState.IDLE -> setOf(VoiceRuntimeState.WAKE_LISTENING, VoiceRuntimeState.ACTIVATING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.PROCESSING, VoiceRuntimeState.CONFIRMING, VoiceRuntimeState.SPEAKING, VoiceRuntimeState.FOLLOW_UP, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.WAKE_LISTENING -> setOf(VoiceRuntimeState.ACTIVATING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.ACTIVATING -> setOf(VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.COMMAND_LISTENING -> setOf(VoiceRuntimeState.PROCESSING, VoiceRuntimeState.FOLLOW_UP, VoiceRuntimeState.SPEAKING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.PROCESSING -> setOf(VoiceRuntimeState.CONFIRMING, VoiceRuntimeState.SPEAKING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.CONFIRMING -> setOf(VoiceRuntimeState.SPEAKING, VoiceRuntimeState.ACTIVATING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.PROCESSING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.SPEAKING -> setOf(VoiceRuntimeState.FOLLOW_UP, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.FOLLOW_UP -> setOf(VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.PROCESSING, VoiceRuntimeState.CONFIRMING, VoiceRuntimeState.IDLE, VoiceRuntimeState.SUSPENDED, VoiceRuntimeState.ERROR)
                VoiceRuntimeState.ERROR -> setOf(VoiceRuntimeState.IDLE, VoiceRuntimeState.ACTIVATING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.SPEAKING, VoiceRuntimeState.SUSPENDED)
                VoiceRuntimeState.SUSPENDED -> setOf(VoiceRuntimeState.IDLE, VoiceRuntimeState.WAKE_LISTENING, VoiceRuntimeState.COMMAND_LISTENING, VoiceRuntimeState.FOLLOW_UP, VoiceRuntimeState.ERROR)
            }
        }
    }
}

/** Process-wide arbitration for mutually exclusive microphone and speech-output sessions. */
class AudioSessionManager {
    enum class Owner { ANDROID_STT, FOLLOW_UP_STT, ANDROID_WAKE, TTS }

    class Token internal constructor(val id: Long, val owner: Owner)

    private data class Active(val token: Token, val revoke: () -> Unit)
    private val ids = AtomicLong()
    private val lock = Any()
    private var active: Active? = null
    private val _owner = MutableStateFlow<Owner?>(null)
    val owner: StateFlow<Owner?> = _owner
    val stateMachine = VoiceStateMachine()

    /** Takes ownership and revokes the prior session. Callbacks run outside the lock. */
    fun acquire(owner: Owner, onRevoked: () -> Unit): Token {
        val token = Token(ids.incrementAndGet(), owner)
        val previous = synchronized(lock) {
            active.also {
                active = Active(token, onRevoked)
                _owner.value = owner
                stateMachine.transition(stateFor(owner))
            }
        }
        runCatching { previous?.revoke?.invoke() }
        return token
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { active?.token == token }

    /** A stale callback may release only its own token; it cannot release a newer session. */
    fun release(token: Token): Boolean = synchronized(lock) {
        if (active?.token != token) false else {
            active = null
            _owner.value = null
            if (stateMachine.state.value != VoiceRuntimeState.SUSPENDED) stateMachine.transition(VoiceRuntimeState.IDLE)
            true
        }
    }

    fun currentOwner(): Owner? = synchronized(lock) { active?.token?.owner }

    private fun stateFor(owner: Owner) = when (owner) {
        Owner.ANDROID_WAKE -> VoiceRuntimeState.WAKE_LISTENING
        Owner.ANDROID_STT -> VoiceRuntimeState.COMMAND_LISTENING
        Owner.FOLLOW_UP_STT -> VoiceRuntimeState.FOLLOW_UP
        Owner.TTS -> VoiceRuntimeState.SPEAKING
    }

    companion object {
        /** All live RideDeck audio components arbitrate through this process-level instance. */
        val process = AudioSessionManager()
    }
}

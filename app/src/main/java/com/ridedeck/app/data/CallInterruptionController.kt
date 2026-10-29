package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A new call cancels the delayed resume; only the original, untouched player may resume. */
class CallInterruptionController(
    scope: CoroutineScope,
    phases: Flow<CallPhase>,
    isPlaying: () -> Boolean,
    player: () -> String?,
    commandVersion: () -> Long,
    suspendSpeech: (Boolean) -> Unit,
    resume: () -> Unit,
    canResume: () -> Boolean = { true },
    outputDevices: () -> Set<Int> = { emptySet() },
    resumeDelayMs: Long = 1_500L,
) {
    private data class Resume(val player: String?, val version: Long, val devices: Set<Int>)
    init {
        scope.launch {
            var previous = CallPhase.IDLE
            var pending: Resume? = null
            phases.collectLatest { phase ->
                val entering = previous == CallPhase.IDLE && phase != CallPhase.IDLE
                previous = phase
                suspendSpeech(phase != CallPhase.IDLE)
                if (entering) {
                    if (pending?.let { it.player != player() || it.version != commandVersion() } == true) pending = null
                    if (isPlaying()) pending = Resume(player(), commandVersion(), outputDevices().toSet())
                }
                if (phase == CallPhase.IDLE && pending != null) {
                    val request = pending!!
                    delay(resumeDelayMs)
                    pending = null
                    if (canResume() && !isPlaying() && request.player == player() && request.version == commandVersion() && outputDevices().containsAll(request.devices)) resume()
                }
            }
        }
    }
}

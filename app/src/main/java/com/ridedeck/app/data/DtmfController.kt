package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DtmfController<T : Any>(
    private val scope: CoroutineScope,
    private val play: (T, Char) -> Unit,
    private val stopTone: (T) -> Unit,
) {
    private var current: T? = null
    private var job: Job? = null
    private var generation = 0L

    fun send(call: T?, active: Boolean, key: Char): Boolean {
        if (call == null || !active || key !in "0123456789*#") return false
        stop()
        try { play(call, key) } catch (_: RuntimeException) { return false }
        current = call
        val request = ++generation
        job = scope.launch {
            try { delay(150L) } finally { if (request == generation) stop() }
        }
        return true
    }

    fun stop() {
        ++generation
        val old = current
        current = null
        val pending = job
        job = null
        pending?.cancel()
        old?.let { runCatching { stopTone(it) } }
    }
}

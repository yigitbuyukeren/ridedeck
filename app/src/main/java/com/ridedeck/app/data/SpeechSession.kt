package com.ridedeck.app.data

/**
 * One announcement at a time, with priorities. Focus, interruption and stale engine callbacks share this ownership.
 * A prompt of lower priority than the one playing waits in a single slot per priority (newest wins) and is spoken
 * when the current one ends, unless it has gone stale by then; a higher or equal priority flushes the current one.
 */
class SpeechSession(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val speak: (String, String) -> Boolean,
    private val stopEngine: () -> Unit,
    private val changed: (String?) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Pending(val text: String, val priority: SpeechPriority, val atMs: Long)

    private var current: String? = null
    private var currentPriority = SpeechPriority.NAVIGATION
    private var counter = 0L
    private var suspended = false
    private var closed = false
    private val pending = HashMap<SpeechPriority, Pending>()

    fun announce(text: String, priority: SpeechPriority = SpeechPriority.NAVIGATION) {
        if (closed || suspended || text.isBlank()) return
        if (current != null && currentPriority > priority) {
            // Something more important is playing: wait for it, keeping only the newest of this priority.
            pending[priority] = Pending(text, priority, now())
            return
        }
        pending.remove(priority)
        start(text, priority)
    }

    private fun start(text: String, priority: SpeechPriority) {
        // Stop before requesting again: losing focus must never leave an old prompt playing.
        interrupt()
        if (!requestFocus()) { playPending(); return }
        val id = "speech-${++counter}"
        current = id
        currentPriority = priority
        changed(id)
        if (!speak(text, id)) complete(id)
    }

    fun complete(id: String?) {
        if (id == null || current != id) return
        current = null
        changed(null)
        abandonFocus()
        playPending()
    }

    private fun playPending() {
        if (closed || suspended) return
        val next = pending.values.filter { now() - it.atMs <= PENDING_MAX_MS }.maxByOrNull { it.priority } ?: run { pending.clear(); return }
        pending.remove(next.priority)
        start(next.text, next.priority)
    }

    fun timeout(id: String) { if (current == id) stop() }

    /** Interrupts the current prompt without touching what is waiting. */
    private fun interrupt() {
        if (current == null) return
        current = null
        changed(null)
        stopEngine()
        abandonFocus()
    }

    /** Silence: the current prompt and everything waiting. */
    fun stop() {
        pending.clear()
        interrupt()
    }

    fun suspendForCall(value: Boolean) {
        suspended = value
        if (value) stop()
    }

    fun close() { stop(); closed = true }

    companion object {
        /** A waiting prompt older than this is no longer true ("rain in 10 minutes", a message) and is dropped. */
        const val PENDING_MAX_MS = 15_000L
    }
}

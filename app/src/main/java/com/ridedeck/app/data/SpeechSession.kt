package com.ridedeck.app.data

/** One flush-only announcement. Focus, interruption and stale engine callbacks share this ownership. */
class SpeechSession(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val speak: (String, String) -> Boolean,
    private val stopEngine: () -> Unit,
    private val changed: (String?) -> Unit = {},
) {
    private var current: String? = null
    private var counter = 0L
    private var suspended = false
    private var closed = false

    fun announce(text: String) {
        if (closed || suspended || text.isBlank()) return
        // Stop before requesting again: losing focus must never leave an old prompt playing.
        stop()
        if (!requestFocus()) return
        val id = "speech-${++counter}"
        current = id
        changed(id)
        if (!speak(text, id)) complete(id)
    }

    fun complete(id: String?) {
        if (id == null || current != id) return
        current = null
        changed(null)
        abandonFocus()
    }

    fun timeout(id: String) { if (current == id) stop() }

    fun stop() {
        if (current == null) return
        current = null
        changed(null)
        stopEngine()
        abandonFocus()
    }

    fun suspendForCall(value: Boolean) {
        suspended = value
        if (value) stop()
    }

    fun close() { stop(); closed = true }
}

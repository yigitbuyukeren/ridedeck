package com.ridedeck.app.data

/** API 34 mute callbacks are authoritative; a late legacy audio callback must not undo them. */
class CallMuteState(private val modernApi: Boolean) {
    var muted = false
        private set

    fun legacyChanged(isMuted: Boolean) {
        if (!modernApi) muted = isMuted
    }

    fun modernChanged(isMuted: Boolean) {
        if (modernApi) muted = isMuted
    }
}

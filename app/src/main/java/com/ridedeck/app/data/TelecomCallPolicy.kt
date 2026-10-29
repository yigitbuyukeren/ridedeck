package com.ridedeck.app.data

import android.telecom.Call

object TelecomCallPolicy {
    fun priority(state: Int): Int? = when (state) {
        Call.STATE_RINGING -> 0
        Call.STATE_ACTIVE -> 1
        Call.STATE_NEW, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL, Call.STATE_SELECT_PHONE_ACCOUNT -> 2
        Call.STATE_HOLDING -> 3
        else -> null
    }

    fun phase(state: Int): CallPhase = when (state) {
        Call.STATE_RINGING -> CallPhase.RINGING
        Call.STATE_HOLDING -> CallPhase.HELD
        Call.STATE_ACTIVE -> CallPhase.ACTIVE
        else -> if (priority(state) != null) CallPhase.DIALING else CallPhase.IDLE
    }
}

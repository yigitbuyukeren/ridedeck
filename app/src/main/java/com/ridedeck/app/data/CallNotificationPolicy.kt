package com.ridedeck.app.data

/** How a ringing call may present itself; kept pure so the Android version gate is unit-testable. */
object CallNotificationPolicy {
    /**
     * Android 14 added a user-controlled full-screen intent switch. Before it, the manifest
     * permission was the only gate; from 14 on the system switch wins and the call falls back to an
     * ordinary notification whose content intent still opens RideDeck.
     */
    fun fullScreenIntentAllowed(sdkInt: Int, allowedBySystem: Boolean): Boolean =
        sdkInt < 34 || allowedBySystem
}

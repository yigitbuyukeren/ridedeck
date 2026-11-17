package com.ridedeck.app.data

/**
 * Privacy gate for a chat notification before its sender and body leave the notification shade.
 *
 * The values mirror [android.app.Notification.VISIBILITY_*] so this policy stays a pure JVM object
 * (no Android dependency) and can be unit tested. [CarNotificationListener] passes
 * `Notification.visibility` and `KeyguardManager.isKeyguardLocked` here.
 *
 * Rules:
 *  - A notification the app marked secret is never surfaced, locked or unlocked.
 *  - While the keyguard is locked only public content may be surfaced in full. Private content
 *    becomes an app-only card ("content hidden"); the sender, body and any reply are not read.
 *  - While unlocked, public and private notifications may be surfaced in full.
 *  - An unknown visibility value is treated conservatively as app-only (never as content).
 */
object MessagePrivacy {
    const val VISIBILITY_SECRET = -1
    const val VISIBILITY_PRIVATE = 0
    const val VISIBILITY_PUBLIC = 1

    enum class Surface {
        /** Sender and message text may be displayed and read aloud. */
        FULL,

        /** Only the posting app may be named; sender and text are never parsed. */
        APP_ONLY,

        /** Nothing may be shown or spoken. */
        SKIP,
    }

    fun surface(visibility: Int, keyguardLocked: Boolean): Surface = when {
        visibility == VISIBILITY_SECRET -> Surface.SKIP
        keyguardLocked -> if (visibility == VISIBILITY_PUBLIC) Surface.FULL else Surface.APP_ONLY
        visibility == VISIBILITY_PUBLIC || visibility == VISIBILITY_PRIVATE -> Surface.FULL
        else -> Surface.APP_ONLY
    }
}

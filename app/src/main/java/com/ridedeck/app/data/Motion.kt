package com.ridedeck.app.data

/** What the bike is doing, as far as the GPS can tell. Every riding lock in the app reads this, not a raw speed. */
enum class Motion { MOVING, STOPPED, UNKNOWN }

/**
 * Turns the filtered display speed into a motion state with hysteresis: two consecutive fixes at or above
 * [movingKmh] mean riding, two below mean stopped, and a missing speed (no fresh fix) means unknown. One noisy
 * fix at a red light therefore changes nothing, and a tunnel does not pretend the bike has stopped.
 */
class MotionTracker(private val movingKmh: Double = MOVING_KMH, private val needFixes: Int = NEED_FIXES) {
    var motion: Motion = Motion.UNKNOWN
        private set
    private var lastFixId = Long.MIN_VALUE
    private var streak = 0
    private var streakMoving = false

    /**
     * @param displayKmh the filtered speed, or null while unknown.
     * @param fixId identity of the measurement (e.g. its monotonic time), so one fix observed twice counts once.
     */
    fun update(displayKmh: Double?, fixId: Long): Motion {
        if (displayKmh == null || !displayKmh.isFinite()) {
            motion = Motion.UNKNOWN
            streak = 0
            return motion
        }
        if (fixId == lastFixId) return motion
        lastFixId = fixId
        val moving = displayKmh >= movingKmh
        streak = if (moving == streakMoving) streak + 1 else 1
        streakMoving = moving
        val target = if (moving) Motion.MOVING else Motion.STOPPED
        // Coming out of UNKNOWN the first fix decides at once: a tunnel exit must not leave the keyboard open for two more fixes.
        if (motion == Motion.UNKNOWN || streak >= needFixes) motion = target
        return motion
    }

    fun reset() {
        motion = Motion.UNKNOWN; lastFixId = Long.MIN_VALUE; streak = 0; streakMoving = false
    }

    companion object {
        const val MOVING_KMH = 5.0
        const val NEED_FIXES = 2

        /** Typing and settings lock: locked while moving, open while stopped, unchanged while nothing is known. */
        fun inputLocked(previous: Boolean, motion: Motion): Boolean = when (motion) {
            Motion.MOVING -> true
            Motion.STOPPED -> false
            Motion.UNKNOWN -> previous
        }
    }
}

package com.ridedeck.app.data

/** Caps camera/GeoJSON work as well as rendering; a MapView FPS cap alone does not cap Compose frames. */
class FrameThrottle(fps: Int) {
    private val intervalNs = 1_000_000_000L / fps.coerceAtLeast(1)
    private var lastNs: Long? = null

    fun shouldDraw(nowNs: Long): Boolean {
        val last = lastNs
        if (last != null && nowNs - last < intervalNs) return false
        lastNs = nowNs
        return true
    }
}

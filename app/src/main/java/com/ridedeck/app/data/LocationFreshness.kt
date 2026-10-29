package com.ridedeck.app.data

/** Use the measurement clock, rather than delivery time, to reject cached and reordered GPS fixes. */
object LocationFixPolicy {
    const val MAX_AGE_MS = 5_000L

    fun accepts(measuredAtMs: Long, previousAtMs: Long, nowMs: Long, lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            measuredAtMs <= nowMs && nowMs - measuredAtMs <= MAX_AGE_MS &&
            (previousAtMs == 0L || measuredAtMs > previousAtMs)
}

fun LocationState.hasFreshFix(nowMs: Long, elapsedMs: Long? = null): Boolean {
    if (fixAtMs <= 0L || gpsProviderEnabled == false || locationPermissionGranted == false) return false
    val age = if (elapsedMs != null && elapsedRealtimeMs > 0L) elapsedMs - elapsedRealtimeMs else nowMs - fixAtMs
    return age in 0 until LocationFixPolicy.MAX_AGE_MS
}

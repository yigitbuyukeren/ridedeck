package com.ridedeck.app.data

object CallAudioRoutePolicy {
    /** Android CallEndpoint types differ from CallAudioState's bit mask (wired=3, speaker=4). */
    fun fromEndpointType(type: Int): Int = when (type) { 1 -> 1; 2 -> 2; 3 -> 4; 4 -> 8; else -> 0 }
    /** CallAudioState routes: earpiece, Bluetooth, wired headset and speaker. A request must name exactly one. */
    fun supports(route: Int, available: Int): Boolean = route in setOf(1, 2, 4, 8) && available and route == route
}

package com.ridedeck.app.data

/** A route is selected only by Telecom confirmation. Late errors and disconnected endpoints are scoped to a request. */
class CallRouteRequests {
    private var generation = 0L
    private var pending: Int? = null
    private var available = 0
    var failed = false
        private set

    fun availability(mask: Int) {
        available = mask
        if (pending?.let { !CallAudioRoutePolicy.supports(it, mask) } == true) {
            ++generation
            pending = null
            failed = true
        }
    }
    fun begin(route: Int): Long? {
        if (!CallAudioRoutePolicy.supports(route, available)) return null
        pending = route
        failed = false
        return ++generation
    }
    fun confirmed(route: Int) {
        failed = false
        if (pending == route) { pending = null; ++generation }
    }
    fun failure(request: Long) { if (request == generation && pending != null) { failed = true; pending = null; ++generation } }
    fun reset() { ++generation; pending = null; available = 0; failed = false }
}

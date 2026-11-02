package com.ridedeck.app.data

/** Debug builds only: replays a planned route as GPS fixes so guidance can be watched without riding. */
object DebugSim {
    /** Set by the ride session; null when nothing can simulate. */
    var hook: ((Route, Int) -> Unit)? = null
    var stopHook: (() -> Unit)? = null

    fun start(route: Route, multiplier: Int) = hook?.invoke(route, multiplier)
    fun stop() = stopHook?.invoke()
}

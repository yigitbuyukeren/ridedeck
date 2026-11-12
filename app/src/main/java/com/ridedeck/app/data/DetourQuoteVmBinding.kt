package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Route-context identity: no progress, ETA, maneuver or distance churn. */
internal data class DetourRouteContextKey(
    val route: Route?,
    val destination: Place?,
    val finalDestination: Place?,
    val stopsAhead: List<Place>,
    val navigating: Boolean,
    val options: RouteOptions,
) {
    companion object {
        fun from(ui: NavUi, options: RouteOptions) = DetourRouteContextKey(
            route = ui.route,
            destination = ui.destination,
            finalDestination = ui.finalDestination,
            stopsAhead = ui.stops.drop(ui.stopsPassed),
            navigating = ui.navigating,
            options = options,
        )
    }
}

/**
 * VM-only binding: combines only route-context changes and the existing motion-lock flow.
 * The initial context/motion emissions never invalidate a quote the rider has just started.
 */
internal fun bindDetourQuoteSignals(
    scope: CoroutineScope,
    navUi: Flow<NavUi>,
    options: Flow<RouteOptions>,
    motionLocked: Flow<Boolean>,
    initialKey: DetourRouteContextKey,
    onContextChanged: () -> Unit,
    onMotionChanged: (Boolean) -> Unit,
): Job = scope.launch {
    coroutineScope {
        launch {
            var last = initialKey
            combine(navUi, options) { ui, opts -> DetourRouteContextKey.from(ui, opts) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == last) return@collectLatest
                    last = key
                    onContextChanged()
                }
        }
        launch {
            motionLocked.drop(1).distinctUntilChanged().collect(onMotionChanged)
        }
    }
}

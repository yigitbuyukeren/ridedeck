package com.ridedeck.app

import com.ridedeck.app.data.DetourRouteContextKey
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.bindDetourQuoteSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure VM binding: fake flows only, no AndroidViewModel, GPS, HTTP or provider. */
class DetourQuoteVmBindingTest {
    private val origin = GeoPoint(41.0, 29.0)
    private val destination = Place("Dest", "", GeoPoint(41.01, 29.01))

    @Test fun initialEmissionIsSkippedAndOnlyRouteContextChangesInvalidate() = runTest {
        val nav = MutableStateFlow(NavUi(destination = destination, route = route(), navigating = true))
        val options = MutableStateFlow(RouteOptions())
        val motion = MutableStateFlow(false)
        var contextChanges = 0
        var motionChanges = 0
        val job = bindDetourQuoteSignals(
            scope = this,
            navUi = nav,
            options = options,
            motionLocked = motion,
            initialKey = DetourRouteContextKey.from(nav.value, options.value),
            onContextChanged = { contextChanges++ },
            onMotionChanged = { motionChanges++ },
        )
        advanceUntilIdle()
        assertEquals(0, contextChanges)

        // Progress/ETA churn must not invalidate a just-started quote.
        nav.value = nav.value.copy(maneuverIndex = 3, distToNextM = 12, remainingM = 900, remainingS = 80)
        advanceUntilIdle()
        assertEquals(0, contextChanges)

        nav.value = nav.value.copy(destination = Place("Other", "", GeoPoint(41.02, 29.02)))
        advanceUntilIdle()
        assertEquals(1, contextChanges)

        options.value = RouteOptions(avoidHighways = true)
        advanceUntilIdle()
        assertEquals(2, contextChanges)

        motion.value = true
        advanceUntilIdle()
        assertEquals(1, motionChanges)
        motion.value = true
        advanceUntilIdle()
        assertEquals(1, motionChanges)
        job.cancel()
    }

    @Test fun cancellingBindingScopeStopsBothCollectors() = runTest {
        val nav = MutableStateFlow(NavUi(destination = destination, route = route(), navigating = true))
        val options = MutableStateFlow(RouteOptions())
        val motion = MutableStateFlow(false)
        var changes = 0
        val job = bindDetourQuoteSignals(
            scope = this,
            navUi = nav,
            options = options,
            motionLocked = motion,
            initialKey = DetourRouteContextKey.from(nav.value, options.value),
            onContextChanged = { changes++ },
            onMotionChanged = { changes++ },
        )
        advanceUntilIdle()
        job.cancel()
        nav.value = nav.value.copy(destination = Place("Other", "", GeoPoint(41.02, 29.02)))
        motion.value = true
        advanceUntilIdle()
        assertEquals(0, changes)
        assertTrue(job.isCancelled)
    }

    private fun route(): Route = Route(
        shape = listOf(origin, destination.point),
        maneuvers = listOf(Maneuver(0, "", "", "", 0.0, 0, timeS = 10.0)),
        distanceM = 1_000.0,
        durationS = 10.0,
    )
}

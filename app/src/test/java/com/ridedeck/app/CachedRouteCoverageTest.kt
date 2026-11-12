package com.ridedeck.app

import com.ridedeck.app.data.CachedRouteCoverage
import com.ridedeck.app.data.CachedRouteMapCoverage
import com.ridedeck.app.data.CachedRoutePreview
import com.ridedeck.app.data.FuelFinder
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.Routing
import com.ridedeck.app.data.Speaker
import com.ridedeck.app.data.StopSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Advisory saved-region bounds coverage; no case may claim that tiles are available. */
class CachedRouteCoverageTest {
    private val at = GeoPoint(41.0, 29.0)

    private fun area(center: GeoPoint = at, radiusKm: Int = 15, name: String = "a") =
        OfflineArea(name, center, radiusKm, 1L)

    @Test
    fun missingMetadataOrShortGeometryStaysUnknown() {
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(emptyList(), listOf(at, GeoPoint(41.001, 29.0))))
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(area()), listOf(at)))
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(area()), emptyList()))
    }

    @Test
    fun routeInsideSavedBoundsIsOnlyBoundsAndNeverATileProof() {
        val route = listOf(GeoPoint(40.99, 29.0), at, GeoPoint(41.01, 29.0))

        val coverage = CachedRouteCoverage.assess(listOf(area()), route)

        assertEquals(CachedRouteMapCoverage.BOUNDS_ONLY, coverage)
        assertFalse(coverage.provesTileAvailability)
        CachedRouteMapCoverage.values().forEach { assertFalse(it.provesTileAvailability) }
    }

    @Test
    fun routeLeavingSavedBoundsIsOutside() {
        val route = listOf(at, GeoPoint(41.0, 29.5))

        assertEquals(CachedRouteMapCoverage.OUTSIDE_SAVED_BOUNDS, CachedRouteCoverage.assess(listOf(area()), route))
    }

    @Test
    fun segmentCrossingAGapBetweenTwoAreasIsOutsideEvenWhenBothEndsAreInside() {
        // A ends at lon 29.1785, B starts at 29.3214: both vertices are inside a region, the middle is not.
        val areas = listOf(area(name = "west"), area(center = GeoPoint(41.0, 29.5), name = "east"))
        val route = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0, 29.5))

        assertEquals(CachedRouteMapCoverage.OUTSIDE_SAVED_BOUNDS, CachedRouteCoverage.assess(areas, route))
    }

    @Test
    fun overlappingAreasCoverASegmentThatNeitherCoversAlone() {
        // A: lon 28.8215..29.1785, B: 29.0215..29.3785. The union covers 28.9..29.3, neither rectangle does.
        val areas = listOf(area(name = "west"), area(center = GeoPoint(41.0, 29.2), name = "east"))
        val route = listOf(GeoPoint(41.0, 28.9), GeoPoint(41.0, 29.3))

        assertEquals(CachedRouteMapCoverage.BOUNDS_ONLY, CachedRouteCoverage.assess(areas, route))
    }

    @Test
    fun antimeridianGeometryStaysUnknownInsteadOfBeingGuessed() {
        val crossingRoute = listOf(GeoPoint(41.0, 179.9), GeoPoint(41.0, -179.9))
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(area(center = GeoPoint(0.0, 0.0), radiusKm = 60)), crossingRoute))

        // A region whose requested rectangle runs past +180° is also outside the trusted schema.
        val wrappedArea = area(center = GeoPoint(41.0, 179.9))
        val localRoute = listOf(GeoPoint(41.0, 179.9), GeoPoint(41.0, 179.95))
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(wrappedArea), localRoute))
    }

    @Test
    fun untrustedMetadataOrPolarGeometryStaysUnknown() {
        val route = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0, 29.01))

        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(area(radiusKm = 0)), route))
        assertEquals(CachedRouteMapCoverage.UNKNOWN, CachedRouteCoverage.assess(listOf(area(center = GeoPoint(Double.NaN, 29.0))), route))
        assertEquals(
            CachedRouteMapCoverage.UNKNOWN,
            CachedRouteCoverage.assess(listOf(area()), listOf(GeoPoint(86.0, 29.0), GeoPoint(86.01, 29.0))),
        )
    }

    // ---- controller wiring -------------------------------------------------------------------

    private fun coverageRoute(): Route {
        val shape = (0..20).map { GeoPoint(41.0 + it * 0.0009, 29.0) }
        val maneuvers = listOf(
            Maneuver(1, "Depart", "", "Head north", 1500.0, 0),
            Maneuver(1, "Turn right", "Turn right ahead", "Turn right now", 500.0, 15),
            Maneuver(4, "Arrive", "", "You have arrived", 0.0, 20),
        )
        return Route(shape, maneuvers, 2000.0, 200.0)
    }

    @Test
    fun restorePublishesInjectedBoundsWithoutRoutingOrStarting() {
        val now = 1_000L
        val location = MutableStateFlow(
            LocationState(
                speedKmh = 0, fixAtMs = now, lat = 41.0, lon = 29.0,
                gpsProviderEnabled = true, locationPermissionGranted = true,
            ),
        )
        val routing = CoverageRouting()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val areas = MutableStateFlow<List<OfflineArea>>(listOf(area()))
        val nav = NavigationController(
            text = { "t$it" }, scope = scope, location = location, voice = CoverageSpeaker(),
            repo = routing, fuelFinder = CoverageFuel(), nowMs = { now },
            offlineAreas = areas,
        )
        try {
            val route = coverageRoute()
            val preview = CachedRoutePreview(Place("D", "", route.shape.last()), emptyList(), RouteOptions(), route)
            assertTrue(nav.restoreCachedPreview(preview))

            assertEquals(CachedRouteMapCoverage.BOUNDS_ONLY, nav.ui.value.cachedPreviewMapCoverage)
            assertFalse(nav.ui.value.cachedPreviewMapCoverage.provesTileAvailability)
            assertFalse(nav.ui.value.navigating)
            assertEquals(0, routing.requests)

            // Removing the saved region while the preview is visible must clear the advisory at once.
            areas.value = emptyList()
            assertEquals(CachedRouteMapCoverage.UNKNOWN, nav.ui.value.cachedPreviewMapCoverage)
            assertFalse(nav.ui.value.navigating)
            assertEquals(0, routing.requests)

            // A newly available region refreshes the same preview without routing or starting guidance.
            areas.value = listOf(area())
            assertEquals(CachedRouteMapCoverage.BOUNDS_ONLY, nav.ui.value.cachedPreviewMapCoverage)
            assertFalse(nav.ui.value.navigating)
            assertEquals(0, routing.requests)

            // A failed explicit refresh keeps the preview; its advisory must keep following area metadata.
            assertTrue(nav.refreshCachedPreview())
            assertTrue(nav.ui.value.cachedPreview)
            assertTrue(nav.ui.value.cachedPreviewRefreshFailed)
            areas.value = emptyList()
            assertEquals(CachedRouteMapCoverage.UNKNOWN, nav.ui.value.cachedPreviewMapCoverage)
            assertFalse(nav.ui.value.navigating)

            // The same restart must keep the GPS-freshness watcher live: a stale fix now blocks Start again.
            location.value = location.value.copy(fixAtMs = 0L)
            assertTrue(nav.ui.value.cachedPreviewStartBlocked)
            location.value = location.value.copy(fixAtMs = now)
            assertFalse(nav.ui.value.cachedPreviewStartBlocked)

            // A new explicit destination clears the cached-preview advice synchronously.
            nav.selectPlace(Place("New", "", GeoPoint(41.02, 29.0)))
            assertEquals(CachedRouteMapCoverage.UNKNOWN, nav.ui.value.cachedPreviewMapCoverage)
            assertFalse(nav.ui.value.cachedPreview)
        } finally {
            nav.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun restoreWithoutMetadataOrWithDistantRegionsStaysConservative() {
        val now = 1_000L
        val location = MutableStateFlow(
            LocationState(
                speedKmh = 0, fixAtMs = now, lat = 41.0, lon = 29.0,
                gpsProviderEnabled = true, locationPermissionGranted = true,
            ),
        )
        val route = coverageRoute()
        val preview = CachedRoutePreview(Place("D", "", route.shape.last()), emptyList(), RouteOptions(), route)

        fun assess(areas: MutableStateFlow<List<OfflineArea>>): CachedRouteMapCoverage {
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            val nav = NavigationController(
                text = { "t$it" }, scope = scope, location = location, voice = CoverageSpeaker(),
                repo = CoverageRouting(), fuelFinder = CoverageFuel(), nowMs = { now }, offlineAreas = areas,
            )
            try {
                assertTrue(nav.restoreCachedPreview(preview))
                return nav.ui.value.cachedPreviewMapCoverage
            } finally {
                nav.shutdown()
                scope.cancel()
            }
        }

        assertEquals(CachedRouteMapCoverage.UNKNOWN, assess(MutableStateFlow<List<OfflineArea>>(emptyList())))
        assertEquals(
            CachedRouteMapCoverage.OUTSIDE_SAVED_BOUNDS,
            assess(MutableStateFlow(listOf(area(center = GeoPoint(0.0, 0.0), radiusKm = 60)))),
        )
    }
}

private class CoverageRouting : Routing {
    var requests = 0

    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
        requests++
        return Result.failure(IllegalStateException("no network in a coverage test"))
    }

    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> = Result.success(emptyList())
}

private class CoverageFuel : FuelFinder {
    override suspend fun near(here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> =
        Result.success(emptyList())

    override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> =
        Result.success(emptyList())
}

private class CoverageSpeaker : Speaker {
    override fun speak(text: String) {}
    override fun stop() {}
    override fun shutdown() {}
}

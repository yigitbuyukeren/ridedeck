package com.ridedeck.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ridedeck.app.data.FrameThrottle
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.PositionSmoother
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteSnap
import com.ridedeck.app.data.hasFreshFix
import android.os.SystemClock
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

private const val SETTLE_MS = 700L
private const val COMPASS_BELOW_KMH = 8
private const val MARKER_MIN_INTERVAL_NS = 16_000_000L // 60 Hz lockstep with chase camera
private const val MARKER_MIN_DEG = 1e-6 // ~0.11 m
private const val MARKER_MIN_BEARING_DEG = 0.5

/**
 * Draws my position marker, and when [follow] is set the tilted chase camera, once per frame from a
 * [PositionSmoother] instead of once per GPS fix, so the ride looks continuous rather than hopping every second.
 *
 * While navigating, [route] lets [RouteSnap] draw the marker on the road when the fix is only a little
 * beside it. That correction is visual only: the smoother, guidance and off-route detection keep using
 * the raw fix and heading, so a real deviation or turn stays visible.
 */
@Composable
internal fun SmoothFollow(
    map: MapLibreMap?,
    mapView: MapView,
    styleReady: Boolean,
    me: LocationState,
    follow: Boolean,
    zoom: Double,
    tilt: Double,
    padTopFraction: Double,
    maxFps: Int,
    route: Route? = null,
    progressIndex: Int = 0,
    progressFraction: Double = 0.0,
) {
    val compass = rememberCompassHeading()
    val latestMe by rememberUpdatedState(me)
    val latestZoom by rememberUpdatedState(zoom)
    val latestTilt by rememberUpdatedState(tilt)
    val latestRoute by rememberUpdatedState(route)
    val latestProgressIndex by rememberUpdatedState(progressIndex)
    val latestProgressFraction by rememberUpdatedState(progressFraction)
    val smoother = remember { PositionSmoother() }
    val snapper = remember { RouteSnap() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(me.fixAtMs, styleReady) {
        val elapsed = SystemClock.elapsedRealtime()
        val canShowPosition = me.fixAtMs > 0L &&
            me.gpsProviderEnabled != false &&
            me.locationPermissionGranted != false &&
            me.lat.isFinite() && me.lon.isFinite()
        if (me.hasFreshFix(System.currentTimeMillis(), elapsed)) {
            val ageMs = if (me.elapsedRealtimeMs > 0L) elapsed - me.elapsedRealtimeMs else 0L
            smoother.onFix(me.lat, me.lon, me.speedKmh / 3.6, me.bearing, System.nanoTime() - ageMs * 1_000_000L)
        } else if (canShowPosition) {
            // Keep the last-known position marker visible without dead-reckoning when a fix ages past 5 s
            // (for example, while previewing a route, stopped at a light, or after a day/night style reload).
            smoother.onFix(me.lat, me.lon, 0.0, me.bearing, System.nanoTime())
        } else smoother.reset()
    }

    LaunchedEffect(map, styleReady, follow, maxFps, lifecycle) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapper.reset()
            val throttle = FrameThrottle(maxFps)
            var settleUntil = 0L
            var drawnLat = Double.NaN
            var drawnLon = Double.NaN
            var drawnBearing = Double.NaN
            var alignedBearing = Double.NaN
            var lastFrameNs = 0L
            var lastMarkerNs = 0L
            var wasFollowing = false
            while (true) {
                val now = withFrameNanos { it }
                if (!throttle.shouldDraw(now)) continue
                val f = smoother.step(now, m.cameraPosition.bearing) ?: continue
                val shown = snapper.display(
                    latestRoute, latestProgressIndex, latestProgressFraction,
                    GeoPoint(f.lat, f.lon), f.bearing, latestMe.speedMps.toDouble(),
                )
                val lat = shown.point.lat
                val lon = shown.point.lon
                // Riding: follow the smoothed 35m road chord when snapped on route so micro-node kinks
                // never twist the navigation chevron crooked relative to the road or chase camera.
                val moving = latestMe.speedKmh >= COMPASS_BELOW_KMH && latestMe.bearing != null
                val targetHeading = if (moving && shown.onRoute && shown.roadBearing != null) {
                    shown.roadBearing
                } else {
                    f.bearing
                }
                val dtS = if (lastFrameNs > 0L) ((now - lastFrameNs) / 1e9).coerceIn(0.0, 0.25) else 0.0
                lastFrameNs = now
                alignedBearing = if (alignedBearing.isNaN()) {
                    targetHeading
                } else {
                    val turn = com.ridedeck.app.data.shortestTurn(targetHeading - alignedBearing)
                    val k = 1.0 - kotlin.math.exp(-dtS / 0.25)
                    (alignedBearing + turn * k + 360.0) % 360.0
                }
                val markerBearing = if (moving) alignedBearing else compass.value?.toDouble() ?: alignedBearing
                val markerChanged = drawnLat.isNaN() ||
                    kotlin.math.abs(lat - drawnLat) >= MARKER_MIN_DEG ||
                    kotlin.math.abs(lon - drawnLon) >= MARKER_MIN_DEG ||
                    kotlin.math.abs(com.ridedeck.app.data.shortestTurn(markerBearing - drawnBearing)) >= MARKER_MIN_BEARING_DEG
                if (markerChanged && (lastMarkerNs == 0L || now - lastMarkerNs >= MARKER_MIN_INTERVAL_NS)) {
                    drawnLat = lat; drawnLon = lon; drawnBearing = markerBearing; lastMarkerNs = now
                    m.style?.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(
                        org.maplibre.geojson.FeatureCollection.fromFeature(
                            Feature.fromGeometry(Point.fromLngLat(lon, lat)).also { it.addNumberProperty("bearing", markerBearing) }
                        )
                    )
                }
                if (!follow) { wasFollowing = false; continue }

                val camera = CameraPosition.Builder()
                    .target(LatLng(lat, lon))
                    .zoom(latestZoom)
                    .tilt(latestTilt)
                    .bearing(alignedBearing)
                    .padding(0.0, mapView.height * padTopFraction, 0.0, 0.0)
                    .build()
                if (!wasFollowing) {
                    // Entering follow mode: one eased move from wherever the camera is, then per-frame tracking.
                    wasFollowing = true
                    settleUntil = now + SETTLE_MS * 1_000_000L
                    m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), SETTLE_MS.toInt())
                } else if (now >= settleUntil) {
                    m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                }
            }
        }
    }
}

package com.ridedeck.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ridedeck.app.data.FrameThrottle
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.PositionSmoother
import com.ridedeck.app.data.hasFreshFix
import android.os.SystemClock
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

private const val SETTLE_MS = 700L

/**
 * Draws my position marker, and when [follow] is set the tilted chase camera, once per frame from a
 * [PositionSmoother] instead of once per GPS fix, so the ride looks continuous rather than hopping every second.
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
) {
    val smoother = remember { PositionSmoother() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(me.fixAtMs) {
        val elapsed = SystemClock.elapsedRealtime()
        if (me.hasFreshFix(System.currentTimeMillis(), elapsed)) {
            val ageMs = if (me.elapsedRealtimeMs > 0L) elapsed - me.elapsedRealtimeMs else 0L
            smoother.onFix(me.lat, me.lon, me.speedKmh / 3.6, me.bearing, System.nanoTime() - ageMs * 1_000_000L)
        } else smoother.reset()
    }

    LaunchedEffect(map, styleReady, follow, maxFps, lifecycle) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val throttle = FrameThrottle(maxFps)
            var settleUntil = 0L
            var drawnLat = Double.NaN
            var drawnLon = Double.NaN
            var drawnBearing = Double.NaN
            var wasFollowing = false
            while (true) {
                val now = withFrameNanos { it }
                if (!throttle.shouldDraw(now)) continue
                val f = smoother.step(now, m.cameraPosition.bearing) ?: continue
                if (f.lat == drawnLat && f.lon == drawnLon && f.bearing == drawnBearing) continue
                drawnLat = f.lat; drawnLon = f.lon; drawnBearing = f.bearing

                m.style?.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(Point.fromLngLat(f.lon, f.lat))
                if (!follow) { wasFollowing = false; continue }

                val camera = CameraPosition.Builder()
                    .target(LatLng(f.lat, f.lon))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(f.bearing)
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

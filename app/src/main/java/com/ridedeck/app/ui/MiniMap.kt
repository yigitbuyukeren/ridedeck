package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val MINI_FULL_FPS = 30
private const val MINI_LOW_POWER_FPS = 15

/**
 * The dashboard card's own small live map: route line and position, camera following the heading.
 * It is only composed while guidance runs and the full map screen is closed, and runs at a low frame
 * rate (lower still when the phone is hot), so it costs little battery.
 */
@Composable
fun MiniMap(
    route: Route?,
    progressIndex: Int,
    progressFraction: Double,
    destination: Place?,
    me: LocationState,
    isDay: Boolean,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    LaunchedEffect(lowPower) { mapView.setMaximumFps(if (lowPower) MINI_LOW_POWER_FPS else MINI_FULL_FPS) }

    MapLifecycleEffect(mapView)
    MarkerRefresh(map, styleReady)


    LaunchedEffect(map, isDay) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        m.setStyle(Style.Builder().fromUri(styleUrl(isDay))) { style ->
            addMapLayers(style)
            styleReady = true
        }
    }

    LaunchedEffect(styleReady, route, progressIndex, progressFraction) {
        val style = map?.style?.takeIf { styleReady } ?: return@LaunchedEffect
        showRoute(style, route, progressIndex, progressFraction)
    }

    LaunchedEffect(styleReady, destination) {
        val style = map?.style?.takeIf { styleReady } ?: return@LaunchedEffect
        val destSource = style.getSourceAs<GeoJsonSource>(SRC_DEST)
        if (destination != null) destSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(destination.point.lon, destination.point.lat)))
        else destSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }

    SmoothFollow(
        map, mapView, styleReady, me,
        follow = true,
        zoom = 16.0,
        tilt = 50.0,
        padTopFraction = 0.3,
        maxFps = if (lowPower) MINI_LOW_POWER_FPS else MINI_FULL_FPS,
        route = route,
        progressIndex = progressIndex,
        progressFraction = progressFraction,
    )

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.also { mv ->
                mv.getMapAsync { m ->
                    // A glance, not a control: taps and drags go to the card, which opens the full map.
                    m.uiSettings.setAllGesturesEnabled(false)
                    // MapLibre's own logo and (i) button look out of place on a dark card; the required credit is drawn by the card instead.
                    m.uiSettings.isLogoEnabled = false
                    m.uiSettings.isAttributionEnabled = false
                    map = m
                }
            }
        },
    )
}

/**
 * The map data credit that OpenFreeMap and OpenStreetMap ask for. It always draws its own dark
 * backing so it stays readable over light day tiles, and caps font scaling so the fixed footer
 * cannot grow into the navigation content.
 */
@Composable
fun MapCredit(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scale = minOf(density.fontScale, 1.2f)
    val fontSize = with(density) { (9f * scale / density.fontScale).sp }
    val lineHeight = with(density) { (12f * scale / density.fontScale).sp }
    Box(
        modifier = modifier
            .background(Color(0xE6000000), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            "OpenFreeMap © OpenMapTiles · © OpenStreetMap",
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = Color.White,
        )
    }
}

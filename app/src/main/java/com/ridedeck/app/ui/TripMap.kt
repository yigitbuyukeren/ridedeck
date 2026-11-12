package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.min

private const val SRC_TRIP_LINES = "trip-lines"
private const val SRC_TRIP_START = "trip-start"
private const val SRC_TRIP_END = "trip-end"
private const val LOCAL_STYLE_FALLBACK_MS = 3_500L

private sealed interface TripMapLoad {
    data object Loading : TripMapLoad
    data object Empty : TripMapLoad
    data object Error : TripMapLoad
    data class Ready(val trace: TripTrace) : TripMapLoad
}

/** Narrow internal seam for native tests; production passes no callback. */
internal data class TripMapSnapshot(
    val hasMap: Boolean,
    val styleReady: Boolean,
    val dayStyle: Boolean,
    val localOnly: Boolean,
    val lineLayer: Boolean,
    val startLayer: Boolean,
    val endLayer: Boolean,
    val pointCount: Int,
    val lineFeatureCount: Int,
    val startPointCount: Int,
    val endPointCount: Int,
    val mapWidth: Int,
    val mapHeight: Int,
    val frameRendered: Boolean,
    val cameraFitted: Boolean,
    val cameraTarget: LatLng?,
    val cameraZoom: Double,
)

/**
 * Lazily loads saved points only after the explicit map action. The point read is cancelled with
 * this composition; trace preparation runs off the UI thread. A map is never constructed for an
 * empty or failed load.
 */
@Composable
internal fun TripMapPanel(
    tripId: Long,
    loadPoints: suspend () -> List<TripPoint>,
    isDay: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentLoad by rememberUpdatedState(loadPoints)
    val online = rememberOnline(LocalContext.current)
    var attempt by remember(tripId) { mutableIntStateOf(0) }
    val state by produceState<TripMapLoad>(TripMapLoad.Loading, tripId, attempt) {
        value = TripMapLoad.Loading
        value = try {
            val points = withContext(Dispatchers.IO) { currentLoad() }
            ensureActive()
            if (points.isEmpty()) {
                TripMapLoad.Empty
            } else {
                val trace = withContext(Dispatchers.Default) { TripTrace.from(points) }
                ensureActive()
                if (trace.pointCount == 0) TripMapLoad.Empty else TripMapLoad.Ready(trace)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TripMapLoad.Error
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TripMapHeader(onClose)
        when (val load = state) {
            TripMapLoad.Loading -> TripMapMessage(stringResource(R.string.trip_map_loading), modifier = Modifier.weight(1f))
            TripMapLoad.Empty -> TripMapMessage(stringResource(R.string.trip_map_empty), modifier = Modifier.weight(1f))
            TripMapLoad.Error -> TripMapMessage(
                stringResource(R.string.trip_map_error),
                Modifier.weight(1f),
                action = stringResource(R.string.trip_map_retry),
                onAction = { attempt++ },
            )
            is TripMapLoad.Ready -> TripMapReady(load.trace, isDay, online, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun TripMapHeader(onClose: () -> Unit) {
    val palette = LocalMotoPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(
                modifier = Modifier.heightIn(max = 56.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.trip_map_title), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Text(stringResource(R.string.trip_map_privacy), fontSize = 13.sp, lineHeight = 15.6.sp, color = palette.muted)
            }
        }
        Box(
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(palette.cardHigh).hapticClickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, stringResource(R.string.trip_map_close), tint = palette.text, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun TripMapMessage(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalMotoPalette.current
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, fontSize = 17.sp, lineHeight = 20.4.sp, color = palette.muted)
            if (action != null && onAction != null) {
                PillButton(action, MotoColors.Blue, onAction, minHeight = 64.dp)
            }
        }
    }
}

@Composable
internal fun TripMapReady(
    trace: TripTrace,
    isDay: Boolean,
    online: Boolean,
    modifier: Modifier = Modifier,
    onSnapshot: ((TripMapSnapshot) -> Unit)? = null,
) {
    val palette = LocalMotoPalette.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trace.wasSimplified || trace.gaps.isNotEmpty()) {
            Column(
                modifier = Modifier.heightIn(max = 32.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (trace.wasSimplified) {
                    Text(
                        stringResource(R.string.trip_map_simplified, trace.sampledPointCount, trace.pointCount),
                        fontSize = 13.sp,
                        lineHeight = 15.6.sp,
                        color = palette.muted,
                    )
                }
                if (trace.gaps.isNotEmpty()) {
                    Text(
                        stringResource(R.string.trip_map_gaps, trace.gaps.size),
                        fontSize = 13.sp,
                        lineHeight = 15.6.sp,
                        color = palette.muted,
                    )
                }
            }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 64.dp)
                .clip(RoundedCornerShape(20.dp)).background(palette.card).testTag("trip-map-viewport"),
        ) {
            TripMapCanvas(trace, isDay, online, modifier = Modifier.fillMaxSize(), onSnapshot = onSnapshot)
        }
        MapCredit(Modifier.padding(start = 2.dp).testTag("trip-map-credit"))
    }
}

@Composable
internal fun TripMapCanvas(
    trace: TripTrace,
    isDay: Boolean,
    online: Boolean,
    modifier: Modifier = Modifier,
    onSnapshot: ((TripMapSnapshot) -> Unit)? = null,
) {
    val context = LocalContext.current
    val snapshotSink by rememberUpdatedState(onSnapshot)
    val currentIsDay by rememberUpdatedState(isDay)
    val currentTrace by rememberUpdatedState(trace)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var localOnly by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var frameRendered by remember { mutableStateOf(false) }
    var cameraFitted by remember { mutableStateOf(false) }
    var lastCamera by remember { mutableStateOf<CameraPosition?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var disposed by remember { mutableStateOf(false) }
    var fitted by remember(trace) { mutableStateOf(false) }
    var fittedSize by remember(trace) { mutableStateOf(IntSize.Zero) }

    fun publishSnapshot(m: MapLibreMap, local: Boolean) {
        val style = m.style
        val camera = m.cameraPosition
        snapshotSink?.invoke(
            TripMapSnapshot(
                hasMap = true,
                styleReady = styleReady,
                dayStyle = currentIsDay,
                localOnly = local,
                lineLayer = style?.getLayer("trip-line") != null,
                startLayer = style?.getLayer("trip-start") != null,
                endLayer = style?.getLayer("trip-end") != null,
                pointCount = currentTrace.pointCount,
                lineFeatureCount = tripLineFeatures(currentTrace).size,
                startPointCount = if (currentTrace.start != null) 1 else 0,
                endPointCount = if (currentTrace.end != null) 1 else 0,
                mapWidth = mapSize.width,
                mapHeight = mapSize.height,
                frameRendered = frameRendered,
                cameraFitted = cameraFitted,
                cameraTarget = camera.target,
                cameraZoom = camera.zoom,
            )
        )
    }

    MapLifecycleEffect(mapView)
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            generation++
        }
    }
    DisposableEffect(mapView, onSnapshot != null) {
        if (onSnapshot == null) return@DisposableEffect onDispose {}
        val frameListener = object : MapView.OnDidBecomeIdleListener {
            override fun onDidBecomeIdle() {
                val frameGeneration = generation
                mapView.post {
                    if (frameGeneration == generation && styleReady && cameraFitted && !disposed && !mapView.isDestroyed) {
                        frameRendered = true
                        map?.let { publishSnapshot(it, localOnly) }
                    }
                }
            }
        }
        mapView.addOnDidBecomeIdleListener(frameListener)
        onDispose { mapView.removeOnDidBecomeIdleListener(frameListener) }
    }

    LaunchedEffect(map, isDay, online, trace) {
        val m = map ?: return@LaunchedEffect
        val token = ++generation
        // Keep the last good camera even if a previous style request is still in flight.
        val restoreCamera = if (styleReady) m.cameraPosition else lastCamera
        styleReady = false
        frameRendered = false
        cameraFitted = fitted
        fun draw(style: Style, callbackToken: Int, local: Boolean) {
            if (callbackToken != generation || disposed || mapView.isDestroyed) return
            addTripLayers(style, trace)
            if (restoreCamera != null) {
                m.moveCamera(CameraUpdateFactory.newCameraPosition(restoreCamera))
                lastCamera = m.cameraPosition
            }
            localOnly = local
            styleReady = true
            publishSnapshot(m, local)
        }
        if (online) {
            m.setStyle(Style.Builder().fromUri(styleUrl(isDay))) { style -> draw(style, token, local = false) }
            // A style request can fail without a callback. Invalidate the online callback before
            // installing a local background so a late response cannot add layers to a dead style.
            delay(LOCAL_STYLE_FALLBACK_MS)
            if (!styleReady && token == generation && !disposed && !mapView.isDestroyed) {
                val fallbackToken = ++generation
                m.setStyle(Style.Builder().fromJson(localTripStyle(isDay))) { style ->
                    draw(style, fallbackToken, local = true)
                }
            }
        } else {
            m.setStyle(Style.Builder().fromJson(localTripStyle(isDay))) { style ->
                draw(style, token, local = true)
            }
        }
    }

    LaunchedEffect(map, styleReady, trace, mapSize) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady || (fitted && fittedSize == mapSize) || disposed || mapView.isDestroyed) return@LaunchedEffect
        if (mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect
        val points = trace.segments.flatten().ifEmpty { listOfNotNull(trace.start, trace.end) }
        if (points.isEmpty()) return@LaunchedEffect
        fitted = true
        val first = points.first()
        val allSame = points.all { it.lat == first.lat && it.lon == first.lon }
        if (points.size == 1 || allSame) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lon), 15.0))
        } else {
            // Padding is capped by the actual pane so a narrow detail pane never gets a degenerate fit.
            val shortSide = min(mapSize.width, mapSize.height)
            val padding = min(80, shortSide / 6).coerceAtLeast(16)
            val bounds = LatLngBounds.Builder().apply {
                points.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding, padding, padding, padding))
        }
        lastCamera = m.cameraPosition
        fittedSize = mapSize
        cameraFitted = true
        frameRendered = false
        publishSnapshot(m, localOnly)
    }

    Column(modifier = modifier) {
        if (localOnly) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 32.dp)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("trip-map-notice"),
            ) {
                Text(
                    stringResource(R.string.trip_map_local_only),
                    fontSize = 11.sp,
                    lineHeight = 13.2.sp,
                    color = Color.White,
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 64.dp)
                .onSizeChanged {
                    if (mapSize != it) {
                        mapSize = it
                        cameraFitted = false
                        frameRendered = false
                    }
                }
                .testTag("trip-map-native"),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    mapView.also { mv ->
                        mv.getMapAsync { m ->
                            if (disposed || mv.isDestroyed) return@getMapAsync
                            map = m
                            m.uiSettings.isLogoEnabled = false
                            m.uiSettings.isAttributionEnabled = false
                            m.addOnCameraIdleListener {
                                if (styleReady && !disposed) lastCamera = m.cameraPosition
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Pure geometry builder shared by production drawing and JVM tests; one feature per non-gap segment. */
internal fun tripLineFeatures(trace: TripTrace): List<Feature> =
    trace.segments
        .filter { it.size >= 2 }
        .map { segment ->
            Feature.fromGeometry(LineString.fromLngLats(segment.map { Point.fromLngLat(it.lon, it.lat) }))
        }

private fun addTripLayers(style: Style, trace: TripTrace) {
    val lines = tripLineFeatures(trace)
    style.addSource(GeoJsonSource(SRC_TRIP_LINES))
    style.getSourceAs<GeoJsonSource>(SRC_TRIP_LINES)?.setGeoJson(FeatureCollection.fromFeatures(lines))
    style.addLayer(
        LineLayer("trip-line-casing", SRC_TRIP_LINES).withProperties(
            lineColor("#06306B"), lineWidth(10f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        LineLayer("trip-line", SRC_TRIP_LINES).withProperties(
            lineColor("#0A84FF"), lineWidth(7f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        )
    )

    style.addSource(GeoJsonSource(SRC_TRIP_START))
    val startFeatures: List<Feature> = trace.start?.let { listOf(pointFeature(it)) } ?: emptyList()
    style.getSourceAs<GeoJsonSource>(SRC_TRIP_START)?.setGeoJson(FeatureCollection.fromFeatures(startFeatures))
    style.addLayer(
        CircleLayer("trip-start", SRC_TRIP_START).withProperties(
            circleColor("#30D158"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
        )
    )
    style.addSource(GeoJsonSource(SRC_TRIP_END))
    val endFeatures: List<Feature> = trace.end?.let { listOf(pointFeature(it)) } ?: emptyList()
    style.getSourceAs<GeoJsonSource>(SRC_TRIP_END)?.setGeoJson(FeatureCollection.fromFeatures(endFeatures))
    style.addLayer(
        CircleLayer("trip-end", SRC_TRIP_END).withProperties(
            circleColor("#FF453A"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
        )
    )
}

private fun pointFeature(point: GeoPoint): Feature =
    Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat))

/** A tiny local style: no tile source and no download, used only when online style is unavailable. */
private fun localTripStyle(isDay: Boolean): String {
    val background = if (isDay) "#E8EEF7" else "#0B1220"
    return """{"version":8,"sources":{},"layers":[{"id":"trip-bg","type":"background","paint":{"background-color":"$background"}}]}"""
}

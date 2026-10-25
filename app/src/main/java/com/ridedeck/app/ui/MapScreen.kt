package com.ridedeck.app.ui

import com.ridedeck.app.util.installedNavApps
import com.ridedeck.app.util.startExternalNav
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import com.ridedeck.app.util.Units
import com.ridedeck.app.data.SpeedLimitMath
import com.ridedeck.app.data.Geo
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.FuelUi
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.NavigationController
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.StopKind
import com.ridedeck.app.data.VehicleProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
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

internal fun styleUrl(isDay: Boolean) =
    "https://tiles.openfreemap.org/styles/" + if (isDay) "bright" else "dark"
internal const val SRC_ROUTE = "route"
internal const val SRC_DEST = "dest"
internal const val SRC_ME = "me"
internal const val SRC_FUEL = "fuel"

/** No typing while riding: the search box locks above this speed. */
private const val SEARCH_LOCK_KMH = 5
private const val SAVED_SPOT_M = 30.0
private const val FULL_FPS = 60
private const val LOW_POWER_FPS = 20

@Composable
fun MapScreen(
    nav: NavigationController,
    locationState: StateFlow<LocationState>,
    speedKmh: Int,
    speedShown: Int? = null,
    speedLimit: Int?,
    isDay: Boolean,
    lowPower: Boolean,
    places: List<SavedPlace>,
    onSavePlace: (PlaceKind, Place) -> Unit,
    onRemovePlace: (SavedPlace) -> Unit,
    onMic: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recents: List<Place> = emptyList(),
    onClearRecents: () -> Unit = {},
) {
    val ui by nav.ui.collectAsStateWithLifecycle()
    val fuel by nav.fuel.collectAsStateWithLifecycle()
    val options by nav.options.collectAsStateWithLifecycle()
    val me by locationState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Place?>(null) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(speedKmh >= SEARCH_LOCK_KMH) { if (speedKmh >= SEARCH_LOCK_KMH) typing = false }

    // A hot phone gets a calmer map: fewer frames per second.
    LaunchedEffect(lowPower) { mapView.setMaximumFps(if (lowPower) LOW_POWER_FPS else FULL_FPS) }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) { destroyed = true; mapView.onDestroy() }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                destroyed = true
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
    }

    // (Re)load the style when the map is ready or the day/night theme flips; layers are re-added on each load.
    LaunchedEffect(map, isDay) {
        val m = map ?: return@LaunchedEffect
        styleReady = false
        m.setStyle(Style.Builder().fromUri(styleUrl(isDay))) { style ->
            addMapLayers(style)
            styleReady = true
        }
    }

    // My position marker and the navigation chase camera are drawn per frame (see SmoothFollow).
    SmoothFollow(map, mapView, styleReady, me, follow = ui.navigating, zoom = 17.0, tilt = 55.0, padTopFraction = 0.45)

    LaunchedEffect(styleReady, me.fixAtMs, ui.navigating) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady || me.fixAtMs == 0L) return@LaunchedEffect
        val target = LatLng(me.lat, me.lon)
        when {
            ui.navigating -> {}
            !centeredOnce -> {
                centeredOnce = true
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 15.0))
            }
        }
    }

    // Route line and destination pin.
    LaunchedEffect(styleReady, ui.route, ui.destination) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        val route = ui.route
        val line = route?.let { r -> LineString.fromLngLats(r.shape.map { Point.fromLngLat(it.lon, it.lat) }) }
        val routeSource = style.getSourceAs<GeoJsonSource>(SRC_ROUTE)
        if (line != null) routeSource?.setGeoJson(Feature.fromGeometry(line))
        else routeSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        val dest = ui.destination
        val destSource = style.getSourceAs<GeoJsonSource>(SRC_DEST)
        if (dest != null) destSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(dest.point.lon, dest.point.lat)))
        else destSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        if (route != null && !ui.navigating) {
            val bounds = LatLngBounds.Builder().apply {
                route.shape.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            // Keep the route clear of the panel: on the left when wide, along the top when upright.
            val padding = if (portrait) intArrayOf(140, 640, 260, 200) else intArrayOf(520, 140, 140, 140)
            m.animateCamera(
                CameraUpdateFactory.newLatLngBounds(bounds, padding[0], padding[1], padding[2], padding[3]), 800
            )
        }
    }

    // Fuel station markers while the list is open.
    LaunchedEffect(styleReady, fuel.stations, fuel.visible) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val features = if (fuel.visible) fuel.stations.map { Feature.fromGeometry(Point.fromLngLat(it.place.point.lon, it.place.point.lat)) } else emptyList()
        m.style?.getSourceAs<GeoJsonSource>(SRC_FUEL)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // Leaving navigation: flatten the camera back to a north-up overview.
    LaunchedEffect(ui.navigating) {
        val m = map ?: return@LaunchedEffect
        if (!ui.navigating && me.fixAtMs > 0 && centeredOnce) {
            val camera = CameraPosition.Builder()
                .target(LatLng(me.lat, me.lon)).zoom(15.0).tilt(0.0).bearing(0.0)
                .padding(0.0, 0.0, 0.0, 0.0).build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 700)
        }
    }

    Box(modifier = modifier.clip(RoundedCornerShape(28.dp)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(28.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.also { mv ->
                    mv.getMapAsync { m ->
                        map = m
                        m.uiSettings.isRotateGesturesEnabled = true
                        m.uiSettings.isLogoEnabled = false
                        m.uiSettings.isAttributionEnabled = false
                        m.addOnMapLongClickListener { latLng ->
                            nav.selectPoint(GeoPoint(latLng.latitude, latLng.longitude))
                            true
                        }
                    }
                }
            },
        )

        // Left side panel, CarPlay style.
        Column(
            modifier = Modifier.fillMaxHeight()
                // Upright: the panel takes the width, leaving the round buttons on the right free.
                .then(if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) Modifier.fillMaxWidth().padding(end = 84.dp) else Modifier.width(380.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val chosen = saving
            when {
                chosen != null -> SaveChooser(
                    place = chosen,
                    onPick = { kind -> onSavePlace(kind, chosen); saving = null },
                    onCancel = { saving = null },
                )
                fuel.visible -> FuelPanel(
                    fuel = fuel,
                    profile = options.profile,
                    guiding = ui.navigating,
                    onGo = nav::selectPlace,
                    onAddStop = nav::detourVia,
                    onRetry = nav::findFuel,
                    onClose = nav::closeFuel,
                )
                ui.navigating -> ManeuverBanner(ui)
                ui.arrived -> ArrivedCard(
                    next = ui.finalDestination,
                    onContinue = nav::continueToFinal,
                    onDone = nav::stopNavigation,
                )
                ui.route != null -> RouteCard(
                    ui, options, onOptions = nav::setOptions,
                    canOpenExternal = speedKmh < SEARCH_LOCK_KMH,
                    onStart = nav::startNavigation, onCancel = nav::stopNavigation,
                    saved = ui.destination?.let { d -> places.any { Geo.distance(it.place.point, d.point) < SAVED_SPOT_M } } == true,
                    onSave = { ui.destination?.let { saving = it } },
                )
                else -> SearchPanel(
                    ui = ui,
                    locked = speedKmh >= SEARCH_LOCK_KMH,
                    typing = typing,
                    onFocus = { typing = true },
                    onSearch = { typing = false; nav.search() },
                    places = places,
                    onSelect = nav::selectPlace,
                    onSave = { saving = it },
                    onRemovePlace = onRemovePlace,
                    onMic = onMic,
                    recents = recents,
                    onClearRecents = onClearRecents,
                    onSuggestion = { typing = false; nav.onQueryChange(it); nav.search() },
                )
            }
            if (ui.rerouting) Chip(stringResource(R.string.rerouting_chip), MotoColors.Orange)
        }

        if (ui.navigating) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                    .then(if (portrait) Modifier.fillMaxWidth().padding(end = 84.dp) else Modifier.width(356.dp)),
            ) { TripBar(ui, onStop = nav::stopNavigation) }
        }

        // The keyboard covers the lower right of an upright screen: the round buttons wait until it is closed.
        if (!(typing && portrait)) Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            RoundIconButton(Icons.Rounded.Close, onClick = onClose)
            if (!ui.arrived) {
                RoundIconButton(if (options.profile == VehicleProfile.BICYCLE) Icons.Rounded.PedalBike else Icons.Rounded.LocalGasStation, onClick = { if (fuel.visible) nav.closeFuel() else nav.findFuel() })
            }
            if (!ui.navigating) {
                RoundIconButton(Icons.Rounded.MyLocation, onClick = {
                    val m = map
                    if (m != null && me.fixAtMs > 0) {
                        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.lat, me.lon), 16.0), 600)
                    }
                })
            }
            if (speedLimit != null) SpeedLimitSign(speedLimit, size = 64.dp)
            SpeedChip(speedShown, over = SpeedLimitMath.isOver(speedKmh, speedLimit))
        }
        MapCredit(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp))

        // The app's own keyboard: it covers the lower part of the map, never resizes anything.
        if (typing && speedKmh < SEARCH_LOCK_KMH) {
            SearchKeyboard(
                query = ui.query,
                onQuery = nav::onQueryChange,
                onSearch = { typing = false; nav.search() },
                onClose = { typing = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

internal fun addMapLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_ROUTE))
    style.addLayer(
        LineLayer("route-casing", SRC_ROUTE).withProperties(
            lineColor("#06306B"), lineWidth(11f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addLayer(
        LineLayer("route-line", SRC_ROUTE).withProperties(
            lineColor("#0A84FF"), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        )
    )
    style.addSource(GeoJsonSource(SRC_DEST))
    style.addLayer(
        CircleLayer("dest-pin", SRC_DEST).withProperties(
            circleColor("#FF453A"), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        )
    )
    style.addSource(GeoJsonSource(SRC_FUEL))
    style.addLayer(
        CircleLayer("fuel-pin", SRC_FUEL).withProperties(
            circleColor("#FF9F0A"), circleRadius(10f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        )
    )
    style.addSource(GeoJsonSource(SRC_ME))
    style.addLayer(CircleLayer("me-halo", SRC_ME).withProperties(circleColor("#0A84FF"), circleRadius(18f), circleOpacity(0.25f)))
    style.addLayer(
        CircleLayer("me-dot", SRC_ME).withProperties(
            circleColor("#0A84FF"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        )
    )
}

// ---- panel pieces -------------------------------------------------------------------------

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE6101114))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(22.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun SearchPanel(
    ui: NavUi,
    locked: Boolean,
    typing: Boolean,
    onFocus: () -> Unit,
    onSearch: () -> Unit,
    places: List<SavedPlace>,
    onSelect: (Place) -> Unit,
    onSave: (Place) -> Unit,
    onRemovePlace: (SavedPlace) -> Unit,
    onMic: () -> Unit,
    recents: List<Place>,
    onClearRecents: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The query is typed on the app's own keyboard; this box just shows it and opens the keyboard.
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(MotoColors.PanelHigh)
                    .clickable(enabled = !locked, onClick = onFocus).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MotoColors.Gray, modifier = Modifier.size(26.dp))
                Box(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                    if (ui.query.isEmpty()) Text(stringResource(R.string.where_to), fontSize = 20.sp, color = MotoColors.Gray, maxLines = 1)
                    else Text(ui.query + if (typing) "▏" else "", fontSize = 20.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MicButton(onMic, Modifier.padding(start = 8.dp), size = 52.dp)
                if (ui.query.isNotEmpty()) {
                    Box(
                        modifier = Modifier.padding(start = 8.dp).height(52.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (locked) MotoColors.Gray else MotoColors.Blue)
                            .pressClickable(enabled = !locked, onClick = onSearch).padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.search), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
            when {
                ui.loading -> Text(stringResource(R.string.route_computing), fontSize = 15.sp, color = MotoColors.Gray)
                ui.searching -> Text(stringResource(R.string.searching), fontSize = 15.sp, color = MotoColors.Gray)
                ui.error != null -> Text(ui.error, fontSize = 15.sp, color = MotoColors.Orange)
                locked -> Text(stringResource(R.string.search_locked), fontSize = 14.sp, color = MotoColors.Orange)
                ui.results.isEmpty() && ui.query.isEmpty() ->
                    Text(stringResource(if (places.isEmpty()) R.string.saved_hint else R.string.hint_long_press), fontSize = 14.sp, color = MotoColors.Gray)
            }
            val suggestion = ui.suggestion
            if (suggestion != null && ui.results.isEmpty() && !ui.searching) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(MotoColors.PanelHigh)
                        .pressClickable(enabled = !locked, onClick = { onSuggestion(suggestion) }).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(stringResource(R.string.did_you_mean_fmt, suggestion), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MotoColors.Blue) }
            }
            if (ui.results.isEmpty() && !ui.loading && !ui.searching && ui.error != null) {
                Text(stringResource(R.string.hint_long_press), fontSize = 14.sp, color = MotoColors.Gray)
            }
            val resultsFor = ui.resultsFor
            if (resultsFor != null && ui.results.isNotEmpty()) {
                Text(stringResource(R.string.search_results_for_fmt, resultsFor), fontSize = 14.sp, color = MotoColors.Gray)
            }
            if (ui.results.isEmpty() && !ui.loading) {
                SavedTiles(places, onGo = onSelect, onRemove = onRemovePlace)
                if (recents.isNotEmpty()) RecentTiles(recents, onGo = onSelect, onClear = onClearRecents)
            }
            if (ui.results.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(ui.results) { place ->
                        val saved = places.any { Geo.distance(it.place.point, place.point) < SAVED_SPOT_M }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelect(place) }.padding(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Text(place.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (place.detail.isNotBlank()) {
                                    Text(place.detail, fontSize = 14.sp, color = MotoColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box(Modifier.size(68.dp).clickable { onSave(place) }, contentAlignment = Alignment.Center) {
                                Icon(
                                    if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                                    tint = if (saved) MotoColors.Orange else MotoColors.Gray, modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The last chosen destinations as tiles, with a last tile that deletes the history. */
@Composable
private fun RecentTiles(recents: List<Place>, onGo: (Place) -> Unit, onClear: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recents) { place ->
            Box(
                modifier = Modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(MotoColors.PanelHigh)
                    .pressClickable(onClick = { onGo(place) }).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(place.name, fontSize = 17.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item {
            Box(
                modifier = Modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).pressClickable(onClick = onClear).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.recents_clear), fontSize = 15.sp, color = MotoColors.Gray) }
        }
    }
}

/** Home, work and favorites as big tiles: one tap to route, long press then tap again to delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTiles(places: List<SavedPlace>, onGo: (Place) -> Unit, onRemove: (SavedPlace) -> Unit) {
    var confirm by remember { mutableStateOf<SavedPlace?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { delay(3000); confirm = null } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(places) { sp ->
            val pending = confirm == sp
            Row(
                modifier = Modifier.height(68.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (pending) MotoColors.Red else MotoColors.PanelHigh)
                    .combinedClickable(
                        onClick = { if (pending) { onRemove(sp); confirm = null } else onGo(sp.place) },
                        onLongClick = { confirm = sp },
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (sp.kind) {
                        PlaceKind.HOME -> Icons.Rounded.Home
                        PlaceKind.WORK -> Icons.Rounded.Work
                        PlaceKind.PARKED -> Icons.Rounded.LocalParking
                        PlaceKind.FAVORITE -> Icons.Rounded.Star
                    },
                    null, tint = Color.White, modifier = Modifier.size(26.dp),
                )
                Column(modifier = Modifier.padding(start = 10.dp).widthIn(max = 130.dp)) {
                    Text(
                        if (pending) stringResource(R.string.saved_delete_confirm) else savedLabel(sp),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Home/Work show their address, the parked bike shows when it was left.
                    val sub = when (sp.kind) { PlaceKind.HOME, PlaceKind.WORK -> sp.place.name; PlaceKind.PARKED -> sp.place.detail; PlaceKind.FAVORITE -> "" }
                    if (!pending && sub.isNotBlank()) {
                        Text(sub, fontSize = 12.sp, color = Color(0xB3FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun savedLabel(sp: SavedPlace): String = when (sp.kind) {
    PlaceKind.HOME -> stringResource(R.string.saved_home)
    PlaceKind.WORK -> stringResource(R.string.saved_work)
    PlaceKind.PARKED -> stringResource(R.string.saved_parked)
    PlaceKind.FAVORITE -> sp.place.name
}

@Composable
private fun SaveChooser(place: Place, onPick: (PlaceKind) -> Unit, onCancel: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(place.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.saved_home), MotoColors.Blue, { onPick(PlaceKind.HOME) }, Modifier.weight(1f), horizontalPadding = 4.dp)
                PillButton(stringResource(R.string.saved_work), MotoColors.Blue, { onPick(PlaceKind.WORK) }, Modifier.weight(1f), horizontalPadding = 4.dp)
                PillButton(stringResource(R.string.saved_favorite), MotoColors.Orange, { onPick(PlaceKind.FAVORITE) }, Modifier.weight(1.3f), horizontalPadding = 4.dp)
            }
            PillButton(stringResource(R.string.cancel), MotoColors.PanelHigh, onCancel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RouteCard(
    ui: NavUi,
    options: RouteOptions,
    onOptions: (RouteOptions) -> Unit,
    canOpenExternal: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val route = ui.route ?: return
    val context = LocalContext.current
    val externalApps = remember { context.installedNavApps() }
    var menuOpen by remember { mutableStateOf(false) }
    val now by rememberNowMs()
    val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + route.durationS.toLong() * 1000))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.destination?.name.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (externalApps.isNotEmpty() && canOpenExternal) {
                    Box(
                        Modifier.size(width = 76.dp, height = 68.dp).clickable {
                            val dest = ui.destination ?: return@clickable
                            if (externalApps.size == 1) context.startExternalNav(externalApps[0], dest.point.lat, dest.point.lon, options.profile)
                            else menuOpen = true
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open_external_nav), tint = MotoColors.Gray, modifier = Modifier.size(32.dp))
                            Text(stringResource(R.string.open_external_label), fontSize = 12.sp, color = MotoColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            externalApps.forEach { app ->
                                DropdownMenuItem(
                                    text = { Text(app.label, fontSize = 20.sp) },
                                    modifier = Modifier.heightIn(min = 64.dp),
                                    onClick = {
                                        menuOpen = false
                                        ui.destination?.let { context.startExternalNav(app, it.point.lat, it.point.lon, options.profile) }
                                    },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.size(width = 76.dp, height = 68.dp).clickable(onClick = onSave), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                            tint = if (saved) MotoColors.Orange else MotoColors.Gray, modifier = Modifier.size(32.dp),
                        )
                        Text(
                            stringResource(if (saved) R.string.save_place_saved else R.string.save_place_label),
                            fontSize = 12.sp, color = if (saved) MotoColors.Orange else MotoColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                if (ui.loading) stringResource(R.string.route_updating)
                else "${formatDistance(route.distanceM.toInt())} · ${formatDuration(LocalContext.current, route.durationS.toInt())} · ${stringResource(R.string.arrival_fmt, eta)}",
                fontSize = 16.sp, color = MotoColors.Gray,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (options.profile == VehicleProfile.BICYCLE) {
                    OptionChip(stringResource(R.string.opt_no_hills), options.avoidHills, { onOptions(options.copy(avoidHills = !options.avoidHills)) }, Modifier.weight(1f))
                    OptionChip(stringResource(R.string.opt_no_bad_surface), options.avoidBadSurfaces, { onOptions(options.copy(avoidBadSurfaces = !options.avoidBadSurfaces)) }, Modifier.weight(1f))
                } else {
                    OptionChip(stringResource(R.string.opt_no_highway), options.avoidHighways, { onOptions(options.copy(avoidHighways = !options.avoidHighways)) }, Modifier.weight(1f))
                    OptionChip(stringResource(R.string.opt_no_toll), options.avoidTolls, { onOptions(options.copy(avoidTolls = !options.avoidTolls)) }, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.start), MotoColors.Green, onStart, Modifier.weight(2f))
                PillButton(stringResource(R.string.cancel), MotoColors.PanelHigh, onCancel, Modifier.weight(1f), horizontalPadding = 8.dp)
            }
        }
    }
}

/** A toggle that reads like a button: filled blue with a check when on. At least 56dp tall. */
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (selected) R.string.state_on else R.string.state_off)
    Row(
        modifier = modifier.height(68.dp).clip(RoundedCornerShape(28.dp))
            .background(if (selected) MotoColors.Blue else MotoColors.PanelHigh)
            .border(1.dp, if (selected) Color.White else Color(0x26FFFFFF), RoundedCornerShape(28.dp))
            .semantics { role = Role.Switch; stateDescription = state }
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArrivedCard(next: Place?, onContinue: () -> Unit, onDone: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(if (next != null) R.string.stop_arrived else R.string.arrived),
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
            if (next != null) {
                Text(next.name, fontSize = 16.sp, color = MotoColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PillButton(stringResource(R.string.continue_trip), MotoColors.Green, onContinue, Modifier.fillMaxWidth())
                PillButton(stringResource(R.string.finish), MotoColors.PanelHigh, onDone, Modifier.fillMaxWidth())
            } else {
                PillButton(stringResource(R.string.ok), MotoColors.Blue, onDone, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Fuel stations around the rider, or along the road ahead while guiding. */
@Composable
private fun FuelPanel(
    fuel: FuelUi,
    profile: VehicleProfile,
    guiding: Boolean,
    onGo: (Place) -> Unit,
    onAddStop: (Place) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(when (profile) {
                            VehicleProfile.MOTORCYCLE -> R.string.fuel_title
                            VehicleProfile.CAR -> R.string.stops_title_car
                            VehicleProfile.BICYCLE -> R.string.stops_title_bike
                        }),
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Text(
                        stringResource(if (fuel.alongRoute) R.string.fuel_ahead else R.string.fuel_nearby),
                        fontSize = 14.sp, color = MotoColors.Gray,
                    )
                }
                Box(Modifier.size(68.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            when {
                fuel.loading -> Text(stringResource(R.string.fuel_searching), fontSize = 15.sp, color = MotoColors.Gray)
                fuel.error != null -> {
                    Text(fuel.error, fontSize = 15.sp, color = MotoColors.Orange)
                    PillButton(stringResource(R.string.retry), MotoColors.Blue, onRetry, Modifier.fillMaxWidth())
                }
                else -> LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(fuel.stations) { station -> FuelRow(station, guiding, onGo, onAddStop) }
                }
            }
        }
    }
}

@Composable
private fun FuelRow(station: FuelStation, guiding: Boolean, onGo: (Place) -> Unit, onAddStop: (Place) -> Unit) {
    val name = station.place.name.ifBlank {
        stringResource(when (station.kind) {
            StopKind.FUEL -> R.string.fuel_unnamed
            StopKind.CHARGE -> R.string.stop_unnamed_charge
            StopKind.BIKE_REPAIR -> R.string.stop_unnamed_repair
            StopKind.BIKE_SHOP -> R.string.stop_unnamed_shop
            StopKind.WATER -> R.string.stop_unnamed_water
        })
    }
    val place = station.place.copy(name = name)
    val detail = listOf(station.place.detail, formatDistance(station.distanceM)).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MotoColors.PanelHigh).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 13.sp, color = MotoColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton(
            stringResource(if (guiding) R.string.fuel_add_stop else R.string.fuel_go),
            MotoColors.Orange, { if (guiding) onAddStop(place) else onGo(place) },
            Modifier.padding(6.dp), horizontalPadding = 14.dp,
        )
    }
}

@Composable
private fun ManeuverBanner(ui: NavUi) {
    val route = ui.route ?: return
    val maneuver = route.maneuvers.getOrNull(ui.maneuverIndex) ?: return
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1B6FE0), Color(0xFF0B4FB3))))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(maneuverIcon(maneuver.type), null, tint = Color.White, modifier = Modifier.size(64.dp))
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(formatDistance(ui.distToNextM), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(maneuver.instruction, fontSize = 16.sp, color = Color(0xE6FFFFFF), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TripBar(ui: NavUi, onStop: () -> Unit) {
    val now by rememberNowMs()
    val eta = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + ui.remainingS * 1000L))
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(eta, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${formatDuration(LocalContext.current, ui.remainingS)} · ${formatDistance(ui.remainingM)}", fontSize = 15.sp, color = MotoColors.Gray)
            }
            PillButton(stringResource(R.string.finish), MotoColors.Red, onStop)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6101114)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(68.dp).clip(CircleShape).background(Color(0xE6101114))
            .border(1.dp, Color(0x26FFFFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun SpeedChip(shown: Int?, over: Boolean) {
    Column(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xE6101114)).border(1.dp, Color(0x26FFFFFF), CircleShape),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (shown != null) Units.speed(shown).toString() else "--", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (shown == null) MotoColors.Gray else if (over) MotoColors.Red else Color.White)
        Text(stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit), fontSize = 11.sp, color = MotoColors.Gray)
    }
}

fun formatDistance(meters: Int): String = Units.distance(meters)

fun formatDuration(context: Context, seconds: Int): String {
    val minutes = (seconds + 30) / 60
    val h = context.getString(R.string.unit_hour)
    val m = context.getString(R.string.unit_minute)
    return if (minutes >= 60) "${minutes / 60} $h ${"%02d".format(minutes % 60)} $m" else "${maxOf(minutes, 1)} $m"
}

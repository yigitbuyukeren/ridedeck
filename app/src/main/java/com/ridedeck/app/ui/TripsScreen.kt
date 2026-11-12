package com.ridedeck.app.ui

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.ridedeck.app.util.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.repeatOnLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.Trip
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripRecorder
import com.ridedeck.app.data.TripSummary
import com.ridedeck.app.data.TripSummaryMath
import com.ridedeck.app.data.RideTracking
import com.ridedeck.app.data.RidePhase
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Date

internal data class TripSummaryCalendarState(
    val now: Instant,
    val zone: ZoneId,
    val generation: Int,
    /** False means protected time-change broadcasts could not be registered; resume/midnight still work. */
    val broadcastActive: Boolean,
)

/**
 * Lifecycle-aware calendar refresh for the summary header. The clock, zone and signal flow are
 * injectable for tests; production uses the wall clock, system zone and the three protected system
 * broadcasts below. The receiver is registered only while RESUMED and is unregistered on pause or
 * disposal. It reads no intent extras and performs no external writes.
 */
@Composable
internal fun rememberTripSummaryCalendar(
    locale: java.util.Locale,
    clock: () -> Instant = { Instant.now() },
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    externalSignals: Flow<Unit>? = null,
    waitUntil: suspend (Long) -> Unit = { delay(it) },
): TripSummaryCalendarState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentClock by rememberUpdatedState(clock)
    val currentZoneProvider by rememberUpdatedState(zone)
    val currentWaitUntil by rememberUpdatedState(waitUntil)
    val currentZone = currentZoneProvider()
    var generation by remember { mutableIntStateOf(0) }
    var broadcastActive by remember { mutableStateOf(false) }

    LifecycleResumeEffect(context, lifecycle) {
        generation++
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                // Only protected system actions are registered; no extras are trusted or used.
                generation++
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.isSuccess
        broadcastActive = registered
        onPauseOrDispose {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            broadcastActive = false
        }
    }

    LaunchedEffect(externalSignals) {
        externalSignals?.collect { generation++ }
    }

    LaunchedEffect(lifecycle, locale, currentZone, generation) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = currentClock()
                val waitMs = runCatching {
                    Duration.between(now, TripSummaryMath.nextLocalMidnight(now, currentZone)).toMillis()
                }.getOrNull()
                if (waitMs == null) break // unrepresentable injected clock: do not spin or crash
                currentWaitUntil(waitMs.coerceAtLeast(1_000L))
                generation++
            }
        }
    }

    val now = remember(generation, currentZone) { currentClock() }
    return TripSummaryCalendarState(
        now = now,
        zone = currentZone,
        generation = generation,
        broadcastActive = broadcastActive,
    )
}

@Composable
fun TripsScreen(trips: TripRecorder, tracking: RideTracking, onClose: () -> Unit) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by trips.version.collectAsState()
    val recording by trips.recording.collectAsState()
    val storageError by trips.error.collectAsState()
    val ride by tracking.session.state.collectAsState()
    val recordingAllowed by tracking.recordTrips.collectAsState()
    val startRide = rememberRideStart(tracking)
    val list by produceState(emptyList<Trip>(), version) { value = trips.trips() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val locale = LocalConfiguration.current.locales[0]
    val calendar = rememberTripSummaryCalendar(locale)
    val summaries = remember(list, locale, calendar.zone, calendar.now, calendar.generation) {
        TripSummaryMath.of(list, calendar.now, calendar.zone, locale)
    }

    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(modifier = Modifier.fillMaxSize().symmetricSafePadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(R.string.trips_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = palette.text)
                    if (recording) Text(stringResource(R.string.trip_recording), fontSize = 16.sp, color = MotoColors.Red)
                    if (storageError) Text(stringResource(R.string.trip_storage_error), fontSize = 16.sp, color = MotoColors.Red)
                }
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.cardHigh).hapticClickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.message_dismiss), tint = palette.text, modifier = Modifier.size(32.dp)) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    stringResource(if (ride.running) R.string.ride_stop else R.string.ride_start),
                    if (ride.running) MotoColors.Red else MotoColors.Green,
                    { if (ride.running) tracking.session.stop() else startRide() },
                    Modifier.weight(1f),
                )
                Text(stringResource(when (ride.phase) {
                    RidePhase.STARTING -> R.string.ride_starting
                    RidePhase.STOPPING -> R.string.ride_stopping
                    RidePhase.ACTIVE -> R.string.ride_active
                    RidePhase.IDLE -> R.string.ride_idle
                }), color = palette.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            }
            ride.failure?.let { Text(stringResource(it.textId()), color = MotoColors.Red, fontSize = 15.sp) }
            if (ride.running && !recordingAllowed) Text(stringResource(R.string.ride_recording_off), color = palette.muted, fontSize = 14.sp)
            if (ride.running && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Text(stringResource(R.string.ride_notifications_off), color = palette.muted, fontSize = 14.sp)
            }
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TripSummaryHeader(
                        week = summaries.week,
                        month = summaries.month,
                        unassignedDateCount = summaries.unassignedDateCount,
                    )
                    val threshold = "${Units.speed(TripRecorder.START_KMH)} ${stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)}"
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.trips_empty_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.text)
                        Text(
                            stringResource(R.string.trips_empty, threshold),
                            fontSize = 18.sp,
                            color = palette.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                SplitPane(
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                    first = { m -> LazyColumn(
                        modifier = m,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "trip-summary") {
                            TripSummaryHeader(
                                week = summaries.week,
                                month = summaries.month,
                                modifier = Modifier.padding(bottom = 2.dp),
                                unassignedDateCount = summaries.unassignedDateCount,
                            )
                        }
                        items(list, key = { it.id }) { trip ->
                            val isSelected = trip.id == selected?.id
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) MotoColors.Blue else MotoColors.Card)
                                    .hapticClickable(haptic = RideHaptic.Select) { selectedId = trip.id }
                                    .padding(16.dp),
                            ) {
                                val fg = if (isSelected) Color.White else palette.text
                                Text(dateFormat.format(Date(trip.startedAt)), fontSize = 18.sp, color = fg, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatDistance(trip.stats.distanceM.toInt())} · ${formatDuration(context, (trip.durationMs / 1000).toInt())}",
                                    fontSize = 15.sp,
                                    color = if (isSelected) Color(0xE6FFFFFF) else palette.muted,
                                )
                            }
                        }
                    } },
                    second = { m ->
                        if (selected != null) {
                            TripDetail(
                                trip = selected,
                                modifier = m,
                                onDelete = {
                                    scope.launch {
                                        trips.delete(selected.id)
                                        selectedId = null
                                    }
                                },
                                onLoadPoints = { trips.points(selected.id) },
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The trip list and the chosen trip: side by side on a wide screen, one above the other on an upright phone. */
@Composable
private fun SplitPane(modifier: Modifier, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (isNarrowScreen()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.fillMaxWidth().weight(0.45f))
            second(Modifier.fillMaxWidth().weight(0.55f))
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            first(Modifier.width(380.dp).fillMaxHeight())
            second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Weekly/monthly totals in the list pane; scrolls with history and keeps a 64 dp minimum block. */
@Composable
internal fun TripSummaryHeader(
    week: TripSummary,
    month: TripSummary,
    modifier: Modifier = Modifier,
    unassignedDateCount: Int = 0,
) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val baseStyle = LocalTextStyle.current
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp)).background(palette.card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SummaryLine(stringResource(R.string.trip_summary_week_label), week, context, baseStyle, palette)
        SummaryLine(stringResource(R.string.trip_summary_month_label), month, context, baseStyle, palette)
        if (unassignedDateCount > 0) {
            Text(
                stringResource(R.string.trip_summary_unassigned_fmt, unassignedDateCount),
                style = baseStyle.statText(12f, FontWeight.Normal, palette.muted),
            )
        }
    }
}

@Composable
private fun SummaryLine(
    label: String,
    summary: TripSummary,
    context: android.content.Context,
    baseStyle: TextStyle,
    palette: MotoPalette,
) {
    val unit = stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)
    Text(label, style = baseStyle.statText(14f, FontWeight.SemiBold, palette.text))
    when {
        summary.tripCount <= 0 -> Text(
            stringResource(R.string.trip_summary_none),
            style = baseStyle.statText(13f, FontWeight.Normal, palette.muted),
        )
        summary.capped -> Text(
            stringResource(R.string.trip_summary_capped),
            style = baseStyle.statText(13f, FontWeight.Normal, palette.muted),
        )
        else -> Text(
            stringResource(
                R.string.trip_summary_line_fmt,
                summary.tripCount,
                formatDistance(summary.distanceM.toInt()),
                summaryDuration(context, summary.totalMs),
                summaryDuration(context, summary.movingMs),
                "${Units.speed(summary.averageMovingKmh)} $unit",
            ),
            style = baseStyle.statText(13f, FontWeight.Normal, palette.muted),
        )
    }
    if (summary.omittedCount > 0) {
        Text(
            stringResource(R.string.trip_summary_omitted_fmt, summary.omittedCount),
            style = baseStyle.statText(12f, FontWeight.Normal, palette.muted),
        )
    }
}

private fun summaryDuration(context: android.content.Context, milliseconds: Long): String {
    // formatDuration takes Int seconds; cap corrupt/overflowed totals instead of wrapping negative.
    val seconds = (milliseconds / 1000L).coerceIn(0L, 2_000_000_000L).toInt()
    return formatDuration(context, seconds)
}

@Composable
internal fun TripDetail(
    trip: Trip,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onLoadPoints: (suspend () -> List<TripPoint>)? = null,
) {
    val context = LocalContext.current
    val palette = LocalMotoPalette.current
    val baseStyle = LocalTextStyle.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val tripDate = remember(trip.startedAt) { dateFormat.format(Date(trip.startedAt)) }
    var confirmingDelete by remember(trip.id) { mutableStateOf(false) }
    var showMap by remember(trip.id) { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (showMap && onLoadPoints != null) {
            TripMapPanel(
                tripId = trip.id,
                loadPoints = onLoadPoints,
                isDay = palette.isDay,
                onClose = { showMap = false },
                modifier = Modifier.weight(1f),
            )
        } else {
            // The stats get their intrinsic height inside the scroll area, so large fonts can grow the
            // cards instead of being clipped by a fixed pane height.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tripDate, modifier = Modifier.testTag("trip-detail-date"), style = baseStyle.statText(18f, FontWeight.SemiBold, palette.text))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Stat(R.string.stat_distance, formatDistance(trip.stats.distanceM.toInt()), Modifier.weight(1f))
                    Stat(R.string.stat_total_duration, formatDuration(context, (trip.durationMs / 1000).toInt()), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    // Moving time comes from the GPS fix timestamps; this is not a claim about real stopped time.
                    Stat(R.string.stat_moving, formatDuration(context, (trip.stats.movingMs / 1000).toInt()), Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SpeedStat(R.string.stat_avg, Units.speed(trip.stats.avgKmh), Modifier.weight(1f))
                    SpeedStat(R.string.stat_max, Units.speed(trip.stats.maxKmh), Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.trip_moving_disclaimer),
                    style = baseStyle.statText(13f, FontWeight.Normal, palette.muted),
                )
                if (onLoadPoints != null) {
                    PillButton(
                        stringResource(R.string.trip_map_open),
                        MotoColors.Blue,
                        { showMap = true },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // Fixed at the bottom: never hidden behind the scrollable stats, with the usual 72 dp target.
            PillButton(stringResource(R.string.trip_delete), MotoColors.Red, { confirmingDelete = true }, Modifier.fillMaxWidth())
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.trip_delete_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.trip_delete_confirm_body, tripDate))
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 64.dp),
                    onClick = {
                        // Re-check the live state so a double tap cannot delete twice.
                        if (confirmingDelete) {
                            confirmingDelete = false
                            onDelete()
                        }
                    },
                ) {
                    Text(stringResource(R.string.trip_delete_confirm_action), color = MotoColors.Red)
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 64.dp),
                    onClick = { confirmingDelete = false },
                ) {
                    Text(stringResource(R.string.trip_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun Stat(label: Int, value: String, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val baseStyle = LocalTextStyle.current
    Column(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(label), style = baseStyle.statText(15f, FontWeight.Normal, palette.muted))
        Text(value, style = baseStyle.statText(34f, FontWeight.Bold, palette.text))
    }
}

/** Speed card: the number and the unit are sized separately so a long value or unit never clips. */
@Composable
internal fun SpeedStat(label: Int, value: Int, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val baseStyle = LocalTextStyle.current
    Column(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(label), style = baseStyle.statText(15f, FontWeight.Normal, palette.muted))
        SpeedValue(value, stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit))
    }
}

/**
 * One-line value + unit pair. Both are measured at their nominal sizes, then shrunk together when the
 * card is too narrow (small screen, large font, three-digit value or a long unit). The number stays
 * bigger than the unit; neither is ever ellipsised.
 */
@Composable
internal fun SpeedValue(value: Int, unit: String, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val valueText = value.toString()
    val baseStyle = LocalTextStyle.current
    val valueStyle = baseStyle.statText(VALUE_SP, FontWeight.Bold, palette.text)
    val unitStyle = baseStyle.statText(UNIT_SP, FontWeight.Medium, palette.muted)
    val gap = 4.dp
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gapPx = with(density) { gap.toPx() }
        // Memoized on everything that can change the real text width. The measurer itself is keyed on
        // density, layout direction and the font resolver, so a nonlinear font converter is included.
        val sizes = remember(
            valueText, unit, valueStyle, unitStyle, textMeasurer, density,
            constraints.maxWidth, gapPx,
        ) {
            StatFit.fitMeasured(
                availableWidth = constraints.maxWidth.toFloat(),
                gap = gapPx,
                baseValueSp = VALUE_SP,
                baseUnitSp = UNIT_SP,
                measure = { valueSp, unitSp ->
                    (textMeasurer.measure(valueText, valueStyle.scaledTo(valueSp)).size.width +
                        textMeasurer.measure(unit, unitStyle.scaledTo(unitSp)).size.width).toFloat()
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Text(
                valueText,
                style = valueStyle.scaledTo(sizes.valueSp),
                maxLines = 1,
                modifier = Modifier.alignByBaseline().width(IntrinsicSize.Max),
            )
            Text(
                unit,
                style = unitStyle.scaledTo(sizes.unitSp),
                maxLines = 1,
                modifier = Modifier.alignByBaseline().width(IntrinsicSize.Max),
            )
        }
    }
}

private const val VALUE_SP = 34f
private const val UNIT_SP = 17f
private const val STAT_LINE_HEIGHT = 1.2f

/** Explicit line height: inheriting a short Material line height clips large stat text vertically. */
private fun TextStyle.statText(fontSize: Float, weight: FontWeight, color: Color): TextStyle =
    copy(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * STAT_LINE_HEIGHT).sp,
        fontWeight = weight,
        color = color,
    )

/** Scales every size-dependent dimension of the already merged style, so measured and drawn widths agree. */
private fun TextStyle.scaledTo(fontSize: Float): TextStyle {
    val factor = fontSize / this.fontSize.value
    return copy(
        fontSize = fontSize.sp,
        lineHeight = (fontSize * STAT_LINE_HEIGHT).sp,
        letterSpacing = if (letterSpacing.isSp) (letterSpacing.value * factor).sp else letterSpacing,
    )
}

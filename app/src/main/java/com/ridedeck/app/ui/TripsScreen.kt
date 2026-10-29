package com.ridedeck.app.ui

import com.ridedeck.app.util.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.Trip
import com.ridedeck.app.data.TripRecorder
import com.ridedeck.app.data.RideTracking
import com.ridedeck.app.data.RidePhase
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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

    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
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
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.cardHigh).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, null, tint = palette.text, modifier = Modifier.size(32.dp)) }
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.trips_empty), fontSize = 18.sp, color = palette.muted)
                }
            } else {
                SplitPane(
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                    first = { m -> LazyColumn(
                        modifier = m,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list, key = { it.id }) { trip ->
                            val isSelected = trip.id == selected?.id
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) MotoColors.Blue else MotoColors.Card)
                                    .clickable { selectedId = trip.id }
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

@Composable
private fun TripDetail(trip: Trip, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Stat(R.string.stat_distance, formatDistance(trip.stats.distanceM.toInt()), Modifier.weight(1f))
            Stat(R.string.stat_duration, formatDuration(context, (trip.durationMs / 1000).toInt()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Stat(R.string.stat_avg, "${Units.speed(trip.stats.avgKmh)} ${stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)}", Modifier.weight(1f))
            Stat(R.string.stat_max, "${Units.speed(trip.stats.maxKmh)} ${stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit)}", Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton(stringResource(R.string.trip_delete), MotoColors.Red, onDelete, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: Int, value: String, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(label), fontSize = 15.sp, color = palette.muted)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = palette.text)
    }
}

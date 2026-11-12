package com.ridedeck.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.MediaState
import com.ridedeck.app.data.OfflineMaps
import com.ridedeck.app.data.ReadinessDetail
import com.ridedeck.app.data.ReadinessItem
import com.ridedeck.app.data.ReadinessKind
import com.ridedeck.app.data.ReadinessStatus
import com.ridedeck.app.data.RideReadiness
import com.ridedeck.app.data.RideReadinessAssessment
import com.ridedeck.app.data.RideReadinessInput
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.TtsFacts
import com.ridedeck.app.data.hasFreshFix
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.delay

/** Settings destinations a readiness row can open; none of them sends SMS, calls or requests. */
internal enum class ReadinessAction {
    APP_DETAILS,
    LOCATION_SOURCE,
    NETWORK_SETTINGS,
    NOTIFICATION_SETTINGS,
    NOTIFICATION_ACCESS,
}

/** Pure visibility/action gate; the motion owner remains DashboardViewModel's `inputLocked`. */
internal data class ReadinessUiState(val open: Boolean = false) {
    fun requestOpen(inputLocked: Boolean): ReadinessUiState = copy(open = !inputLocked)

    fun afterMotion(inputLocked: Boolean): ReadinessUiState = if (inputLocked) copy(open = false) else this

    fun dismiss(): ReadinessUiState = copy(open = false)

    fun allowsSettingsAction(inputLocked: Boolean): Boolean = open && !inputLocked
}

internal fun readinessAction(item: ReadinessItem): ReadinessAction? = when {
    item.kind == ReadinessKind.PRECISE_LOCATION -> ReadinessAction.APP_DETAILS
    // A GPS row missing precise permission is a permission-recovery action, not a provider toggle.
    item.kind == ReadinessKind.GPS && item.detail == ReadinessDetail.GPS_PERMISSION_MISSING -> ReadinessAction.APP_DETAILS
    item.kind == ReadinessKind.GPS -> ReadinessAction.LOCATION_SOURCE
    item.kind == ReadinessKind.INTERNET -> ReadinessAction.NETWORK_SETTINGS
    item.kind == ReadinessKind.NOTIFICATIONS -> ReadinessAction.NOTIFICATION_SETTINGS
    item.kind == ReadinessKind.MEDIA -> ReadinessAction.NOTIFICATION_ACCESS
    else -> null
}

/** One optional launch-screen/settings entry; the panel itself never opens by itself. */
@Composable
internal fun ReadinessEntryRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.readiness_title), fontSize = 19.sp, lineHeight = 24.sp, color = palette.text, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.readiness_entry_desc), fontSize = 14.sp, lineHeight = 20.sp, color = palette.muted)
        }
        PillButton(
            stringResource(R.string.readiness_entry_action), MotoColors.Blue, onOpen,
            minHeight = 64.dp, fontSize = 16.sp, horizontalPadding = 16.dp,
        )
    }
}

/**
 * The one refreshable fact-read helper used by the panel. `refreshKey` is the Activity resume tick,
 * so returning from a system page re-reads permissions; a failed read is null/UNKNOWN.
 */
@Composable
internal fun <T> rememberReadinessFact(refreshKey: Any?, read: () -> T): T? =
    remember(refreshKey) { runCatching(read).getOrNull() }

/** Lifecycle-aware one-second clock for freshness; it never requests GPS or creates a location source. */
@Composable
internal fun rememberReadinessNowMs(
    clock: () -> Long = System::currentTimeMillis,
    intervalMs: Long = 1_000L,
): Long {
    require(intervalMs > 0) { "Readiness ticker interval must be positive" }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentClock by rememberUpdatedState(clock)
    var nowMs by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(lifecycle, intervalMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMs = currentClock()
                delay(intervalMs)
            }
        }
    }
    return nowMs
}

/**
 * Stationary informational panel. It gathers the existing runtime facts, then renders only the
 * approved pure [RideReadiness] rows. No aggregate score, no automatic opening, no safety promise.
 */
@Composable
internal fun ReadinessPanel(
    locationState: LocationState,
    mediaState: MediaState,
    ttsFacts: TtsFacts,
    sosContacts: List<SosContact>,
    refreshKey: Any? = null,
    onClose: () -> Unit,
    onAction: (ReadinessAction) -> Unit,
) {
    val context = LocalContext.current
    val internet = rememberConnectivityStatus(context)
    var offlineLoaded by remember { mutableStateOf(false) }
    val nowMs = rememberReadinessNowMs()
    val offlineAreas by OfflineMaps.areas.collectAsStateWithLifecycle()
    LaunchedEffect(context) {
        // A failed metadata read must leave the list unknown, not terminate this effect.
        offlineLoaded = runCatching {
            OfflineMaps.load(context)
            true
        }.getOrDefault(false)
    }

    val fine = rememberReadinessFact(refreshKey) { context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    val coarse = rememberReadinessFact(refreshKey) { context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) }
    val notifications = rememberReadinessFact(refreshKey) { notificationsEnabled(context) }
    val sosSms = rememberReadinessFact(refreshKey) { context.hasPermission(Manifest.permission.SEND_SMS) }

    val elapsed = SystemClock.elapsedRealtime()
    val fresh = locationState.hasFreshFix(nowMs, elapsed)
    val knownPosition = locationState.takeIf {
        fresh && it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0
    }?.let { GeoPoint(it.lat, it.lon) }

    val readiness = RideReadinessAssessment.assess(
        RideReadinessInput(
            fineLocationGranted = fine,
            coarseLocationGranted = coarse,
            gpsEnabled = locationState.gpsProviderEnabled,
            hasFreshFix = fresh,
            internet = internet,
            knownPosition = knownPosition,
            offlineAreas = offlineAreas.takeIf { offlineLoaded },
            // The native MapLibre region/tile store is not verified by TECH-002.
            offlineStorageVerified = null,
            tts = ttsFacts,
            notificationsGranted = notifications,
            mediaAccess = mediaState.hasAccess,
            sosContactsConfigured = sosContacts.isNotEmpty(),
            sosSmsGranted = sosSms,
        )
    )
    ReadinessContent(readiness, onClose, onAction)
}

/** Stateless content so fake readiness rows can be tested without permission, radio or network access. */
@Composable
internal fun ReadinessContent(
    readiness: RideReadiness,
    onClose: () -> Unit,
    onAction: (ReadinessAction) -> Unit,
) {
    val palette = LocalMotoPalette.current
    Box(Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(
            modifier = Modifier.fillMaxHeight().symmetricSafePadding().widthIn(max = 820.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = if (isNarrowScreen()) 20.dp else 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f).heightIn(max = 104.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.readiness_title),
                        fontSize = if (isNarrowScreen()) 24.sp else 30.sp,
                        lineHeight = if (isNarrowScreen()) 30.sp else 36.sp,
                        fontWeight = FontWeight.Bold, color = palette.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.readiness_sub),
                        fontSize = 15.sp, lineHeight = 20.sp, color = palette.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier.size(72.dp).testTag("readiness-close").clip(CircleShape).background(MotoColors.CardHigh)
                        .hapticClickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.message_dismiss), tint = palette.text, modifier = Modifier.size(32.dp)) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("readiness-list"),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(readiness.items, key = { it.kind.name }) { item ->
                    ReadinessRow(item, onAction)
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem, onAction: (ReadinessAction) -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(item.kind), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = palette.text, modifier = Modifier.weight(1f))
            Text(statusLabel(item.status), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = statusColor(item.status))
        }
        Text(detailText(item.detail), fontSize = 14.sp, lineHeight = 18.sp, color = palette.muted)
        val action = readinessAction(item)
        if (action != null) {
            PillButton(
                actionLabel(action), palette.cardHigh, { onAction(action) },
                Modifier.fillMaxWidth().testTag("readiness-action-${item.kind.name}"), textColor = palette.text, minHeight = 64.dp, fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun kindLabel(kind: ReadinessKind): String = stringResource(when (kind) {
    ReadinessKind.PRECISE_LOCATION -> R.string.readiness_kind_location
    ReadinessKind.GPS -> R.string.readiness_kind_gps
    ReadinessKind.INTERNET -> R.string.readiness_kind_internet
    ReadinessKind.OFFLINE_MAP -> R.string.readiness_kind_offline
    ReadinessKind.VOICE -> R.string.readiness_kind_voice
    ReadinessKind.NOTIFICATIONS -> R.string.readiness_kind_notifications
    ReadinessKind.MEDIA -> R.string.readiness_kind_media
    ReadinessKind.SOS -> R.string.readiness_kind_sos
})

@Composable
private fun statusLabel(status: ReadinessStatus): String = stringResource(when (status) {
    ReadinessStatus.READY -> R.string.readiness_status_ready
    ReadinessStatus.MISSING -> R.string.readiness_status_missing
    ReadinessStatus.OPTIONAL -> R.string.readiness_status_optional
    ReadinessStatus.UNKNOWN -> R.string.readiness_status_unknown
})

private fun statusColor(status: ReadinessStatus): androidx.compose.ui.graphics.Color = when (status) {
    ReadinessStatus.READY -> MotoColors.Green
    ReadinessStatus.MISSING -> MotoColors.Red
    ReadinessStatus.OPTIONAL -> MotoColors.Orange
    ReadinessStatus.UNKNOWN -> MotoColors.Gray
}

@Composable
private fun detailText(detail: ReadinessDetail): String = stringResource(when (detail) {
    ReadinessDetail.LOCATION_FINE_GRANTED -> R.string.readiness_detail_location_fine
    ReadinessDetail.LOCATION_COARSE_ONLY -> R.string.readiness_detail_location_coarse
    ReadinessDetail.LOCATION_DENIED -> R.string.readiness_detail_location_denied
    ReadinessDetail.LOCATION_PRECISE_MISSING -> R.string.readiness_detail_location_precise_missing
    ReadinessDetail.LOCATION_UNKNOWN -> R.string.readiness_detail_location_unknown
    ReadinessDetail.GPS_ENABLED -> R.string.readiness_detail_gps_enabled
    ReadinessDetail.GPS_DISABLED -> R.string.readiness_detail_gps_disabled
    ReadinessDetail.GPS_WAITING_FIX -> R.string.readiness_detail_gps_waiting_fix
    ReadinessDetail.GPS_PERMISSION_MISSING -> R.string.readiness_detail_gps_permission_missing
    ReadinessDetail.GPS_PERMISSION_UNKNOWN -> R.string.readiness_detail_gps_permission_unknown
    ReadinessDetail.GPS_UNKNOWN -> R.string.readiness_detail_gps_unknown
    ReadinessDetail.INTERNET_VALIDATED -> R.string.readiness_detail_internet_validated
    ReadinessDetail.INTERNET_UNVALIDATED -> R.string.readiness_detail_internet_unvalidated
    ReadinessDetail.INTERNET_OFFLINE -> R.string.readiness_detail_internet_offline
    ReadinessDetail.INTERNET_UNKNOWN -> R.string.readiness_detail_internet_unknown
    ReadinessDetail.OFFLINE_COVERED -> R.string.readiness_detail_offline_covered
    ReadinessDetail.OFFLINE_NOT_COVERED -> R.string.readiness_detail_offline_not_covered
    ReadinessDetail.OFFLINE_NO_AREAS -> R.string.readiness_detail_offline_no_areas
    ReadinessDetail.OFFLINE_POSITION_UNKNOWN -> R.string.readiness_detail_offline_position_unknown
    ReadinessDetail.OFFLINE_AREAS_UNKNOWN -> R.string.readiness_detail_offline_areas_unknown
    ReadinessDetail.OFFLINE_STORAGE_UNVERIFIED -> R.string.readiness_detail_offline_storage_unverified
    ReadinessDetail.VOICE_READY -> R.string.readiness_detail_voice_ready
    ReadinessDetail.VOICE_NETWORK_REQUIRED -> R.string.readiness_detail_voice_network_required
    ReadinessDetail.VOICE_NETWORK_UNAVAILABLE -> R.string.readiness_detail_voice_network_unavailable
    ReadinessDetail.VOICE_LANGUAGE_MISSING -> R.string.readiness_detail_voice_language_missing
    ReadinessDetail.VOICE_ENGINE_NOT_READY -> R.string.readiness_detail_voice_engine_not_ready
    ReadinessDetail.VOICE_UNKNOWN -> R.string.readiness_detail_voice_unknown
    ReadinessDetail.NOTIFICATIONS_GRANTED -> R.string.readiness_detail_notifications_granted
    ReadinessDetail.NOTIFICATIONS_DENIED -> R.string.readiness_detail_notifications_denied
    ReadinessDetail.NOTIFICATIONS_UNKNOWN -> R.string.readiness_detail_notifications_unknown
    ReadinessDetail.MEDIA_AVAILABLE -> R.string.readiness_detail_media_available
    ReadinessDetail.MEDIA_UNAVAILABLE -> R.string.readiness_detail_media_unavailable
    ReadinessDetail.MEDIA_UNKNOWN -> R.string.readiness_detail_media_unknown
    ReadinessDetail.SOS_CONFIGURED_UNVERIFIED -> R.string.readiness_detail_sos_configured_unverified
    ReadinessDetail.SOS_NOT_CONFIGURED -> R.string.readiness_detail_sos_not_configured
    ReadinessDetail.SOS_PERMISSION_MISSING -> R.string.readiness_detail_sos_permission_missing
    ReadinessDetail.SOS_PERMISSION_UNKNOWN -> R.string.readiness_detail_sos_permission_unknown
    ReadinessDetail.SOS_UNKNOWN -> R.string.readiness_detail_sos_unknown
})

@Composable
private fun actionLabel(action: ReadinessAction): String = stringResource(when (action) {
    ReadinessAction.APP_DETAILS -> R.string.action_open_settings
    ReadinessAction.LOCATION_SOURCE -> R.string.readiness_action_location
    ReadinessAction.NETWORK_SETTINGS -> R.string.readiness_action_network
    ReadinessAction.NOTIFICATION_SETTINGS -> R.string.readiness_action_notifications
    ReadinessAction.NOTIFICATION_ACCESS -> R.string.readiness_action_media
})

private fun notificationsEnabled(context: Context): Boolean? = runCatching {
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.hasPermission(Manifest.permission.POST_NOTIFICATIONS))
}.getOrNull()

/** Opens only system/app settings pages; callers enforce the motion lock before invoking it. */
internal fun openReadinessAction(context: Context, action: ReadinessAction): Boolean = runCatching {
    val intent = when (action) {
        ReadinessAction.APP_DETAILS -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"),
        )
        ReadinessAction.LOCATION_SOURCE -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        ReadinessAction.NETWORK_SETTINGS -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        ReadinessAction.NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        ReadinessAction.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

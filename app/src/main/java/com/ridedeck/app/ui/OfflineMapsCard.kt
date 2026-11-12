package com.ridedeck.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.ConnectivityClassifier
import com.ridedeck.app.data.ConnectivityEvents
import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.ConnectivityTracker
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.NativeRegionState
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineBlock
import com.ridedeck.app.data.OfflineMaps
import com.ridedeck.app.data.OfflinePlan
import com.ridedeck.app.data.ReconciledRegion
import kotlinx.coroutines.flow.StateFlow

/** Injectable callbacks/state for the card; production defaults use the OfflineMaps singleton. */
data class OfflineMapsCardActions(
    val load: (Context) -> Unit = OfflineMaps::load,
    val download: (Context, GeoPoint, Int, String) -> Unit = OfflineMaps::download,
    val delete: (Context, OfflineArea, Boolean) -> Unit = { context, area, allowStale ->
        OfflineMaps.delete(context, area, allowStale)
    },
    val clearError: () -> Unit = OfflineMaps::clearError,
)

/** Settings > Setup: download the map around the rider, list and delete the areas already stored. */
@Composable
fun OfflineMapsCard(
    here: GeoPoint?,
    areasFlow: StateFlow<List<OfflineArea>> = OfflineMaps.areas,
    progressFlow: StateFlow<OfflineMaps.Progress?> = OfflineMaps.progress,
    regionsFlow: StateFlow<List<ReconciledRegion>> = OfflineMaps.regions,
    reconcileErrorFlow: StateFlow<String?> = OfflineMaps.reconcileError,
    actionErrorFlow: StateFlow<String?> = OfflineMaps.actionError,
    actions: OfflineMapsCardActions = OfflineMapsCardActions(),
) {
    val context = LocalContext.current
    val loadAction = rememberUpdatedState(actions.load)
    LaunchedEffect(context) { loadAction.value(context) }
    val areas by areasFlow.collectAsStateWithLifecycle()
    val progress by progressFlow.collectAsStateWithLifecycle()
    val regions by regionsFlow.collectAsStateWithLifecycle()
    val reconcileError by reconcileErrorFlow.collectAsStateWithLifecycle()
    val actionError by actionErrorFlow.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<OfflineArea?>(null) }
    LaunchedEffect(areas) {
        if (confirming != null && areas.none { it == confirming }) confirming = null
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.offline_title), fontSize = 20.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.offline_desc), fontSize = 15.sp, color = LocalMotoPalette.current.muted)
        val fitting = here?.let { OfflinePlan.fittingRadii(it).lastOrNull() }
        if (here != null && fitting != null) {
            val budget = OfflinePlan.budget(here, fitting)
            Text(
                stringResource(R.string.offline_estimate, fitting, budget.tiles, OfflinePlan.estimateMb(budget.tiles)),
                fontSize = 15.sp, color = LocalMotoPalette.current.muted,
            )
        }
        Text("⚠ ${stringResource(R.string.offline_paused)}", fontSize = 15.sp, color = LocalMotoPalette.current.text)
        val p = progress
        if (p != null) {
            val block = p.block
            if (block != null) {
                Text(offlineBlockText(block), fontSize = 15.sp, color = MotoColors.Red)
                PillButton(stringResource(R.string.cancel), MotoColors.PanelHigh, actions.clearError, Modifier.fillMaxWidth())
            } else if (p.error != null) {
                Text(p.error.orEmpty(), fontSize = 15.sp, color = MotoColors.Red)
                PillButton(stringResource(R.string.cancel), MotoColors.PanelHigh, actions.clearError, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.offline_progress, (p.fraction * 100).toInt()), fontSize = 14.sp, color = LocalMotoPalette.current.muted)
            }
        } else if (here == null) {
            Text(stringResource(R.string.offline_no_gps), fontSize = 15.sp, color = LocalMotoPalette.current.muted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfflinePlan.RADII_KM.forEach { km ->
                    PillButton(
                        stringResource(R.string.offline_download, km), MotoColors.Blue,
                        { actions.download(context, here, km, "${"%.2f".format(java.util.Locale.US, here.lat)},${"%.2f".format(java.util.Locale.US, here.lon)}~$km") },
                        Modifier.weight(1f), horizontalPadding = 8.dp,
                    )
                }
            }
        }
        reconcileError?.let {
            Text(
                stringResource(R.string.offline_native_error, it), fontSize = 15.sp, lineHeight = 22.sp,
                color = MotoColors.Red,
            )
        }
        actionError?.let {
            Text(
                stringResource(R.string.offline_action_error, it), fontSize = 15.sp, lineHeight = 22.sp,
                color = MotoColors.Red,
            )
        }
        areas.forEach { a ->
            val reconciled = regions.firstOrNull { it.area == a }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_area_fmt, "${"%.2f".format(a.center.lat)}, ${"%.2f".format(a.center.lon)}", a.radiusKm),
                        fontSize = 15.sp, color = LocalMotoPalette.current.text, modifier = Modifier.weight(1f),
                    )
                    if (confirming != a) {
                        PillButton(
                            stringResource(R.string.offline_delete), MotoColors.PanelHigh,
                            { confirming = a }, Modifier.testTag("offline-delete"),
                            horizontalPadding = 12.dp, minHeight = 64.dp, fontSize = 16.sp,
                        )
                    }
                }
                Text(
                    offlineStateText(reconciled?.state), fontSize = 14.sp, lineHeight = 20.sp,
                    color = if (reconciled?.state == NativeRegionState.ERROR) MotoColors.Red else LocalMotoPalette.current.muted,
                )
                if (confirming == a) {
                    Text(
                        stringResource(R.string.offline_delete_confirm_text), fontSize = 15.sp, lineHeight = 22.sp,
                        color = LocalMotoPalette.current.text,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(
                            stringResource(R.string.offline_delete_cancel), MotoColors.PanelHigh,
                            { confirming = null }, Modifier.weight(1f).testTag("offline-delete-cancel"),
                            horizontalPadding = 8.dp, minHeight = 64.dp, fontSize = 16.sp,
                        )
                        PillButton(
                            stringResource(R.string.offline_delete_confirm), MotoColors.Red,
                            {
                                // Guard the callback itself: a second click before recomposition
                                // must not issue a second delete.
                                if (confirming == a && areas.any { it == a }) {
                                    confirming = null
                                    actions.delete(context, a, true)
                                }
                            },
                            Modifier.weight(1f).testTag("offline-delete-confirm"),
                            horizontalPadding = 8.dp, minHeight = 64.dp, fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun offlineStateText(state: NativeRegionState?): String = when (state) {
    NativeRegionState.NATIVE_COMPLETE -> stringResource(R.string.offline_state_native_complete)
    NativeRegionState.DOWNLOADING -> stringResource(R.string.offline_state_downloading)
    NativeRegionState.PAUSED -> stringResource(R.string.offline_state_paused)
    NativeRegionState.PARTIAL -> stringResource(R.string.offline_state_partial)
    NativeRegionState.MISSING -> stringResource(R.string.offline_state_missing)
    NativeRegionState.ERROR -> stringResource(R.string.offline_state_error)
    NativeRegionState.UNKNOWN, null -> stringResource(R.string.offline_state_unknown)
}

@Composable
private fun offlineBlockText(block: OfflineBlock): String = when (block) {
    is OfflineBlock.TileLimit -> stringResource(R.string.offline_limit_tiles, block.tiles, block.limit)
    is OfflineBlock.ByteLimit -> stringResource(R.string.offline_limit_bytes, megabytes(block.bytes), megabytes(block.limit))
    OfflineBlock.ProviderUnverified -> stringResource(R.string.offline_provider_blocked, OfflinePlan.MAX_CONCURRENT_REQUESTS)
}

private fun megabytes(bytes: Long): Int = ((bytes + 999_999L) / 1_000_000L).toInt()

/** The default network's validated state, shared by the map chip and future readiness checks. */
@Composable
fun rememberConnectivityStatus(context: Context): ConnectivityStatus {
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    var status by remember { mutableStateOf(activeStatus(manager)) }
    DisposableEffect(manager) {
        val cm = manager ?: return@DisposableEffect onDispose {}
        val tracker = ConnectivityTracker(status)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Only the active default is authoritative; an old callback must not overwrite it.
                status = tracker.update(activeStatus(cm))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                status = tracker.update(
                    ConnectivityEvents.fromCapabilities(
                        eventIsActiveNetwork = runCatching { cm.activeNetwork }.getOrNull() == network,
                        eventValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        activeStatus = activeStatus(cm),
                    )
                )
            }

            override fun onLost(network: Network) {
                // A handover can lose the old default before the replacement is reported. Re-read
                // whatever is active now instead of treating the loss itself as "offline".
                status = tracker.update(activeStatus(cm))
            }
        }
        var registered = false
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: RuntimeException) {
            status = tracker.update(ConnectivityStatus.UNKNOWN)
        }
        onDispose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }
    return status
}

/** Whether the phone's default network has validated internet access right now. */
@Composable
fun rememberOnline(context: Context): Boolean = rememberConnectivityStatus(context).isOnline

private fun activeStatus(manager: ConnectivityManager?): ConnectivityStatus {
    val cm = manager ?: return ConnectivityStatus.UNKNOWN
    val network = try {
        cm.activeNetwork
    } catch (_: RuntimeException) {
        return ConnectivityStatus.UNKNOWN
    } ?: return ConnectivityStatus.OFFLINE
    val capabilities = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        ?: return ConnectivityStatus.UNKNOWN
    return ConnectivityClassifier.classify(
        hasDefaultNetwork = true,
        capabilitiesKnown = true,
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}

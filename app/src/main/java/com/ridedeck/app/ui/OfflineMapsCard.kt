package com.ridedeck.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.OfflineMaps
import com.ridedeck.app.data.OfflinePlan

/** Settings > Setup: download the map around the rider, list and delete the areas already stored. */
@Composable
fun OfflineMapsCard(here: GeoPoint?) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineMaps.load(context) }
    val areas by OfflineMaps.areas.collectAsStateWithLifecycle()
    val progress by OfflineMaps.progress.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.offline_title), fontSize = 20.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
        val mb = here?.let { OfflinePlan.estimateMb(OfflinePlan.tileCount(it, OfflinePlan.RADII_KM[1])) } ?: 0
        Text(stringResource(R.string.offline_desc, mb), fontSize = 15.sp, color = LocalMotoPalette.current.muted)
        val p = progress
        if (p != null) {
            if (p.error != null) {
                Text(p.error, fontSize = 15.sp, color = MotoColors.Red)
                PillButton(stringResource(R.string.cancel), MotoColors.PanelHigh, OfflineMaps::clearError, Modifier.fillMaxWidth())
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
                        { OfflineMaps.download(context, here, km, "${"%.2f".format(java.util.Locale.US, here.lat)},${"%.2f".format(java.util.Locale.US, here.lon)}~$km") },
                        Modifier.weight(1f), horizontalPadding = 8.dp,
                    )
                }
            }
        }
        areas.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.offline_area_fmt, "${"%.2f".format(a.center.lat)}, ${"%.2f".format(a.center.lon)}", a.radiusKm),
                    fontSize = 15.sp, color = LocalMotoPalette.current.text, modifier = Modifier.weight(1f),
                )
                PillButton(stringResource(R.string.offline_delete), MotoColors.PanelHigh, { OfflineMaps.delete(context, a) }, horizontalPadding = 12.dp)
            }
        }
    }
}

/** Whether the phone has a validated internet connection right now. */
@Composable
fun rememberOnline(context: Context): Boolean {
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember { mutableStateOf(manager?.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) {
                online = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) } != null
            }
        }
        manager?.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

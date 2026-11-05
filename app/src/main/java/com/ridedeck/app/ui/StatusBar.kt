package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.BatteryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** The current time, refreshed every [intervalMs]: for times that must keep moving while nothing else changes (an ETA while stuck in traffic). */
@Composable
fun rememberNowMs(intervalMs: Long = 15_000L): androidx.compose.runtime.State<Long> =
    produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }

/** CarPlay-style clock + battery readout at the bottom of the dock. */
@Composable
fun DockStatus(battery: BatteryState) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    val tempColor = when {
        battery.tempC >= 45f -> MotoColors.Red
        battery.tempC >= 40f -> MotoColors.Orange
        else -> LocalMotoPalette.current.text.copy(alpha = 0.85f)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time, fontSize = cappedSp(26f), fontWeight = FontWeight.Bold, color = LocalMotoPalette.current.text, maxLines = 1, softWrap = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (battery.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                contentDescription = null,
                tint = if (battery.charging) MotoColors.Green else LocalMotoPalette.current.text.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
            Text(stringResource(R.string.battery_percent_fmt, battery.percent), fontSize = cappedSp(17f), color = LocalMotoPalette.current.text.copy(alpha = 0.85f), fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
        Text("${battery.tempC.toInt()}°C", fontSize = cappedSp(17f), color = tempColor, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

package com.ridedeck.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridedeck.app.R
import com.ridedeck.app.data.BatteryState
import java.text.SimpleDateFormat
import java.util.Date
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
    val locale = LocalLocale.current.platformLocale
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val time = SimpleDateFormat("HH:mm", locale).format(Date(now))
    val palette = LocalDeckPalette.current
    val tempColor = when {
        battery.tempC >= 45f -> dangerColor(palette)
        battery.tempC >= 40f -> warningColor(palette)
        else -> palette.text.copy(alpha = 0.85f)
    }
    val level = battery.percent.coerceIn(0, 100)
    val severityColor = batterySeverityColor(level, battery.hasReading, palette)
    val percentColor = severityColor ?: palette.text.copy(alpha = 0.85f)
    val critical = battery.hasReading && level < BATTERY_ALERT_PERCENT
    val description = stringResource(
        when {
            critical && battery.charging -> R.string.battery_status_low_charging_fmt
            critical -> R.string.battery_status_low_fmt
            battery.charging -> R.string.battery_status_charging_fmt
            else -> R.string.battery_status_fmt
        },
        level,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("dock-status")) {
        Text(time, fontSize = cappedSp(26f), fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1, softWrap = false)
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatteryIndicator(level = level, charging = battery.charging, hasReading = battery.hasReading)
            if (critical) {
                Text(
                    "!",
                    fontSize = cappedSp(13f),
                    fontWeight = FontWeight.Bold,
                    color = severityColor ?: dangerColor(palette),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 1.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.battery_percent_fmt, level), fontSize = cappedSp(17f), color = percentColor, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
        Text("${battery.tempC.toInt()}°C", fontSize = cappedSp(17f), color = tempColor, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

private const val BATTERY_CRITICAL_PERCENT = 15
private const val BATTERY_LOW_PERCENT = 30
private const val BATTERY_ALERT_PERCENT = 5

private fun batterySeverityColor(level: Int, hasReading: Boolean, palette: DeckPalette): Color? = when {
    !hasReading -> null
    level <= BATTERY_CRITICAL_PERCENT -> dangerColor(palette)
    level <= BATTERY_LOW_PERCENT -> warningColor(palette)
    else -> null
}

/**
 * A battery outline whose interior fills in proportion to [level]. The frame is translucent gray, and the fill is
 * near-white at night but follows the theme text color by day so it stays visible on the light wallpaper. A small
 * bolt is drawn over the fill while charging, without hiding the level.
 */
@Composable
private fun BatteryIndicator(level: Int, charging: Boolean, hasReading: Boolean) {
    val palette = LocalDeckPalette.current
    val fillColor = batterySeverityColor(level, hasReading, palette) ?: palette.text.copy(alpha = 0.92f)
    val frameColor = palette.muted.copy(alpha = 0.55f)
    val boltColor = successColor(palette)
    Box(modifier = Modifier.size(width = 28.dp, height = 15.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 1.4.dp.toPx()
            val capGap = 1.dp.toPx()
            val capWidth = 2.4.dp.toPx()
            val bodyWidth = size.width - capGap - capWidth
            drawRoundRect(
                color = frameColor,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(bodyWidth - stroke, size.height - stroke),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(stroke),
            )
            drawRoundRect(
                color = frameColor,
                topLeft = Offset(bodyWidth + capGap, size.height * 0.3f),
                size = Size(capWidth, size.height * 0.4f),
                cornerRadius = CornerRadius(capWidth / 2f),
            )
            val inset = stroke + 1.dp.toPx()
            val innerWidth = bodyWidth - inset * 2f
            val innerHeight = size.height - inset * 2f
            if (hasReading && level > 0 && innerWidth > 0f && innerHeight > 0f) {
                val fillWidth = innerWidth * (level / 100f)
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(inset, inset),
                    size = Size(fillWidth, innerHeight),
                    cornerRadius = CornerRadius(1.dp.toPx().coerceAtMost(fillWidth / 2f)),
                )
            }
        }
        if (charging) {
            // Nudge left so the bolt sits over the body, not the terminal cap.
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = null,
                tint = boltColor,
                modifier = Modifier.align(Alignment.Center).offset(x = (-1.5).dp).size(9.dp),
            )
        }
    }
}

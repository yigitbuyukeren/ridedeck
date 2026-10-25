package com.ridedeck.app.ui

import com.ridedeck.app.util.Units
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.ridedeck.app.data.GaugeScale
import com.ridedeck.app.data.SpeedSmoother
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import java.util.Locale
import com.ridedeck.app.data.SpeedLimitMath
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.GpsUi
import com.ridedeck.app.R

@Composable
fun SpeedCard(
    gps: GpsUi,
    limit: Int?,
    /** Without location permission the card says so instead of waiting for a fix that cannot come. */
    locationGranted: Boolean = true,
    onGrantLocation: () -> Unit = {},
    modifier: Modifier = Modifier,
    onSos: (() -> Unit)? = null,
    /** Full-scale of the gauge, km/h; depends on the vehicle. */
    maxKmh: Float = 200f,
    /** The smoothed speed to draw this frame, km/h; the raw fix is only used for the limit colour. */
    speedProvider: () -> Double? = { gps.speedKmh.toDouble() },
    /** Hot or low battery: the dial updates at about 15 frames a second instead of every frame. */
    lowPower: Boolean = false,
    onClick: () -> Unit = {},
) {
    // The dial and the number follow the filtered speed once per frame (no spring on top of the filter's own smoothing).
    val smoother = remember { SpeedSmoother() }
    var fraction by remember { mutableFloatStateOf(0f) }
    var digit by remember { mutableIntStateOf(0) }
    // No usable speed (no fix, or the fixes stopped): show "--" and a message, never a made-up 0.
    var unknown by remember { mutableStateOf(true) }
    var hadSpeed by remember { mutableStateOf(false) }
    val currentGps by rememberUpdatedState(gps)
    val currentProvider by rememberUpdatedState(speedProvider)
    LaunchedEffect(maxKmh, lowPower) {
        val minFrameNs = if (lowPower) 66_000_000L else 0L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && now - last < minFrameNs) return@withFrameNanos
                val dt = if (last == 0L) 0.0 else (now - last) / 1_000_000_000.0
                last = now
                val shown = if (currentGps.hasFix) currentProvider() else null
                smoother.step(shown ?: 0.0, dt)
                fraction = (smoother.value / maxKmh).toFloat().coerceIn(0f, 1f)
                digit = smoother.digit()
                if (shown != null) hadSpeed = true
                unknown = shown == null
            }
        }
    }
    val palette = LocalMotoPalette.current
    val trackColor = palette.track
    val miles = Units.miles
    val labels = remember(maxKmh, miles) { GaugeScale.labels(maxKmh, miles) }
    val minorTicks = remember(maxKmh, miles) { GaugeScale.minorTicks(maxKmh, miles) }
    val textMeasurer = rememberTextMeasurer()
    val labelSize = cappedSp(12f)
    val tickColor = palette.muted
    val labelLayouts = remember(labels, labelSize, tickColor) {
        labels.map { textMeasurer.measure(it.text, TextStyle(fontSize = labelSize, color = tickColor, fontWeight = FontWeight.Medium)) }
    }
    var showDebug by remember { mutableStateOf(false) }
    Box(modifier = modifier.card().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier.fillMaxHeight().aspectRatio(1f).padding(20.dp)
        ) {
            val stroke = 26.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            val progressColor = when {
                fraction < 0.4f -> lerp(MotoColors.Cyan, MotoColors.Blue, fraction / 0.4f)
                fraction < 0.65f -> lerp(MotoColors.Blue, MotoColors.Orange, (fraction - 0.4f) / 0.25f)
                else -> lerp(MotoColors.Orange, MotoColors.Red, ((fraction - 0.65f) / 0.25f).coerceAtMost(1f))
            }
            // Ticks and numbers sit just inside the track; they never change, so they cost nothing per frame beyond the draw.
            val centre = Offset(size.width / 2, size.height / 2)
            val trackInner = size.width / 2 - stroke
            fun at(f: Float, radius: Float): Offset {
                val a = Math.toRadians((150.0 + 240.0 * f))
                return Offset(centre.x + radius * Math.cos(a).toFloat(), centre.y + radius * Math.sin(a).toFloat())
            }
            minorTicks.forEach { f -> drawLine(tickColor.copy(alpha = 0.6f), at(f, trackInner - 3.dp.toPx()), at(f, trackInner - 8.dp.toPx()), 1.5.dp.toPx()) }
            labels.forEachIndexed { i, l ->
                drawLine(tickColor, at(l.fraction, trackInner - 3.dp.toPx()), at(l.fraction, trackInner - 12.dp.toPx()), 2.dp.toPx())
                val layout = labelLayouts[i]
                val p = at(l.fraction, trackInner - 12.dp.toPx() - layout.size.height / 2f - 4.dp.toPx())
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
            rotate(150f) {
                drawArc(trackColor, 0f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0.004f) {
                    drawArc(progressColor, 0f, 240f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (unknown) "--" else Units.speed(digit).toString(),
                fontSize = cappedSp(92f),
                fontWeight = FontWeight.Bold,
                color = if (unknown) LocalMotoPalette.current.muted else if (SpeedLimitMath.isOver(gps.speedKmh, limit)) MotoColors.Red else LocalMotoPalette.current.text,
            )
            Text(stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit), fontSize = cappedSp(22f), color = LocalMotoPalette.current.muted, fontWeight = FontWeight.Medium)
            if (!gps.hasFix && !locationGranted) {
                Text(stringResource(R.string.speed_location_needed), fontSize = cappedSp(16f), color = MotoColors.Orange, modifier = Modifier.padding(top = 8.dp))
                PillButton(
                    stringResource(R.string.action_allow), MotoColors.Blue, onGrantLocation,
                    Modifier.padding(top = 8.dp), minHeight = 56.dp, fontSize = 17.sp, horizontalPadding = 20.dp, singleLine = true,
                )
            } else if (unknown) {
                Text(stringResource(if (hadSpeed) R.string.gps_lost else R.string.gps_waiting), fontSize = cappedSp(16f), color = MotoColors.Orange, modifier = Modifier.padding(top = 8.dp))
            } else {
                GpsInfo(gps, showDebug, { showDebug = !showDebug }, Modifier.padding(top = 10.dp))
            }
        }
        if (onSos != null) SosButton(onSos, Modifier.align(Alignment.BottomStart).padding(16.dp))
        if (limit != null) SpeedLimitSign(limit, Modifier.align(Alignment.TopEnd).padding(16.dp), size = 76.dp)
    }
}

/** How good the GPS fix is: the accuracy radius turned into a word and a colour. */
@Composable
private fun GpsInfo(gps: GpsUi, showDebug: Boolean, onToggleDebug: () -> Unit, modifier: Modifier = Modifier) {
    val muted = LocalMotoPalette.current.muted
    val (label, color) = when {
        gps.accuracyM == null -> R.string.gps_fair to MotoColors.Orange
        gps.accuracyM <= GPS_GOOD_M -> R.string.gps_good to MotoColors.Green
        gps.accuracyM <= GPS_FAIR_M -> R.string.gps_fair to MotoColors.Orange
        else -> R.string.gps_poor to MotoColors.Red
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        // A long press on the dot shows the hidden GPS timing line (fix age, interval, the chip's speed error).
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onToggleDebug() }) },
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(stringResource(label), fontSize = cappedSp(17f), fontWeight = FontWeight.Medium, color = muted, maxLines = 1)
        }
    }
    if (showDebug) {
        Text(
            stringResource(
                R.string.gps_debug_fmt,
                gps.fixAgeMs?.toString() ?: "?",
                gps.fixIntervalMs?.let { String.format(Locale.US, "%.2f", it / 1000.0) } ?: "?",
                gps.speedAccuracyMps?.let { String.format(Locale.US, "%.1f", it) } ?: "?",
            ),
            fontSize = cappedSp(13f), color = muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
        )
    }
    }
}

private const val GPS_GOOD_M = 10
private const val GPS_FAIR_M = 30

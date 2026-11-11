package com.ridedeck.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.SunsetMath
import com.ridedeck.app.data.hasOldFix
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** A fix older than this no longer reflects where the rider is (a GPS gap or a lost permission). */
private const val STALE_FIX_MS = 5 * 60_000L

/** Warning orange that still passes contrast on the pale day gradient. */
private val DayStaleOrange = Color(0xFFB45309)

/**
 * Sunset time and how much daylight is left. The gradient follows the theme: deep dusk colours at
 * night, a pale sky by day, both with the palette's own text and border colours so contrast holds.
 */
@Composable
fun SunsetCard(location: LocationState, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val now by rememberNowMs(60_000L)
    val zone = remember { ZoneId.systemDefault() }
    val hasFix = location.fixAtMs > 0L && location.lat.isFinite() && location.lon.isFinite()
    val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    val todaySunset = remember(location.lat, location.lon, hasFix, day, zone) {
        if (hasFix) SunsetMath.sunsetForDate(day, location.lat, location.lon, zone) else null
    }
    val tomorrowSunset = remember(location.lat, location.lon, hasFix, day, zone) {
        if (hasFix) SunsetMath.sunsetForDate(day.plusDays(1), location.lat, location.lon, zone) else null
    }
    val next = SunsetMath.nextSunset(now, todaySunset, tomorrowSunset)
    // The time is still close enough from a slightly old fix, but the rider should know it is old.
    val stale = location.hasOldFix(now, STALE_FIX_MS)

    val gradient = if (palette.isDay) {
        Brush.verticalGradient(listOf(Color(0xFFFFE9C9), Color(0xFFFFC694), Color(0xFFE79A82)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF21163D), Color(0xFF43213E), Color(0xFF7A3624)))
    }
    val sunColor = if (palette.isDay) Color(0xFFFFF3D6) else Color(0xFFFFB86B)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .border(1.dp, palette.border, RoundedCornerShape(28.dp)),
    ) {
        // The sun sits low on the horizon at the right edge, behind the text.
        Canvas(Modifier.fillMaxSize()) {
            val sun = Offset(size.width * 0.82f, size.height * 0.68f)
            drawCircle(sunColor.copy(alpha = 0.18f), 44.dp.toPx(), sun)
            drawCircle(sunColor.copy(alpha = 0.85f), 26.dp.toPx(), sun)
            val horizon = size.height * 0.90f
            drawLine(sunColor.copy(alpha = 0.45f), Offset(0f, horizon), Offset(size.width, horizon), 2.dp.toPx())
            drawLine(sunColor.copy(alpha = 0.22f), Offset(0f, horizon + 8.dp.toPx()), Offset(size.width, horizon + 8.dp.toPx()), 2.dp.toPx())
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (next.atMs != null && next.tomorrow) R.string.sunset_tomorrow else R.string.sunset_title),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stale) {
                    Text(
                        stringResource(R.string.sunset_stale),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (palette.isDay) DayStaleOrange else MotoColors.Orange,
                        maxLines = 1,
                    )
                }
            }
            Column {
                val sunsetMs = next.atMs
                if (sunsetMs == null) {
                    Text(
                        when {
                            !hasFix -> stringResource(R.string.sunset_waiting)
                            next.tomorrow -> stringResource(R.string.sunset_none_tomorrow)
                            else -> stringResource(R.string.sunset_none)
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                    )
                } else {
                    val clock = remember(sunsetMs) {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sunsetMs))
                    }
                    Text(clock, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = palette.text)
                    val leftMin = ((sunsetMs - now) / 60_000L).coerceAtLeast(0L)
                    val hours = leftMin / 60
                    val minutes = leftMin % 60
                    Text(
                        if (hours > 0) stringResource(R.string.sunset_in_hm, hours, minutes)
                        else stringResource(R.string.sunset_in_m, minutes),
                        fontSize = 14.sp,
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}

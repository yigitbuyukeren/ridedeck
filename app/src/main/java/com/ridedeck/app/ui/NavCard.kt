package com.ridedeck.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.ridedeck.app.R
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import com.ridedeck.app.data.WeatherMath
import com.ridedeck.app.data.WeatherSnapshot

@Composable
fun NavCard(
    nav: NavUi,
    places: List<SavedPlace>,
    weather: WeatherSnapshot?,
    me: LocationState,
    isDay: Boolean,
    lowPower: Boolean,
    onOpenMap: () -> Unit,
    onGoTo: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val maneuver = nav.route?.maneuvers?.getOrNull(nav.maneuverIndex)
    val guiding = nav.navigating && maneuver != null
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF15202B))
            .border(1.dp, Color(0x26FFFFFF), shape)
            .pressClickable(onClick = onOpenMap),
    ) {
        if (guiding) {
            // Live map while guiding: the same route the full map shows, at a glance.
            MiniMap(nav.route, nav.destination, me, isDay, lowPower)
            // The map view swallows touches; this clear layer on top passes a tap on to the card.
            Box(Modifier.fillMaxSize().pressClickable(onClick = onOpenMap))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color.Transparent, Color(0xB3000000)))
                )
            )
        } else {
            MapBackdrop()
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))
                )
            )
        }
        val narrowCard = isNarrowScreen()
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // On a narrow card the weather chip gives way.
            if (weather != null && !narrowCard) WeatherChip(weather)
        }
        if (guiding) MapCredit(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp))
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (guiding && maneuver != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = if (weather != null && !narrowCard) 130.dp else 0.dp)) {
                    Icon(maneuverIcon(maneuver.type), null, tint = Color.White, modifier = Modifier.size(52.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(formatDistance(nav.distToNextM), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(maneuver.instruction.trimEnd('.'), fontSize = 13.sp, lineHeight = 16.sp, color = Color(0xE6FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                val now by rememberNowMs()
                val arrival = remember(nav.remainingS, now) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + nav.remainingS * 1000L))
                }
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatDistance(nav.remainingM), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("·", fontSize = 20.sp, color = Color(0x99FFFFFF))
                    Text(arrival, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MotoColors.Green)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The whole card opens the map; the arrow says so.
                    Text(stringResource(R.string.nav_title) + "  ›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    // One tap from the dashboard to a route home or to work.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        places.filter { it.kind != PlaceKind.FAVORITE }.forEach { sp ->
                            PillButton(
                                stringResource(
                                    when (sp.kind) {
                                        PlaceKind.HOME -> R.string.go_home
                                        PlaceKind.WORK -> R.string.go_work
                                        else -> R.string.go_parked
                                    }
                                ),
                                Color(0x33FFFFFF), { onGoTo(sp.place) },
                                horizontalPadding = 16.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherChip(weather: WeatherSnapshot, modifier: Modifier = Modifier) {
    val tint = if (weather.iceRisk) MotoColors.Cyan else Color.White
    Row(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC0B1118)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(WeatherMath.emoji(weather.code), fontSize = 18.sp)
        Text("${weather.tempC}°", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.padding(start = 6.dp))
        if (weather.iceRisk) Text("❄", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Stylised street grid with a route and a position marker — evokes the CarPlay map tile. */
@Composable
private fun MapBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val minor = Color(0xFF1E2C3A)
        val major = Color(0xFF2B3E52)
        val thin = 6.dp.toPx()
        val thick = 12.dp.toPx()
        listOf(0.18f, 0.5f, 0.82f).forEach { drawLine(minor, Offset(0f, h * it), Offset(w, h * it), thin) }
        listOf(0.15f, 0.42f, 0.7f, 0.92f).forEach { drawLine(minor, Offset(w * it, 0f), Offset(w * it, h), thin) }
        drawLine(major, Offset(0f, h * 0.7f), Offset(w, h * 0.3f), thick, StrokeCap.Round)
        drawLine(major, Offset(w * 0.3f, h), Offset(w * 0.62f, 0f), thick, StrokeCap.Round)

        val route = Path().apply {
            moveTo(w * 0.5f, h * 0.62f)
            lineTo(w * 0.5f, h * 0.5f)
            lineTo(w * 0.7f, h * 0.5f)
            lineTo(w * 0.7f, h * 0.2f)
        }
        drawPath(route, MotoColors.Blue, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color.White, 13.dp.toPx(), Offset(w * 0.5f, h * 0.62f))
        drawCircle(MotoColors.Blue, 9.dp.toPx(), Offset(w * 0.5f, h * 0.62f))
        drawCircle(MotoColors.Red, 8.dp.toPx(), Offset(w * 0.7f, h * 0.2f))
    }
}

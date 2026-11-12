package com.ridedeck.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
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

private const val NAV_CARD_PRESS_SCALE = 0.98f

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
    // The card's static map tile follows the dashboard palette; the live mini map gets isDay below.
    val palette = LocalMotoPalette.current
    val maneuver = nav.route?.maneuvers?.getOrNull(nav.maneuverIndex)
    val guiding = nav.navigating && maneuver != null
    // Share the mini-map overlay press so the whole clipped card moves together.
    val press = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // The squeeze is outside the clip/background so the whole card scales as one piece;
            // applied inside, only the map and texts shrank and the map spilled past the rounded edge.
            .pressClickable(pressedScale = NAV_CARD_PRESS_SCALE, interactionSource = press, onClick = onOpenMap)
            .clip(shape)
            .background(if (palette.isDay) palette.card else Color(0xFF15202B))
            .border(1.dp, palette.border, shape),
    ) {
        if (guiding) {
            // Live map while guiding: the same route the full map shows, at a glance.
            MiniMap(nav.route, nav.progressIndex, nav.progressFraction, nav.destination, me, isDay, lowPower)
            // The map view swallows touches; this clear layer on top passes a tap on to the card.
            Box(
                Modifier.fillMaxSize()
                    // Pointer-only layer: clearing its semantics keeps the outer card as the single accessible action.
                    .clearAndSetSemantics { }
                    .pressClickable(pressedScale = NAV_CARD_PRESS_SCALE, interactionSource = press, onClick = onOpenMap)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color.Transparent, Color(0xB3000000)))
                )
            )
        } else {
            MapBackdrop(palette.isDay)
            Box(
                Modifier.fillMaxSize().background(
                    if (palette.isDay) {
                        // A light wash keeps the dark title readable without washing out the route pins.
                        Brush.verticalGradient(
                            listOf(palette.glassTop.copy(alpha = 0.60f), Color.Transparent, palette.glassBottom.copy(alpha = 0.40f))
                        )
                    } else {
                        Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))
                    }
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
        ) {
            if (guiding && maneuver != null) {
                val now by rememberNowMs()
                val arrival = remember(nav.remainingS, now) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + nav.remainingS * 1000L))
                }
                NavGuidedContent(
                    icon = maneuverIcon(maneuver.type),
                    distance = formatDistance(nav.distToNextM),
                    instruction = maneuver.instruction.trimEnd('.'),
                    isDay = palette.isDay,
                    remainingDistance = formatDistance(nav.remainingM),
                    arrival = arrival,
                    // Leave room for the weather chip that overlays the top-right corner.
                    maneuverModifier = Modifier.padding(end = if (weather != null && !narrowCard) 130.dp else 0.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The whole card opens the map; the arrow says so.
                    Text(stringResource(R.string.nav_title) + "  ›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.text)
                    // One tap from the dashboard to a route home or to work.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        places.filter { it.kind != PlaceKind.FAVORITE }.forEach { sp ->
                            PillButton(
                                stringResource(
                                    if (sp.kind == PlaceKind.HOME) R.string.go_home else R.string.go_work
                                ),
                                if (palette.isDay) palette.cardHigh else Color(0x33FFFFFF), { onGoTo(sp.place) },
                                horizontalPadding = 16.dp,
                                textColor = palette.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Remaining distance and arrival time on the guided card. Split out so the line can be measured
 * at the real font scale without pulling in the live map view.
 */
@Composable
internal fun NavProgressRow(distance: String, arrival: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(distance, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Text("·", fontSize = 20.sp, color = Color(0x99FFFFFF))
        Text(arrival, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MotoColors.Green, maxLines = 1)
    }
}

/**
 * Guided card body: the full manoeuvre text stays scrollable above the pinned remaining/arrival row,
 * so a chosen large font is never silently shrunk. Split out for measurement without the live map.
 */
@Composable
internal fun NavGuidedContent(
    icon: ImageVector,
    distance: String,
    instruction: String,
    isDay: Boolean,
    remainingDistance: String,
    arrival: String,
    modifier: Modifier = Modifier,
    maneuverModifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("nav-maneuver-scroll")) {
            ManeuverRow(
                icon = icon,
                distance = distance,
                instruction = instruction,
                isDay = isDay,
                modifier = maneuverModifier,
                // The scrollable guided body must expose the whole instruction, never an ellipsis.
                instructionMaxLines = Int.MAX_VALUE,
            )
        }
        NavProgressRow(remainingDistance, arrival)
    }
}

/**
 * The distance, arrow and instruction above the live map. The day map is light, so its white text
 * gets a dark rounded scrim; night keeps the existing unbacked row.
 */
@Composable
internal fun ManeuverRow(
    icon: ImageVector,
    distance: String,
    instruction: String,
    isDay: Boolean,
    modifier: Modifier = Modifier,
    /** Default keeps the shared first-wave glance row at two lines; scrollable containers pass [Int.MAX_VALUE]. */
    instructionMaxLines: Int = 2,
) {
    val backdrop = if (isDay) {
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 8.dp)
    } else {
        Modifier
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.then(backdrop)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(52.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(distance, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                instruction, fontSize = 13.sp, lineHeight = 16.sp, color = Color(0xE6FFFFFF),
                maxLines = instructionMaxLines,
                overflow = if (instructionMaxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WeatherChip(weather: WeatherSnapshot, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    // Cyan is reserved for the night chip; on the light day cardHigh it is too low-contrast.
    val tint = if (weather.iceRisk && !palette.isDay) MotoColors.Cyan else palette.text
    Row(
        modifier = modifier.clip(RoundedCornerShape(16.dp))
            .background(if (palette.isDay) palette.cardHigh else Color(0xCC0B1118))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(WeatherMath.emoji(weather.code), fontSize = 18.sp)
        Text("${weather.tempC}°", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tint, modifier = Modifier.padding(start = 6.dp))
        if (weather.iceRisk) Text("❄", fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Stylised street grid with a route and a position marker — evokes the CarPlay map tile. */
@Composable
private fun MapBackdrop(day: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // The static tile is not a MapLibre view; match the dashboard's day/night palette here.
        val minor = if (day) Color(0xFFDCE4EE) else Color(0xFF1E2C3A)
        val major = if (day) Color(0xFFC0CEDF) else Color(0xFF2B3E52)
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

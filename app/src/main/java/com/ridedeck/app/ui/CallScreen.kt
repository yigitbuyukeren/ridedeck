package com.ridedeck.app.ui

import com.ridedeck.app.util.Units
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import com.ridedeck.app.data.NavUi
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import android.telecom.CallAudioState
import com.ridedeck.app.R
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import kotlinx.coroutines.delay

/** Full-screen call UI: a status strip on top, who and what state on the left, big actions on the right. */
@Composable
fun CallScreen(
    call: CallState,
    callerName: String?,
    speedShown: Int?,
    nav: NavUi,
    onAnswer: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit = {},
    onRoute: (Int) -> Unit = {},
    onVolumeUp: () -> Unit = {},
    onVolumeDown: () -> Unit = {},
    onDigit: (Char) -> Boolean = { false },
) {
    val elapsed by produceState(0L, call.startedAtMs, call.phase) {
        while (call.phase == CallPhase.ACTIVE) {
            value = ((System.currentTimeMillis() - call.startedAtMs) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val unknown = stringResource(R.string.phone_unknown)
    val name = callerName?.takeIf { it.isNotBlank() } ?: unknown
    val initial = name.firstOrNull()?.takeIf { it.isLetter() }?.uppercase()
    val ringing = call.phase == CallPhase.RINGING
    var keys by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D2247), Color(0xFF05080F), Color.Black)))
            .clickable(enabled = false) {},
    ) {
        Column(modifier = Modifier.fillMaxSize().symmetricSafePadding().padding(horizontal = 28.dp, vertical = 10.dp)) {
            RideStrip(speedShown, nav, call)
            if (keys && call.phase == CallPhase.ACTIVE) {
                InCallKeysPane(
                    callerName = name,
                    typed = typed,
                    onDigit = { if (onDigit(it)) typed += it },
                    onHide = { keys = false },
                    onHangUp = onHangUp,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                )
            } else {
            TwoPane(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                first = { m -> Column(
                    modifier = m,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Avatar(initial, pulsing = call.phase != CallPhase.ACTIVE)
                    Text(
                        name,
                        fontSize = cappedSp(if (name.count { it.isDigit() } > name.length / 2) 30f else 38f, maxScale = 1.25f),
                        lineHeight = cappedSp(44f, maxScale = 1.25f),
                        fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    when (call.phase) {
                        CallPhase.ACTIVE -> Text(
                            "%02d:%02d".format(elapsed / 60, elapsed % 60),
                            fontSize = cappedSp(32f, maxScale = 1.2f), fontWeight = FontWeight.Medium, color = MotoColors.Green,
                        )
                        CallPhase.DIALING -> Text(stringResource(R.string.call_dialing) + "…", fontSize = cappedSp(22f, maxScale = 1.2f), color = MotoColors.Gray)
                        CallPhase.HELD -> Text(stringResource(R.string.call_held), fontSize = cappedSp(22f, maxScale = 1.2f), color = MotoColors.Gray)
                        else -> Text(stringResource(R.string.call_incoming), fontSize = cappedSp(22f, maxScale = 1.2f), color = MotoColors.Gray)
                    }
                    if (!ringing) VolumeRow(onVolumeDown, onVolumeUp)
                } },
                second = { m -> Column(
                    modifier = m,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    when {
                        ringing -> {
                            ActionPill(Icons.Rounded.Call, stringResource(R.string.call_answer), MotoColors.Green, onAnswer, RideHaptic.Confirm)
                            ActionPill(Icons.Rounded.CallEnd, stringResource(R.string.call_decline), MotoColors.Red, onHangUp, RideHaptic.Reject)
                        }
                        else -> {
                            if (call.managed && call.phase == CallPhase.ACTIVE) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ToggleAction(Icons.Rounded.MicOff, stringResource(R.string.call_mute), call.muted, onMute, Modifier.weight(1f))
                                    ToggleAction(Icons.Rounded.Dialpad, stringResource(R.string.call_keys), false, { keys = true }, Modifier.weight(1f))
                                }
                                RoutePicker(call.route, call.routes, onRoute)
                            }
                            if (call.routeError) Text(stringResource(R.string.call_route_failed), color = MotoColors.Red, fontSize = 16.sp)
                            ActionPill(Icons.Rounded.CallEnd, stringResource(R.string.call_hangup), MotoColors.Red, onHangUp, RideHaptic.Reject)
                        }
                    }
                } },
            )
            }
        }
    }
}

/** Who is calling and the actions: side by side on a wide screen, one above the other on an upright phone. */
@Composable
private fun TwoPane(
    modifier: Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (isNarrowScreen()) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first(Modifier.weight(0.8f).fillMaxWidth())
            second(Modifier.weight(1.2f).fillMaxWidth())
        }
    } else {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            first(Modifier.weight(1f).fillMaxHeight())
            second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Clock, speed and the next turn: what the dashboard shows, so a call does not blind the rider to the road. */
@Composable
private fun RideStrip(speedShown: Int?, nav: NavUi, call: CallState) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val maneuver = nav.route?.maneuvers?.getOrNull(nav.maneuverIndex)?.takeIf { nav.navigating }
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x26FFFFFF)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), fontSize = cappedSp(26f, maxScale = 1.15f), fontWeight = FontWeight.Bold, color = Color.White)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (speedShown != null) "${Units.speed(speedShown)}" else "--", fontSize = cappedSp(28f, maxScale = 1.15f), fontWeight = FontWeight.Bold, color = Color.White)
            Text(stringResource(if (Units.miles) R.string.speed_unit_mph else R.string.speed_unit), fontSize = cappedSp(15f, maxScale = 1.15f), color = MotoColors.Gray, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (maneuver != null) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(maneuverIcon(maneuver.type), null, tint = Color.White, modifier = Modifier.size(34.dp))
                // The call takes the voice; close to the turn the distance turns orange instead.
                Text(formatDistance(nav.distToNextM), fontSize = cappedSp(22f, maxScale = 1.15f), fontWeight = FontWeight.Bold, color = if (nav.distToNextM <= 60) MotoColors.Orange else Color.White)
                Text(maneuver.instruction, fontSize = cappedSp(15f, maxScale = 1.15f), color = Color(0xE6FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            // No route to show: use the room for where the call sound is going.
            Spacer(Modifier.weight(1f))
            if (call.managed) routeLabel(call.route)?.let { Text(stringResource(it), fontSize = cappedSp(17f, maxScale = 1.15f), color = MotoColors.Gray) }
        }
    }
}

@Composable
private fun routeLabel(route: Int): Int? = when (route) {
    CallAudioState.ROUTE_SPEAKER -> R.string.call_speaker
    CallAudioState.ROUTE_BLUETOOTH -> R.string.call_route_bluetooth
    CallAudioState.ROUTE_WIRED_HEADSET -> R.string.call_route_headset
    CallAudioState.ROUTE_EARPIECE -> R.string.call_route_phone
    else -> null
}

@Composable
private fun VolumeRow(onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VolumeButton(Icons.Rounded.Remove, stringResource(R.string.volume_down), onDown)
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = MotoColors.Gray, modifier = Modifier.size(26.dp))
        Text(stringResource(R.string.call_volume), fontSize = cappedSp(17f, maxScale = 1.2f), color = MotoColors.Gray)
        VolumeButton(Icons.Rounded.Add, stringResource(R.string.volume_up), onUp)
    }
}

@Composable
private fun VolumeButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0x33FFFFFF)).hapticClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(32.dp)) }
}

/** Full-content keypad view: the caller and typed digits stay visible, actions stay pinned below. */
@Composable
internal fun InCallKeysPane(
    callerName: String,
    typed: String,
    onDigit: (Char) -> Unit,
    onHide: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                callerName, fontSize = cappedSp(22f, maxScale = 1.2f), fontWeight = FontWeight.SemiBold,
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // What has been sent so far, so a long menu ("press 1, then 3") can be followed.
            Text(
                typed.takeLast(14), fontSize = cappedSp(26f, maxScale = 1.15f),
                fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                modifier = Modifier.height(40.dp).testTag("call-typed-digits"),
            )
        }
        InCallKeypad(onDigit, Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleAction(
                Icons.Rounded.Dialpad, stringResource(R.string.call_keys_hide), false, onHide,
                Modifier.weight(1f),
            )
            // Ending the call stays available while the digits are open.
            Row(
                modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp))
                    .background(MotoColors.Red).testTag("call-hangup-keys")
                    .pressClickable(haptic = RideHaptic.Reject, onClick = onHangUp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.call_hangup), fontSize = cappedSp(20f, maxScale = 1.2f),
                    fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                )
            }
        }
    }
}

/** Digits for voice menus ("press 1"); sent down the line as tones. */
@Composable
private fun InCallKeypad(onDigit: (Char) -> Unit, modifier: Modifier = Modifier) {
    val keyTone = rememberKeypadTone()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val key: @Composable (Char) -> Unit = { digit ->
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                    // The local beep is for the rider's ear; the digit still goes down the line as before.
                    .background(Color(0x26FFFFFF)).testTag("call-key-$digit")
                    .hapticClickable { keyTone(digit); onDigit(digit) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    digit.toString(), fontSize = cappedSp(30f, maxScale = 1.15f),
                    fontWeight = FontWeight.SemiBold, color = Color.White,
                )
            }
        }
        when {
            // Canonical 3x4 only when the real width and height keep every key >=64 dp.
            maxHeight >= KEYPAD_3X4_HEIGHT && maxWidth >= KEYPAD_3X4_WIDTH ->
                KeypadGrid(KEYPAD_3X4, Modifier.fillMaxSize(), key)
            // Full-content landscape: 6x2 only when width also keeps every key >= 64 dp.
            maxWidth >= KEYPAD_6X2_WIDTH && maxHeight >= KEYPAD_6X2_HEIGHT ->
                KeypadGrid(KEYPAD_6X2, Modifier.fillMaxSize(), key)
            // Very short/narrow screens: fewer columns, fixed 64 dp keys, scroll; actions stay pinned.
            else -> {
                val rows = keypadFallbackRows(maxWidth)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    KeypadGrid(rows, Modifier.fillMaxWidth().height(keypadRowsHeight(rows.size)), key)
                }
            }
        }
    }
}

@Composable
private fun Avatar(initial: String?, pulsing: Boolean) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.35f, label = "scale",
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
    )
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                Modifier.size(84.dp).scale(pulse).alpha((1.35f - pulse) * 1.6f).clip(CircleShape).background(MotoColors.Blue),
            )
        }
        Box(
            modifier = Modifier.size(84.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF5AA9FF), Color(0xFF1F5FD6)))),
            contentAlignment = Alignment.Center,
        ) {
            if (initial != null) Text(initial, fontSize = cappedSp(40f, maxScale = 1.2f), fontWeight = FontWeight.Bold, color = Color.White)
            else Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    haptic: RideHaptic = RideHaptic.Tap,
) {
    val foreground = callActionForeground(color)
    Row(
        modifier = Modifier
            .fillMaxWidth().height(80.dp).clip(RoundedCornerShape(40.dp)).background(color)
            .pressClickable(haptic = haptic, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = foreground, modifier = Modifier.size(34.dp))
        Text(label, fontSize = cappedSp(24f, maxScale = 1.2f), fontWeight = FontWeight.Bold, color = foreground, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.height(64.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) Color.White else Color(0x26FFFFFF))
            .hapticClickable(haptic = if (on) RideHaptic.ToggleOff else RideHaptic.ToggleOn, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(26.dp))
            Text(label, fontSize = cappedSp(16f, maxScale = 1.2f), fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else Color.White, maxLines = 1)
        }
    }
}

/** Phone, speaker and Bluetooth (or a wired headset) as one row, like the phone app: only routes on offer are shown. */
@Composable
private fun RoutePicker(route: Int, routes: Int, onRoute: (Int) -> Unit) {
    val options = listOf(
        Triple(CallAudioState.ROUTE_EARPIECE, Icons.Rounded.PhoneInTalk, R.string.call_route_phone),
        Triple(CallAudioState.ROUTE_SPEAKER, Icons.AutoMirrored.Rounded.VolumeUp, R.string.call_speaker),
        Triple(CallAudioState.ROUTE_BLUETOOTH, Icons.Rounded.Bluetooth, R.string.call_route_bluetooth),
        Triple(CallAudioState.ROUTE_WIRED_HEADSET, Icons.Rounded.Headset, R.string.call_route_headset),
    ).filter { routes and it.first != 0 }
    if (options.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (r, icon, label) ->
            ToggleAction(icon, stringResource(label), route == r, { onRoute(r) }, Modifier.weight(1f))
        }
    }
}

/** Green call actions need black content; red/other call actions keep white. */
internal fun callActionForeground(background: Color): Color =
    if (background == MotoColors.Green) Color.Black else Color.White

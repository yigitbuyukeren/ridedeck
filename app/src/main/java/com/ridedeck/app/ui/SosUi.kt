package com.ridedeck.app.ui

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.SosContact
import com.ridedeck.app.data.SosError
import com.ridedeck.app.data.SosMath
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.data.SosReason
import com.ridedeck.app.data.SosUi
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOLD_MS = 1500L

/** How the SOS button reacts to a hold: the scale and the halo follow the 0..1 progress of the hold. */
object SosHoldMath {
    /** 1.0 at rest, a small pop (1.06) the moment it is pressed, growing to 1.20 as the hold completes. */
    fun scale(progress: Float, pressed: Boolean): Float =
        if (!pressed) 1f else 1.06f + 0.14f * progress.coerceIn(0f, 1f)

    /** Strength (0..1) of the red halo around the button. */
    fun halo(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Which of the two mid-hold taps (a third and two thirds of the way) have been crossed. */
    fun ticks(progress: Float): Int = when {
        progress >= 2f / 3f -> 2
        progress >= 1f / 3f -> 1
        else -> 0
    }
}

/** Red 68dp button that needs a 1.5 second hold; it grows, glows and buzzes while held, with a ring that fills. */
@Composable
fun SosButton(onTrigger: () -> Unit, modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        SosHoldMath.scale(progress, pressed),
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sosScale",
    )
    Box(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val halo = SosHoldMath.halo(progress)
                if (halo > 0f) drawCircle(MotoColors.Red.copy(alpha = 0.35f * halo), radius = size.minDimension / 2f + 6.dp.toPx() * halo)
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    val hold = scope.launch {
                        val startedAt = System.currentTimeMillis()
                        var ticks = 0
                        while (true) {
                            progress = ((System.currentTimeMillis() - startedAt) / HOLD_MS.toFloat()).coerceAtMost(1f)
                            val crossed = SosHoldMath.ticks(progress)
                            if (crossed > ticks) {
                                ticks = crossed
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (progress >= 1f) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTrigger()
                                break
                            }
                            delay(16)
                        }
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    pressed = false
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(lerp(MotoColors.Red, Color(0xFF8E0B0B), SosHoldMath.halo(progress) * 0.6f)))
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            if (progress > 0f) {
                drawArc(Color.White, -90f, 360f * progress, false, Offset.Zero, Size(size.width, size.height), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text(stringResource(R.string.sos_button_label), fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
    }
}

/** Full-screen countdown / result. Always dark red so it cannot be missed in daylight. */
@Composable
fun SosScreen(ui: SosUi, contactNames: List<String>, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (ui.phase == SosPhase.IDLE) return
    // The red fills the whole screen, cutout strip included; the content keeps clear of the system bars.
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF7A0A0A)).clickable(enabled = false) {}.safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        when (ui.phase) {
            SosPhase.COUNTDOWN -> Countdown(ui, contactNames, onCancel)
            SosPhase.SENDING -> Text(stringResource(R.string.sos_sending), color = Color.White, fontSize = 24.sp, modifier = Modifier.padding(32.dp))
            SosPhase.SENT -> Result(
                title = stringResource(if (ui.reason == SosReason.TEST) R.string.sos_test_done else R.string.sos_sent),
                detail = if (ui.reason == SosReason.TEST) null else smsResultDetail(ui),
                onDismiss = onDismiss,
            )
            SosPhase.PARTIAL, SosPhase.UNKNOWN -> Result(
                title = stringResource(if (ui.phase == SosPhase.PARTIAL) R.string.sos_partial else R.string.sos_unknown),
                detail = smsResultDetail(ui),
                onDismiss = onDismiss,
            )
            SosPhase.FAILED -> Result(
                title = stringResource(R.string.sos_failed),
                detail = stringResource(
                    when (ui.error) {
                        SosError.NO_CONTACTS -> R.string.sos_err_no_contacts
                        SosError.NO_PERMISSION -> R.string.sos_err_no_permission
                        SosError.NO_SIM -> R.string.sos_err_no_sim
                        else -> R.string.sos_err_send_failed
                    }
                ),
                onDismiss = onDismiss,
            )
            SosPhase.IDLE -> {}
        }
    }
}

@Composable
private fun smsResultDetail(ui: SosUi): String = listOfNotNull(
    ui.sentTo.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.sos_sent_to_fmt, it.joinToString(", ")) },
    ui.failedTo.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.sos_failed_to_fmt, it.joinToString(", ")) },
    ui.unknownTo.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.sos_unknown_to_fmt, it.joinToString(", ")) },
    stringResource(R.string.sos_delivery_note),
).joinToString("\n")

@Composable
private fun Countdown(ui: SosUi, contactNames: List<String>, onCancel: () -> Unit) {
    val narrow = isNarrowScreen()
    val align = if (narrow) TextAlign.Center else TextAlign.Start
    val number: @Composable (Modifier) -> Unit = { m ->
        Text(
            ui.secondsLeft.toString(),
            fontSize = if (narrow) 120.sp else 140.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center,
            modifier = m,
        )
    }
    val details: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m, verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = if (narrow) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                stringResource(
                    when (ui.reason) {
                        SosReason.AUTO -> R.string.sos_count_auto
                        SosReason.MANUAL -> R.string.sos_count_manual
                        SosReason.TEST -> R.string.sos_count_test
                    }
                ),
                fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = align,
            )
            if (ui.reason != SosReason.TEST) {
                Text(
                    stringResource(if (ui.reason == SosReason.AUTO) R.string.sos_count_hint_auto else R.string.sos_count_hint_manual),
                    fontSize = 18.sp, color = Color(0xE6FFFFFF), textAlign = align,
                )
                if (contactNames.isNotEmpty()) {
                    Text(stringResource(R.string.sos_to_fmt, contactNames.joinToString(", ")), fontSize = 15.sp, color = Color(0xB3FFFFFF), textAlign = align)
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).clip(RoundedCornerShape(32.dp))
                    .background(Color.White).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (ui.reason == SosReason.AUTO) R.string.sos_fine else R.string.sos_cancel),
                    fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color(0xFF7A0A0A), maxLines = 1, softWrap = false,
                )
            }
        }
    }
    if (narrow) {
        // Upright: the big number above, the words and the cancel button underneath.
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            number(Modifier)
            details(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            number(Modifier.weight(0.8f))
            details(Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun Result(title: String, detail: String?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, fontSize = 20.sp, lineHeight = 26.sp, color = Color(0xE6FFFFFF), textAlign = TextAlign.Center)
        PillButton(stringResource(R.string.ok), Color.White, onDismiss, textColor = Color(0xFF7A0A0A), modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
fun SosSettingsCard(
    contacts: List<SosContact>,
    onAdd: (SosContact) -> Unit,
    onRemove: (SosContact) -> Unit,
    crashEnabled: Boolean,
    onCrashEnabled: (Boolean) -> Unit,
    buttonEnabled: Boolean,
    onButtonEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalMotoPalette.current
    var smsOk by remember { mutableStateOf(context.hasPermission(Manifest.permission.SEND_SMS)) }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsOk = it }
    // The permission can also be changed in the system settings while the app is in the background.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        smsOk = context.hasPermission(Manifest.permission.SEND_SMS)
        onPauseOrDispose {}
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        // The picker hands over read access to just the chosen row, so no contacts permission is needed.
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) onAdd(SosContact(c.getString(0).orEmpty(), c.getString(1).orEmpty()))
        }
    }
    val ready = contacts.isNotEmpty() && smsOk

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.sos_title), fontSize = 20.sp, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.sos_desc), fontSize = 15.sp, color = palette.muted)
        contacts.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.name, fontSize = 18.sp, color = palette.text, fontWeight = FontWeight.Medium)
                    Text(c.number, fontSize = 14.sp, color = palette.muted)
                }
                PillButton(stringResource(R.string.sos_remove), palette.cardHigh, { onRemove(c) }, textColor = palette.text)
            }
        }
        if (contacts.size < SosMath.MAX_CONTACTS) {
            PillButton(stringResource(R.string.sos_add_contact), MotoColors.Blue, {
                try {
                    pickLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.sos_picker_missing, Toast.LENGTH_SHORT).show()
                }
            })
        }
        if (!smsOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sos_perm_title), fontSize = 18.sp, color = palette.text, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.sos_perm_desc), fontSize = 14.sp, color = palette.muted)
                }
                PillButton(stringResource(R.string.action_allow), MotoColors.Blue, { smsLauncher.launch(Manifest.permission.SEND_SMS) })
            }
        }
        if (contacts.isEmpty()) Text(stringResource(R.string.sos_no_contacts), fontSize = 14.sp, color = MotoColors.Orange)
        SosToggle(stringResource(R.string.sos_crash_title), stringResource(R.string.sos_crash_desc), crashEnabled && ready, ready, onCrashEnabled)
        SosToggle(stringResource(R.string.sos_button_title), stringResource(R.string.sos_button_desc), buttonEnabled && ready, ready, onButtonEnabled)
        PillButton(stringResource(R.string.sos_test), palette.cardHigh, onTest, textColor = palette.text)
    }
}

@Composable
private fun SosToggle(title: String, desc: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalMotoPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, color = palette.text, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 14.sp, color = palette.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

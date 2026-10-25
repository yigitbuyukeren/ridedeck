package com.ridedeck.app.ui

import com.ridedeck.app.data.VoiceHistoryEntry
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.VoicePhase
import com.ridedeck.app.data.VoiceUi
import com.ridedeck.app.data.VoiceRuntimeState
import com.ridedeck.app.data.VoicePresentationState

/** A round microphone button; 64dp so it works with gloves. */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 64.dp) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MotoColors.Blue).pressClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Mic, stringResource(R.string.voice_mic), tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

/** The listening card, the call countdown, questions with tappable choices and short results, above everything but SOS. */
@Composable
fun VoiceOverlay(presentation: VoicePresentationState, onCancel: () -> Unit, onChoose: (Int) -> Unit, onHelp: () -> Unit, modifier: Modifier = Modifier) {
    val ui = presentation.assistant
    val runtimeState = presentation.runtime
    if (ui.phase == VoicePhase.IDLE) return
    Box(modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Color(0xF2182430)).padding(20.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(voiceStatusLabel(ui, runtimeState)), fontSize = 15.sp, color = MotoColors.Orange, fontWeight = FontWeight.SemiBold)
            when (ui.phase) {
                VoicePhase.LISTENING -> {
                    Icon(Icons.Rounded.Mic, null, tint = MotoColors.Red, modifier = Modifier.size(44.dp))
                    Text(
                        ui.text.ifBlank { stringResource(R.string.voice_listening) },
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                VoicePhase.PROCESSING -> {
                    CircularProgressIndicator(color = MotoColors.Blue, modifier = Modifier.size(40.dp))
                    ui.recognizedText.takeIf(String::isNotBlank)?.let {
                        Text(stringResource(R.string.voice_heard_fmt, it), fontSize = 18.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                VoicePhase.CONFIRM -> {
                    Text(ui.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                    ui.recognizedText.takeIf(String::isNotBlank)?.let {
                        Text(stringResource(R.string.voice_heard_fmt, it), fontSize = 16.sp, color = MotoColors.Orange, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${ui.secondsLeft}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MotoColors.Orange)
                }
                else -> {
                    ui.recognizedText.takeIf(String::isNotBlank)?.let {
                        Text(stringResource(R.string.voice_heard_fmt, it), fontSize = 16.sp, color = MotoColors.Orange, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(ui.text, fontSize = 20.sp, color = Color.White, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            // "Which one?": the options can be said or tapped.
            ui.choices.forEachIndexed { i, label ->
                PillButton(label, MotoColors.Blue, { onChoose(i) }, Modifier.fillMaxWidth(), singleLine = true)
            }
            if (ui.phase == VoicePhase.LISTENING || ui.phase == VoicePhase.CONFIRM || ui.phase == VoicePhase.PROCESSING || ui.phase == VoicePhase.MESSAGE) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PillButton(
                        stringResource(if (ui.phase == VoicePhase.MESSAGE) R.string.voice_stop_speaking else R.string.voice_cancel),
                        MotoColors.PanelHigh, onCancel, Modifier.weight(1f), horizontalPadding = 12.dp,
                    )
                    if (ui.phase == VoicePhase.LISTENING) {
                        PillButton(stringResource(R.string.voice_help_button), MotoColors.PanelHigh, onHelp, Modifier.weight(1.7f), horizontalPadding = 12.dp, singleLine = true)
                    }
                }
            }
        }
    }
}

private fun voiceStatusLabel(ui: VoiceUi, state: VoiceRuntimeState): Int = when {
    ui.isError || state == VoiceRuntimeState.ERROR -> R.string.voice_status_error
    ui.phase == VoicePhase.LISTENING -> R.string.voice_status_listening
    ui.phase == VoicePhase.PROCESSING || state == VoiceRuntimeState.PROCESSING -> R.string.voice_status_processing
    ui.phase == VoicePhase.CONFIRM || state == VoiceRuntimeState.CONFIRMING -> R.string.voice_status_confirmation
    state == VoiceRuntimeState.SPEAKING -> R.string.voice_status_speaking
    state == VoiceRuntimeState.FOLLOW_UP -> R.string.voice_status_listening
    else -> R.string.voice_status_result
}

/** "What can I say?": the commands by group, with examples. */
@Composable
fun VoiceHelpScreen(history: List<VoiceHistoryEntry>, onClear: () -> Unit, onClose: () -> Unit) {
    val palette = LocalMotoPalette.current
    val groups = listOf(
        R.string.voice_help_nav_title to R.string.voice_help_nav,
        R.string.voice_help_phone_title to R.string.voice_help_phone,
        R.string.voice_help_msg_title to R.string.voice_help_msg,
        R.string.voice_help_music_title to R.string.voice_help_music,
        R.string.voice_help_info_title to R.string.voice_help_info,
        R.string.voice_help_app_title to R.string.voice_help_app,
    )
    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.voice_help_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.cardHigh).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, null, tint = palette.text, modifier = Modifier.size(32.dp)) }
            }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (history.isNotEmpty()) item {
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.voice_history_header), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MotoColors.Blue, modifier = Modifier.weight(1f))
                            PillButton(stringResource(R.string.voice_history_clear), palette.cardHigh, onClear, textColor = palette.text, horizontalPadding = 18.dp, minHeight = 56.dp, fontSize = 16.sp, singleLine = true)
                        }
                        history.forEach { h ->
                            Text((if (h.understood) "✓ " else "✗ ") + h.heard, fontSize = 17.sp, color = if (h.understood) palette.text else MotoColors.Orange, maxLines = 2)
                        }
                    }
                }
                items(groups) { (title, body) ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MotoColors.Blue)
                        Text(stringResource(body), fontSize = 17.sp, color = palette.text, lineHeight = 24.sp)
                    }
                }
            }
        }
    }
}

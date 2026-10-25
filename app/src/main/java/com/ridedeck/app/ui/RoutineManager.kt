package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ridedeck.app.data.*
import com.ridedeck.app.R

@Composable
fun RoutineManager(
    routines: List<RideRoutine>,
    onSave: (RideRoutine, String?) -> Boolean,
    onDelete: (String) -> Unit,
    onRun: (String) -> Unit,
    canEdit: Boolean,
) {
    var editing by remember { mutableStateOf<RideRoutine?>(null) }
    var originalName by remember { mutableStateOf<String?>(null) }
    var deleteName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(canEdit) { if (!canEdit) { editing = null; deleteName = null } }
    val palette = LocalMotoPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.routine_title), color = palette.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            if (canEdit) PillButton(stringResource(R.string.routine_add), MotoColors.Blue, { originalName = null; editing = RideRoutine("", listOf(RoutineStep.MediaVolume(40))) })
        }
        Text(stringResource(R.string.routine_description), color = palette.muted, fontSize = 14.sp)
        if (!canEdit) Text(stringResource(R.string.routine_edit_parked_only), color = palette.muted, fontSize = 14.sp)
        if (routines.isEmpty()) Text(stringResource(R.string.routine_empty), color = palette.muted, fontSize = 14.sp)
        routines.forEach { routine ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.cardHigh).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(routine.name, color = palette.text, fontSize = 18.sp)
                StepSummary(routine.steps, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(R.string.routine_run), MotoColors.Blue, { onRun(routine.name) }, Modifier.weight(1f))
                    if (canEdit) {
                        PillButton(stringResource(R.string.routine_edit), palette.card, { originalName = routine.name; editing = routine }, Modifier.weight(1f), textColor = palette.text)
                        PillButton(stringResource(R.string.routine_delete), palette.card, { deleteName = routine.name }, Modifier.weight(1f), textColor = palette.text)
                    }
                }
            }
        }
    }
    editing?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial.name) }
        var home by remember(initial) { mutableStateOf(initial.steps.contains(RoutineStep.NavigateHome)) }
        var volumeEnabled by remember(initial) { mutableStateOf(initial.steps.filterIsInstance<RoutineStep.MediaVolume>().isNotEmpty()) }
        var volume by remember(initial) { mutableFloatStateOf(initial.steps.filterIsInstance<RoutineStep.MediaVolume>().firstOrNull()?.percent?.toFloat() ?: 40f) }
        var warnings by remember(initial) { mutableStateOf(initial.steps.filterIsInstance<RoutineStep.VoiceWarnings>().firstOrNull()?.enabled) }
        var theme by remember(initial) { mutableStateOf(initial.steps.filterIsInstance<RoutineStep.Theme>().firstOrNull()?.mode) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (originalName == null) R.string.routine_create else R.string.routine_edit_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    OutlinedTextField(name, { name = it.take(RideRoutines.MAX_NAME_LENGTH) }, label = { Text(stringResource(R.string.routine_name)) }, singleLine = true)
                    CheckLine(stringResource(R.string.routine_home), home) { home = it }
                    CheckLine(stringResource(R.string.routine_volume), volumeEnabled) { volumeEnabled = it }
                    if (volumeEnabled) Slider(volume / 100f, { volume = it * 100f }, valueRange = 0f..1f)
                    if (volumeEnabled) Text(stringResource(R.string.routine_volume_fmt, volume.toInt()))
                    CheckLine(stringResource(R.string.routine_voice_warnings), warnings != null) { warnings = if (it) (warnings ?: false) else null }
                    if (warnings != null) CheckLine(stringResource(R.string.routine_warnings_enabled), warnings == true) { warnings = it }
                    CheckLine(stringResource(R.string.routine_theme), theme != null) { theme = if (it) (theme ?: ThemeMode.AUTO) else null }
                    if (theme != null) ChoiceRow(stringResource(R.string.theme_title), stringResource(R.string.routine_theme_desc), listOf(stringResource(R.string.theme_auto) to (theme == ThemeMode.AUTO), stringResource(R.string.theme_day) to (theme == ThemeMode.DAY), stringResource(R.string.theme_night) to (theme == ThemeMode.NIGHT))) { theme = ThemeMode.entries[it] }
                    if (error) Text(stringResource(R.string.routine_invalid), color = androidx.compose.ui.graphics.Color.Red)
                }
            },
            confirmButton = { TextButton(onClick = {
                val steps = buildList<RoutineStep> {
                    if (home) add(RoutineStep.NavigateHome)
                    if (volumeEnabled) add(RoutineStep.MediaVolume(volume.toInt()))
                    warnings?.let { add(RoutineStep.VoiceWarnings(it)) }
                    theme?.let { add(RoutineStep.Theme(it)) }
                }
                if (onSave(RideRoutine(name, steps), originalName)) editing = null else error = true
            }) { Text(stringResource(R.string.save_place_label)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleteName?.let { name -> AlertDialog(
        onDismissRequest = { deleteName = null }, title = { Text(stringResource(R.string.routine_delete_title)) }, text = { Text(name) },
        confirmButton = { TextButton(onClick = { onDelete(name); deleteName = null }) { Text(stringResource(R.string.routine_delete)) } },
        dismissButton = { TextButton(onClick = { deleteName = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
}

@Composable
private fun CheckLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChange); Text(label) }
}

@Composable
private fun StepSummary(steps: List<RoutineStep>, modifier: Modifier = Modifier) {
    val labels = mutableListOf<String>()
    for (step in steps) {
        labels += when (step) {
            RoutineStep.NavigateHome -> stringResource(R.string.routine_step_home)
            is RoutineStep.MediaVolume -> stringResource(R.string.routine_step_volume_fmt, step.percent)
            is RoutineStep.VoiceWarnings -> stringResource(if (step.enabled) R.string.routine_step_warnings_on else R.string.routine_step_warnings_off)
            is RoutineStep.Theme -> stringResource(R.string.routine_step_theme_fmt, stringResource(when (step.mode) { ThemeMode.AUTO -> R.string.theme_auto; ThemeMode.DAY -> R.string.theme_day; ThemeMode.NIGHT -> R.string.theme_night }))
        }
    }
    Text(labels.joinToString(" · "), modifier = modifier, color = LocalMotoPalette.current.muted, fontSize = 14.sp)
}

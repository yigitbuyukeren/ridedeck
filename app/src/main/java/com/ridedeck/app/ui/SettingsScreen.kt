package com.ridedeck.app.ui

import com.ridedeck.app.data.DashCard
import com.ridedeck.app.data.DashLayout
import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.ridedeck.app.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import com.ridedeck.app.data.BikeType
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.RideRoutine
import com.ridedeck.app.data.VoiceResponseMode
import com.ridedeck.app.util.hasPermission

data class SettingItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val action: String,
    /** Without it the app does not work at all; only such rows send the settings page to the Setup tab. */
    val required: Boolean = false,
    val progress: Float? = null,
    val progressLabel: String? = null,
    val actionEnabled: Boolean = true,
    val onClick: () -> Unit,
)

fun locationGranted(c: Context) = c.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
fun phoneGranted(c: Context) =
    c.hasPermission(Manifest.permission.READ_PHONE_STATE) && c.hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
fun isDefaultAssistant(c: Context): Boolean =
    android.os.Build.VERSION.SDK_INT >= 29 &&
        c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true

fun isDefaultDialer(c: Context) =
    c.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage == c.packageName

/** The system's own "make this your phone app" dialog; on Android 10+ it comes from the role manager. */
fun defaultDialerIntent(c: Context): android.content.Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        c.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
    } else {
        android.content.Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, c.packageName)
    }

/** True when RideDeck is the phone's home (launcher) app. */
fun isHomeApp(c: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true
    } else {
        c.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == c.packageName
    }

/** True when the system leaves RideDeck out of battery optimisation, so it is not put to sleep on the road. */
fun ignoresBatteryOptimization(c: Context): Boolean =
    c.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

fun notificationAccessGranted(c: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
fun overlayGranted(c: Context) = Settings.canDrawOverlays(c)

fun runtimePermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
}

/** Asked for from the phone screen itself, not at first launch. */
fun phonebookPermissions() = arrayOf(
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
)

fun phonebookGranted(c: Context) = phonebookPermissions().all { c.hasPermission(it) }

data class ToggleItem(
    val title: String,
    val description: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val onChange: (Boolean) -> Unit,
)

private enum class SettingsTab(val label: Int) {
    SETUP(R.string.settings_tab_setup),
    RIDE(R.string.settings_tab_ride),
    SAFETY(R.string.settings_tab_safety),
}

/**
 * Settings in three tabs down the left side, so each page is a short list even on a 360dp-high phone:
 * permissions, ride options, and safety (SOS).
 */
@Composable
fun SettingsScreen(
    items: List<SettingItem>,
    toggles: List<ToggleItem>,
    onOpenTrips: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onClose: () -> Unit,
    vehicle: VehicleProfile = VehicleProfile.MOTORCYCLE,
    onVehicle: (VehicleProfile) -> Unit = {},
    dashSlots: List<DashCard> = DashLayout.DEFAULT,
    onDashSlot: (Int, DashCard) -> Unit = { _, _ -> },
    bikeType: BikeType = BikeType.CITY,
    onBikeType: (BikeType) -> Unit = {},
    safetyCard: @Composable () -> Unit = {},
    routines: List<RideRoutine> = emptyList(),
    onSaveRoutine: (RideRoutine, String?) -> Boolean = { _, _ -> false },
    onDeleteRoutine: (String) -> Unit = {},
    onRunRoutine: (String) -> Unit = {},
    responseMode: VoiceResponseMode = VoiceResponseMode.NORMAL,
    onResponseMode: (VoiceResponseMode) -> Unit = {},
    speechRate: Float = 1f,
    onSpeechRate: (Float) -> Unit = {},
    ttsVoiceNames: List<String> = emptyList(),
    selectedTtsVoice: String? = null,
    onTtsVoice: (String?) -> Unit = {},
    canEditRoutines: Boolean = true,
) {
    val palette = LocalMotoPalette.current
    // Start on the setup page while a permission is missing, otherwise on the everyday options.
    var tab by rememberSaveable { mutableStateOf(if (items.any { it.required && !it.granted }) SettingsTab.SETUP else SettingsTab.RIDE) }
    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_title), fontSize = if (isNarrowScreen()) 24.sp else 30.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MotoColors.CardHigh).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, null, tint = palette.text, modifier = Modifier.size(32.dp)) }
            }
            val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
            val tabs: @Composable (Modifier) -> Unit = { modifier ->
                SettingsTab.entries.forEach { t ->
                    PillButton(
                        stringResource(t.label),
                        if (t == tab) MotoColors.Blue else palette.cardHigh,
                        { tab = t },
                        modifier,
                        textColor = if (t == tab) Color.White else palette.text,
                        horizontalPadding = 8.dp,
                        minHeight = 64.dp,
                        // Three tabs across an upright phone: one line each, a little smaller.
                        fontSize = if (portrait) 16.sp else 20.sp,
                        singleLine = portrait,
                    )
                }
            }
            val list: @Composable (Modifier) -> Unit = { modifier ->
                val listState = rememberLazyListState()
                Box(modifier) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("settings-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (tab) {
                        SettingsTab.SETUP -> {
                            item {
                                ChoiceRow(
                                    stringResource(R.string.vehicle_setting_title), stringResource(R.string.vehicle_setting_desc),
                                    listOf(
                                        stringResource(R.string.vehicle_moto) to (vehicle == VehicleProfile.MOTORCYCLE),
                                        stringResource(R.string.vehicle_car) to (vehicle == VehicleProfile.CAR),
                                        stringResource(R.string.vehicle_bike) to (vehicle == VehicleProfile.BICYCLE),
                                    ),
                                ) { onVehicle(VehicleProfile.entries[it]) }
                            }
                            items(items) { item -> SettingRow(item) }
                        }
                        SettingsTab.RIDE -> {
                            item { Text(stringResource(R.string.voice_privacy_note), Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MotoColors.Card).padding(14.dp), color = LocalMotoPalette.current.muted) }
                            item {
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(14.dp)) {
                                    ChoiceRow(stringResource(R.string.voice_response_mode_title), stringResource(R.string.voice_response_mode_desc), listOf(stringResource(R.string.voice_response_minimal) to (responseMode == VoiceResponseMode.MINIMAL), stringResource(R.string.voice_response_normal) to (responseMode == VoiceResponseMode.NORMAL), stringResource(R.string.voice_response_detailed) to (responseMode == VoiceResponseMode.DETAILED))) { onResponseMode(VoiceResponseMode.entries[it]) }
                                    Text(stringResource(R.string.voice_speech_rate, (speechRate * 100).toInt()), color = LocalMotoPalette.current.text)
                                    Slider(speechRate, onSpeechRate, valueRange = 0.75f..1.25f)
                                    if (ttsVoiceNames.isEmpty()) Text(stringResource(R.string.voice_tts_missing), color = LocalMotoPalette.current.muted)
                                    else ChoiceRow(stringResource(R.string.voice_tts_title), stringResource(R.string.voice_tts_desc), listOf(stringResource(R.string.voice_tts_default) to (selectedTtsVoice == null)) + ttsVoiceNames.map { it to (it == selectedTtsVoice) }) { index -> onTtsVoice(ttsVoiceNames.getOrNull(index - 1)) }
                                }
                            }
                            item { RoutineManager(routines, onSaveRoutine, onDeleteRoutine, onRunRoutine, canEditRoutines) }
                            if (vehicle == VehicleProfile.BICYCLE) {
                                item {
                                    ChoiceRow(
                                        stringResource(R.string.bike_type_title), stringResource(R.string.bike_type_desc),
                                        listOf(
                                            stringResource(R.string.bike_road) to (bikeType == BikeType.ROAD),
                                            stringResource(R.string.bike_city) to (bikeType == BikeType.CITY),
                                            stringResource(R.string.bike_mountain) to (bikeType == BikeType.MOUNTAIN),
                                        ),
                                    ) { onBikeType(BikeType.entries[it]) }
                                }
                            }
                            item {
                                SettingRow(
                                    SettingItem(
                                        stringResource(R.string.trips_title), stringResource(R.string.trips_desc), false,
                                        stringResource(R.string.action_open), onClick = onOpenTrips,
                                    )
                                )
                            }
                            listOf(R.string.dash_slot_one to 0, R.string.dash_slot_two to 1).forEach { (title, index) ->
                                item {
                                    val order = listOf(DashCard.NAV, DashCard.MEDIA, DashCard.APPS, DashCard.NONE)
                                    ChoiceRow(
                                        stringResource(title), stringResource(R.string.dash_slot_desc),
                                        listOf(
                                            stringResource(R.string.dash_nav), stringResource(R.string.dash_media),
                                            stringResource(R.string.dash_apps), stringResource(R.string.dash_none),
                                        ).mapIndexed { i, label -> label to (dashSlots.getOrNull(index) == order[i]) },
                                    ) { onDashSlot(index, order[it]) }
                                }
                            }
                            items(toggles) { t -> ToggleRow(t) }
                            item { ThemeRow(themeMode, onThemeMode) }
                        }
                        SettingsTab.SAFETY -> item { safetyCard() }
                    }
                }
                // A soft fade at the bottom says there is more below; it is gone when the list fits or ends.
                if (listState.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, palette.scrim)))
                    )
                }
                }
            }
            if (portrait) {
                // An upright phone has no room for a side column: the tabs sit on top.
                Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tabs(Modifier.weight(1f)) }
                    list(Modifier.weight(1f))
                }
            } else {
                Row(modifier = Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(modifier = Modifier.width(190.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        tabs(Modifier.fillMaxWidth())
                    }
                    list(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(t: ToggleItem) {
    // The whole row toggles, not just the small switch: much easier to hit with gloves.
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card)
            .clickable(enabled = t.enabled) { t.onChange(!t.checked) }.padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(t.title, fontSize = 20.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
            Text(t.description, fontSize = 15.sp, color = LocalMotoPalette.current.muted)
        }
        Switch(checked = t.checked, onCheckedChange = t.onChange, enabled = t.enabled)
    }
}

@Composable
private fun SettingRow(item: SettingItem) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontSize = 20.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
            Text(item.description, fontSize = 15.sp, color = LocalMotoPalette.current.muted)
            item.progress?.let { progress ->
                if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                item.progressLabel?.let { Text(it, fontSize = 13.sp, color = LocalMotoPalette.current.muted) }
            }
        }
        if (item.granted) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MotoColors.Green, modifier = Modifier.size(36.dp))
        } else if (item.actionEnabled) {
            PillButton(item.action, MotoColors.Blue, item.onClick)
        } else {
            Text(item.action, color = LocalMotoPalette.current.muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThemeRow(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.theme_title), fontSize = 20.sp, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.theme_desc), fontSize = 15.sp, color = palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                ThemeMode.AUTO to R.string.theme_auto,
                ThemeMode.DAY to R.string.theme_day,
                ThemeMode.NIGHT to R.string.theme_night,
            ).forEach { (m, label) ->
                PillButton(
                    stringResource(label),
                    if (m == mode) MotoColors.Blue else palette.cardHigh,
                    { onSelect(m) },
                    Modifier.weight(1f),
                    textColor = if (m == mode) Color.White else palette.text,
                )
            }
        }
    }
}

/** A title with a row of exclusive buttons; the selected one is blue. */
@Composable
fun ChoiceRow(title: String, description: String, choices: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 20.sp, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(description, fontSize = 15.sp, color = palette.muted)
        // At most three buttons per line (four go two by two), so every name stays on one line even on a narrow phone.
        choices.withIndex().chunked(if (choices.size == 4) 2 else 3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (i, choice) ->
                    val (label, selected) = choice
                    // One line each: a long name such as "Motorcycle" must not wrap inside a third of the width.
                    Box(
                        modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp))
                            .background(if (selected) MotoColors.Blue else palette.cardHigh)
                            .clickable { onSelect(i) }.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                            color = if (selected) Color.White else palette.text,
                        )
                    }
                }
            }
        }
    }
}

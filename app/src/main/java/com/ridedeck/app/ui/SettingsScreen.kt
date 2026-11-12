package com.ridedeck.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridedeck.app.R
import com.ridedeck.app.data.ThemeMode
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.launch

data class SettingItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val action: String,
    /** Without it the app does not work at all; only such rows send the settings page to Permissions. */
    val required: Boolean = false,
    val progress: Float? = null,
    val progressLabel: String? = null,
    val actionEnabled: Boolean = true,
    val onClick: () -> Unit,
)

fun locationGranted(c: Context) = c.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
fun phoneGranted(c: Context) =
    c.hasPermission(Manifest.permission.READ_PHONE_STATE) && c.hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)

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

/** True when the system leaves RideDeck out of battery optimisation, so it is not put to sleep on the road. */
fun ignoresBatteryOptimization(c: Context): Boolean =
    c.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

fun notificationAccessGranted(c: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
fun overlayGranted(c: Context) = Settings.canDrawOverlays(c)

/** The one permission the dashboard cannot work without. */
fun locationPermissions() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Answering and rejecting calls from the dashboard; the ride notification needs the notification permission on Android 13+. */
fun phonePermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
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

/** The six pages of the settings screen; the order here is the order of the category rail. */
enum class SettingsCategory(val label: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_tab_general, Icons.Rounded.Tune),
    APPEARANCE(R.string.settings_tab_appearance, Icons.Rounded.Palette),
    MAP(R.string.settings_tab_map, Icons.Rounded.Map),
    SOUND(R.string.settings_tab_sound, Icons.Rounded.VolumeUp),
    SAFETY(R.string.settings_tab_safety, Icons.Rounded.Shield),
    SYSTEM(R.string.settings_tab_system, Icons.Rounded.Settings),
}

/**
 * One settings page. Rows stay separate composables; the screen stacks them in one column so
 * every card starts at the same edge instead of leaving holes next to a taller neighbour.
 */
class SettingsPage(
    val category: SettingsCategory,
    val rows: List<@Composable () -> Unit>,
)

/**
 * Six categories on a horizontal rail, content in one centred column. The width is capped on a
 * wide landscape dash so controls do not drift to opposite edges; the rail scrolls sideways
 * instead of squeezing six labels.
 */
@Composable
fun SettingsScreen(
    pages: List<SettingsPage>,
    onClose: () -> Unit,
    initialCategory: SettingsCategory = SettingsCategory.GENERAL,
) {
    val palette = LocalMotoPalette.current
    var selectedOrdinal by rememberSaveable { mutableStateOf(initialCategory.ordinal) }
    val current = SettingsCategory.entries.firstOrNull { it.ordinal == selectedOrdinal } ?: SettingsCategory.GENERAL
    val page = pages.firstOrNull { it.category == current } ?: pages.firstOrNull()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(
            // Safe-area inset first, then the 820 dp cap: the inset must not eat into the content width.
            modifier = Modifier.fillMaxHeight().symmetricSafePadding().widthIn(max = 820.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = if (isNarrowScreen()) 20.dp else 28.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    fontSize = if (isNarrowScreen()) 24.sp else 30.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(MotoColors.CardHigh)
                        .hapticClickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, null, tint = palette.text, modifier = Modifier.size(32.dp)) }
            }

            // The rail is the only navigation; one line of 64 dp targets, scrollable on any width.
            SettingsCategoryRail(
                selected = current,
                onSelect = { category ->
                    selectedOrdinal = category.ordinal
                    scope.launch { listState.scrollToItem(0) }
                },
                modifier = Modifier.padding(top = 10.dp),
            )

            if (page == null) return@Column
            Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("settings-list"),
                    contentPadding = PaddingValues(bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(page.rows) { _, row -> row() }
                }
                // A soft fade at the bottom says there is more below; it is gone when the page ends.
                if (listState.canScrollForward) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, palette.scrim))),
                    )
                }
            }
        }
    }
}

/**
 * The one-line category rail. It is internal so UI tests can drive selection on a narrow width and
 * a large font without building the whole settings page stack.
 *
 * A tab is capped to the rail width minus its end content padding, so `animateScrollToItem` always
 * leaves the selected tab wholly visible. The trailing fade appears only while more tabs wait in
 * the scroll direction, doubling as the horizontal scroll hint. TR/EN layouts are LTR; the hint's
 * fade and edge follow `LocalLayoutDirection`.
 */
@Composable
internal fun SettingsCategoryRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    val palette = LocalMotoPalette.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Never wider than the visible rail: the 16 dp end padding must stay outside the tab too.
        val tabMaxWidth = (maxWidth - 16.dp).coerceAtLeast(64.dp)
        // Keyed on the measured rail width, so an embedded/resized rail re-aligns without a
        // screen-size change, and on the font scale, which changes tab widths.
        LaunchedEffect(selected, maxWidth, density.fontScale) {
            state.animateScrollToItem(selected.ordinal)
        }

        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().selectableGroup().testTag("settings-tabs"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            items(SettingsCategory.entries) { category ->
                val active = category == selected
                Row(
                    modifier = Modifier.height(64.dp).widthIn(max = tabMaxWidth)
                        .clip(RoundedCornerShape(32.dp))
                        .background(if (active) MotoColors.Blue else palette.cardHigh)
                        .semantics { this.selected = active }
                        .testTag("settings-tab-${category.name.lowercase()}")
                        .hapticClickable(role = Role.Tab, haptic = RideHaptic.Select) { onSelect(category) }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        category.icon, null,
                        tint = if (active) Color.White else palette.text,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        stringResource(category.label),
                        // The rail is a fixed 64 dp control, so the label scales like the dock does
                        // (up to 140%) instead of growing wider than the screen and clipping.
                        fontSize = cappedSp(16f, maxScale = 1.4f),
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (active) Color.White else palette.text,
                    )
                }
            }
        }
        // The rail's own horizontal scroll hint: a fade only while unseen tabs remain that way.
        if (state.canScrollForward) {
            val ltr = direction == LayoutDirection.Ltr
            Box(
                Modifier.align(Alignment.CenterEnd).width(24.dp).height(64.dp)
                    .background(
                        Brush.horizontalGradient(
                            if (ltr) listOf(Color.Transparent, palette.scrim)
                            else listOf(palette.scrim, Color.Transparent),
                        ),
                    )
                    .testTag("settings-tabs-forward-hint"),
            )
        }
    }
}

@Composable
fun ToggleRow(t: ToggleItem) {
    // The whole row is the single toggle target; the switch is decorative so tapping it cannot
    // create a second focus/click node or toggle twice.
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .settingsCard()
            .testTag("settings-toggle-row")
            .hapticClickable(
                enabled = t.enabled, role = Role.Switch,
                haptic = if (t.checked) RideHaptic.ToggleOff else RideHaptic.ToggleOn,
            ) {
                t.onChange(!t.checked)
            }
            .semantics { toggleableState = ToggleableState(t.checked) }
            .padding(SettingsCardDefaults.Padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(t.title, fontSize = SettingsCardDefaults.TitleSize, lineHeight = 24.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
            Text(t.description, fontSize = SettingsCardDefaults.DescriptionSize, lineHeight = 19.sp, color = LocalMotoPalette.current.muted)
        }
        Switch(
            checked = t.checked,
            onCheckedChange = null,
            enabled = t.enabled,
            modifier = Modifier.testTag("settings-toggle-switch")
                .semantics { if (!t.enabled) disabled() },
        )
    }
}

/**
 * A card with one switch per row. Used for the variable-length message app list: the shared
 * explanation sits once at the top, so a long list stays compact and still scrolls.
 */
@Composable
fun ToggleGroupRow(title: String, description: String, toggles: List<ToggleItem>) {
    if (toggles.isEmpty()) return
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().settingsCard()
            .padding(SettingsCardDefaults.Padding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = SettingsCardDefaults.TitleSize, lineHeight = 24.sp, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(description, fontSize = SettingsCardDefaults.DescriptionSize, lineHeight = 19.sp, color = palette.muted)
        toggles.forEach { t ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.track))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .hapticClickable(
                        enabled = t.enabled, role = Role.Switch,
                        haptic = if (t.checked) RideHaptic.ToggleOff else RideHaptic.ToggleOn,
                    ) {
                        t.onChange(!t.checked)
                    }
                    .semantics { toggleableState = ToggleableState(t.checked) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.title, fontSize = 18.sp, lineHeight = 22.sp, color = palette.text, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                )
                Switch(
                    checked = t.checked,
                    onCheckedChange = null,
                    enabled = t.enabled,
                    modifier = Modifier.testTag("settings-toggle-switch")
                        .semantics { if (!t.enabled) disabled() },
                )
            }
        }
    }
}

@Composable
fun SettingRow(item: SettingItem) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .settingsCard()
            .testTag("settings-setting-row").padding(SettingsCardDefaults.Padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontSize = SettingsCardDefaults.TitleSize, lineHeight = 24.sp, color = LocalMotoPalette.current.text, fontWeight = FontWeight.SemiBold)
            Text(item.description, fontSize = SettingsCardDefaults.DescriptionSize, lineHeight = 19.sp, color = LocalMotoPalette.current.muted)
            item.progress?.let { progress ->
                if (progress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                item.progressLabel?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = LocalMotoPalette.current.muted) }
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
fun ThemeRow(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().settingsCard().padding(SettingsCardDefaults.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.theme_title), fontSize = SettingsCardDefaults.TitleSize, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.theme_desc), fontSize = SettingsCardDefaults.DescriptionSize, color = palette.muted)
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
                    horizontalPadding = 8.dp,
                    fontSize = 16.sp,
                    singleLine = true,
                    haptic = RideHaptic.Select,
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
        modifier = Modifier.fillMaxWidth().settingsCard().padding(SettingsCardDefaults.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = SettingsCardDefaults.TitleSize, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(description, fontSize = SettingsCardDefaults.DescriptionSize, color = palette.muted)
        // At most three buttons per line (four go two by two), so every name stays on one line even on a narrow phone.
        // An incomplete last line keeps empty slots, so its buttons stay the same width as the line above.
        val columns = when {
            choices.size <= 3 -> choices.size.coerceAtLeast(1)
            choices.size == 4 -> 2
            else -> 3
        }
        choices.withIndex().chunked(columns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { (i, choice) ->
                    val (label, selected) = choice
                    // One line each: a long name such as "Motorcycle" must not wrap inside a third of the width.
                    Box(
                        modifier = Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp))
                            .background(if (selected) MotoColors.Blue else palette.cardHigh)
                            .hapticClickable(haptic = RideHaptic.Select) { onSelect(i) }.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                            color = if (selected) Color.White else palette.text,
                        )
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Speech rate for spoken guidance, kept as one big slider. */
@Composable
fun VoiceControlsRow(speechRate: Float, onSpeechRate: (Float) -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().settingsCard().padding(SettingsCardDefaults.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.voice_speech_rate, (speechRate * 100).toInt()), fontSize = SettingsCardDefaults.TitleSize, color = palette.text, fontWeight = FontWeight.SemiBold)
        // Material's inner Slider remains the visual/semantic control at its 48 dp minimum.
        // This 64 dp host handles only the top/bottom edge strips itself; the centre is delegated
        // untouched to Slider so TalkBack SetProgress and the original drag path still work.
        val direction = LocalLayoutDirection.current
        val latestOnSpeechRate by rememberUpdatedState(onSpeechRate)
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .testTag("settings-speech-slider")
                .pointerInput(direction) {
                    val range = 0.75f..1.25f
                    val edge = 10.dp.toPx()
                    fun valueAt(x: Float): Float {
                        val fraction = (x / size.width).coerceIn(0f, 1f)
                        val directional = if (direction == LayoutDirection.Rtl) 1f - fraction else fraction
                        return range.start + directional * (range.endInclusive - range.start)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Central 48 dp belongs to the inner Slider; do not consume or duplicate it.
                        if (down.position.y > edge && down.position.y < size.height - edge) {
                            return@awaitEachGesture
                        }
                        var lastX = down.position.x
                        var moved = false
                        var horizontal = false
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            val change = event.changes.firstOrNull() ?: break
                            lastX = change.position.x
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!moved && dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                moved = true
                                horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
                                if (!horizontal) return@awaitEachGesture
                            }
                            if (horizontal) {
                                latestOnSpeechRate(valueAt(change.position.x))
                                change.consume()
                            }
                            event = awaitPointerEvent()
                        }
                        if (!moved) latestOnSpeechRate(valueAt(lastX))
                    }
                },
        ) {
            Slider(speechRate, onSpeechRate, valueRange = 0.75f..1.25f, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
        }
    }
}

@Composable
fun MarkerRow() {
    val palette = LocalMotoPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val look by MarkerStyle.look.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxWidth().settingsCard().padding(SettingsCardDefaults.Padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.marker_title), fontSize = SettingsCardDefaults.TitleSize, color = palette.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.marker_desc), fontSize = SettingsCardDefaults.DescriptionSize, color = palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                MarkerShape.MODERN to R.string.marker_modern,
                MarkerShape.PUCK to R.string.marker_puck,
                MarkerShape.CLASSIC to R.string.marker_classic,
            ).forEach { (shape, label) ->
                val picked = shape == look.shape
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (picked) MotoColors.Blue else palette.cardHigh)
                        .hapticClickable(haptic = RideHaptic.Select) { MarkerStyle.set(context, look.copy(shape = shape)) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val bmp = remember(shape, look.colorIndex) { markerBitmap(look.copy(shape = shape), 96).asImageBitmap() }
                    Image(bmp, null, Modifier.size(56.dp))
                    Text(stringResource(label), fontSize = 15.sp, color = if (picked) Color.White else palette.text)
                }
            }
        }
        // Six colours in one row: 6 * 64 dp + 5 * 12 dp fits the settings content width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MARKER_COLORS.forEachIndexed { i, argb ->
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .hapticClickable(haptic = RideHaptic.Select) { MarkerStyle.set(context, look.copy(colorIndex = i)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(argb))
                            .then(if (i == look.colorIndex) Modifier.border(4.dp, palette.text, CircleShape) else Modifier),
                    )
                }
            }
        }
    }
}

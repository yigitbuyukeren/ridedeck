package com.ridedeck.app.ui

import com.ridedeck.app.data.WakeWord
import com.ridedeck.app.data.OfflineVoice
import com.ridedeck.app.data.VoiceScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import com.ridedeck.app.data.DashCard
import com.ridedeck.app.util.Units
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import android.Manifest
import android.widget.Toast
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ridedeck.app.DashboardViewModel
import com.ridedeck.app.R
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.SosReason
import com.ridedeck.app.service.OverlayService
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.openMusicApp
import com.ridedeck.app.util.openNavigation
import com.ridedeck.app.data.SosPhase
import com.ridedeck.app.util.hasPermission
import com.ridedeck.app.util.openOverlaySettings
import com.ridedeck.app.util.openSettingsAction

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val context = LocalContext.current
    val gps by vm.gps.collectAsStateWithLifecycle()
    val locationState by vm.locationState.collectAsStateWithLifecycle()
    val battery by vm.battery.state.collectAsStateWithLifecycle()
    val mediaState by vm.media.state.collectAsStateWithLifecycle()
    val callState by vm.call.state.collectAsStateWithLifecycle()
    val callerName by vm.callerName.collectAsStateWithLifecycle()
    val resume by vm.resumeTick.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val vehicleChosen by vm.vehicleChosen.collectAsStateWithLifecycle()
    val routeOptions by vm.nav.options.collectAsStateWithLifecycle()
    val cfg = profile.config
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    val speedLimitOn by vm.speedLimitEnabled.collectAsStateWithLifecycle()
    val speedLimit by vm.speedLimit.limit.collectAsStateWithLifecycle()
    val recordTrips by vm.recordTrips.collectAsStateWithLifecycle()
    var showTrips by remember { mutableStateOf(false) }
    val message by vm.messages.collectAsStateWithLifecycle()
    val autoRead by vm.readMessagesAloud.collectAsStateWithLifecycle()
    var dismissedMessageId by remember { mutableLongStateOf(0L) }
    val themeMode by vm.theme.mode.collectAsStateWithLifecycle()
    val isDay by vm.theme.isDay.collectAsStateWithLifecycle()
    var showMap by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    // The "hold for Settings" caption shows for the first few launches, until the long press has been used.
    var settingsHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val n = Prefs.settingsHintCount(context)
        if (n in 0 until 5) { settingsHint = true; Prefs.setSettingsHintCount(context, n + 1) }
    }
    val dashSlots by vm.dashSlots.collectAsStateWithLifecycle()
    val voicePresentation by vm.voicePresentation.collectAsStateWithLifecycle()
    val voiceScreen by vm.voiceScreen.collectAsStateWithLifecycle()
    val showVoiceHelp by vm.voiceHelp.collectAsStateWithLifecycle()
    val voiceRequests by vm.voiceRequests.collectAsStateWithLifecycle()
    var voiceReplies by remember { mutableStateOf(Prefs.voiceReplies(context)) }
    var voiceHistoryOn by remember { mutableStateOf(Prefs.voiceHistoryOn(context)) }
    val voiceHistory by vm.voiceHistory.collectAsStateWithLifecycle()
    val voiceWake by vm.voiceWakeOn.collectAsStateWithLifecycle()
    val voiceProactive by vm.voiceProactiveOn.collectAsStateWithLifecycle()
    val voiceProactiveKinds by vm.voiceProactiveKinds.collectAsStateWithLifecycle()
    val voiceRoutines by vm.voiceRoutines.collectAsStateWithLifecycle()
    val voiceResponseMode by vm.voiceResponseMode.collectAsStateWithLifecycle()
    val voiceSpeechRate by vm.voiceSpeechRate.collectAsStateWithLifecycle()
    val ttsVoiceNames by vm.voiceTtsNames.collectAsStateWithLifecycle()
    val selectedTtsVoice by vm.selectedTtsVoice.collectAsStateWithLifecycle()
    var voiceContinuous by remember { mutableStateOf(Prefs.voiceContinuous(context)) }
    val voiceButton by vm.voiceButtonOn.collectAsStateWithLifecycle()
    val offlineVoice by vm.offlineVoice.collectAsStateWithLifecycle()
    val voskModelState by vm.voskModelState.collectAsStateWithLifecycle()
    val voskDownloadProgress by vm.voskDownloadProgress.collectAsStateWithLifecycle()
    val voskModelError by vm.voskModelError.collectAsStateWithLifecycle()
    val voiceEngine by vm.voiceEngine.collectAsStateWithLifecycle()
    val wakeMisses by vm.wakeMisses.collectAsStateWithLifecycle()
    var voiceHelmetMic by remember { mutableStateOf(Prefs.voiceHelmetMic(context)) }
    var pendingSearchFallback by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.voiceAssistant.listen(pendingSearchFallback)
    }
    fun startVoice(searchFallback: Boolean, preserveWakeOrigin: Boolean = false) {
        if (!preserveWakeOrigin) vm.clearPendingWake()
        pendingSearchFallback = searchFallback
        if (context.hasPermission(Manifest.permission.RECORD_AUDIO)) vm.voiceAssistant.listen(searchFallback)
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val pinnedApps by vm.pinnedApps.collectAsStateWithLifecycle()
    val messageApps by vm.messageApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshMessageApps() }
    val dialRequest by vm.dialRequest.collectAsStateWithLifecycle()
    val recents by vm.phone.recents.collectAsStateWithLifecycle()
    val contacts by vm.phone.contacts.collectAsStateWithLifecycle()
    val navUi by vm.nav.ui.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val thermal by vm.thermal.state.collectAsStateWithLifecycle()
    // Without a charger a low battery is treated like a hot phone: the maps drop their frame rate.
    val lowPower = thermal.lowPower || (cfg.assumesNoCharger && !battery.charging && battery.percent in 1..LOW_BATTERY_PERCENT)
    val thermalOn by vm.thermalEnabled.collectAsStateWithLifecycle()
    var heatDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(thermal.warn) { if (!thermal.warn) heatDismissed = false }
    val sosUi by vm.sos.ui.collectAsStateWithLifecycle()
    val sosContacts by vm.sosContacts.collectAsStateWithLifecycle()
    val sosCrash by vm.sosCrashEnabled.collectAsStateWithLifecycle()
    val sosButton by vm.sosButtonEnabled.collectAsStateWithLifecycle()
    val weather by vm.weather.snapshot.collectAsStateWithLifecycle()
    val weatherOn by vm.weatherEnabled.collectAsStateWithLifecycle()
    val rainVoice by vm.rainVoice.collectAsStateWithLifecycle()
    var rainDismissed by remember { mutableStateOf(false) }
    var iceDismissed by remember { mutableStateOf(false) }
    var windDismissed by remember { mutableStateOf(false) }
    val windy = weather?.let { w -> cfg.windAlertKmh?.let { w.windKmh >= it } } == true
    LaunchedEffect(windy) { if (!windy) windDismissed = false }
    val raining = weather?.rainInMin != null
    val icy = weather?.iceRisk == true
    LaunchedEffect(raining) { if (!raining) rainDismissed = false }
    LaunchedEffect(icy) { if (!icy) iceDismissed = false }
    // Settings no longer open by themselves; a short permission page follows the vehicle question instead.
    var showSettings by remember { mutableStateOf(false) }
    var showPermIntro by rememberSaveable { mutableStateOf(!locationGranted(context)) }
    LaunchedEffect(dialRequest) {
        if (dialRequest != null) { showPhone = true; showMap = false; showNowPlaying = false; showSettings = false; showTrips = false }
    }
    var overlayEnabled by remember { mutableStateOf(Prefs.overlayEnabled(context)) }

    var pendingDial by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val dialerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onResume()
        pendingDial?.let { (number, name) -> vm.dial(number, name) }
        pendingDial = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onResume()
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onResume() }
    // The second tap on "Allow" after a refusal goes to the app's system page, where Android no longer asks again.
    var locationAsked by remember { mutableStateOf(false) }
    val requestLocation = {
        if (locationAsked && !locationGranted(context)) {
            context.startActivity(
                android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            locationAsked = true
            permissionLauncher.launch(runtimePermissions())
        }
    }
    val phonebookLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.phone.refresh()
        vm.onResume()
    }
    // Recents change whenever a call ends, and the permissions may have been granted while away.
    LaunchedEffect(showPhone, resume, callState.phase) { if (showPhone && callState.phase == CallPhase.IDLE) vm.phone.refresh() }

    // Re-evaluated whenever the activity resumes (e.g. coming back from a system settings page).
    val settingItems = remember(resume, offlineVoice, voskModelState) {
        listOf(
            SettingItem(
                context.getString(R.string.perm_location),
                // Approximate location alone cannot drive a speedometer: say so instead of showing a plain "Allow".
                context.getString(
                    if (!locationGranted(context) && context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) R.string.perm_location_precise
                    else R.string.perm_location_desc
                ),
                locationGranted(context), context.getString(R.string.action_allow), required = true,
            ) {
                permissionLauncher.launch(runtimePermissions())
            },
            SettingItem(context.getString(R.string.perm_phone), context.getString(R.string.perm_phone_desc), phoneGranted(context), context.getString(R.string.action_allow)) {
                permissionLauncher.launch(runtimePermissions())
            },
            SettingItem(context.getString(R.string.perm_dialer), context.getString(R.string.perm_dialer_desc), isDefaultDialer(context), context.getString(R.string.action_set)) {
                dialerLauncher.launch(defaultDialerIntent(context))
            },
            SettingItem(context.getString(R.string.perm_notif), context.getString(R.string.perm_notif_desc), notificationAccessGranted(context), context.getString(R.string.action_open)) {
                context.openSettingsAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            SettingItem(context.getString(R.string.perm_overlay), context.getString(R.string.perm_overlay_desc), overlayGranted(context), context.getString(R.string.action_open)) { context.openOverlaySettings() },
            SettingItem(context.getString(R.string.perm_home), context.getString(R.string.perm_home_desc), isHomeApp(context), context.getString(R.string.action_set)) {
                context.openSettingsAction(Settings.ACTION_HOME_SETTINGS)
            },
            SettingItem(context.getString(R.string.perm_battery), context.getString(R.string.perm_battery_desc), ignoresBatteryOptimization(context), context.getString(R.string.action_open)) {
                context.openSettingsAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            },
            SettingItem(context.getString(R.string.perm_assistant), context.getString(R.string.perm_assistant_desc), isDefaultAssistant(context), context.getString(R.string.action_set)) {
                context.openSettingsAction(Settings.ACTION_VOICE_INPUT_SETTINGS)
            },
            SettingItem(context.getString(R.string.perm_sms), context.getString(R.string.perm_sms_desc), context.hasPermission(Manifest.permission.SEND_SMS), context.getString(R.string.action_allow)) {
                smsLauncher.launch(Manifest.permission.SEND_SMS)
            },
            SettingItem(context.getString(R.string.voice_nicknames_title), context.getString(R.string.voice_nicknames_desc), false, context.getString(R.string.voice_nicknames_action)) {
                vm.clearNicknames()
                Toast.makeText(context, R.string.voice_nicknames_cleared, Toast.LENGTH_SHORT).show()
            },
            SettingItem(context.getString(R.string.voice_misses_title), context.getString(R.string.voice_misses_desc), false, context.getString(R.string.voice_misses_action)) {
                val text = vm.missedPhrases()
                if (text.isBlank()) Toast.makeText(context, R.string.voice_misses_empty, Toast.LENGTH_SHORT).show()
                else context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        ) + listOf(
            SettingItem(
                context.getString(R.string.voice_vosk_title),
                context.getString(when (voskModelState) {
                    com.ridedeck.app.data.VoskModelState.MISSING -> R.string.voice_vosk_missing
                    com.ridedeck.app.data.VoskModelState.DOWNLOADING -> R.string.voice_vosk_downloading
                    com.ridedeck.app.data.VoskModelState.READY -> R.string.voice_vosk_ready
                    com.ridedeck.app.data.VoskModelState.ERROR -> R.string.voice_vosk_error
                }) + if (voskModelState == com.ridedeck.app.data.VoskModelState.ERROR && !voskModelError.isNullOrBlank())
                    "\n${context.getString(R.string.voice_vosk_error_detail, voskModelError)}" else "",
                false,
                context.getString(when (voskModelState) {
                    com.ridedeck.app.data.VoskModelState.READY -> R.string.action_delete
                    com.ridedeck.app.data.VoskModelState.DOWNLOADING -> R.string.voice_vosk_downloading_action
                    else -> R.string.action_download
                }),
                progress = if (voskModelState == com.ridedeck.app.data.VoskModelState.DOWNLOADING)
                    voskDownloadProgress.fraction ?: -1f else null,
                progressLabel = if (voskModelState == com.ridedeck.app.data.VoskModelState.DOWNLOADING) {
                    val mb = 1024f * 1024f
                    if (voskDownloadProgress.total > 0) context.getString(
                        R.string.voice_vosk_progress,
                        voskDownloadProgress.downloaded / mb,
                        voskDownloadProgress.total / mb,
                    ) else context.getString(R.string.voice_vosk_progress_unknown, voskDownloadProgress.downloaded / mb)
                } else null,
                actionEnabled = voskModelState != com.ridedeck.app.data.VoskModelState.DOWNLOADING,
            ) {
                when (voskModelState) {
                    com.ridedeck.app.data.VoskModelState.READY -> vm.deleteVoskModel()
                    com.ridedeck.app.data.VoskModelState.DOWNLOADING -> Unit
                    else -> vm.downloadVoskModel()
                }
            },
        ) + (if (offlineVoice == OfflineVoice.UNKNOWN) emptyList() else listOf(
            SettingItem(
                context.getString(R.string.perm_offline_voice),
                context.getString(if (offlineVoice == OfflineVoice.UNSUPPORTED) R.string.perm_offline_voice_unsupported else R.string.perm_offline_voice_desc),
                offlineVoice == OfflineVoice.INSTALLED, context.getString(R.string.action_download),
            ) { vm.downloadOfflineVoice() },
        ))
    }

    val introRows = remember(resume) {
        listOf(
            PermissionRow(context.getString(R.string.perm_location), context.getString(R.string.perm_location_desc), locationGranted(context), requestLocation),
            PermissionRow(context.getString(R.string.perm_notif), context.getString(R.string.perm_notif_desc), notificationAccessGranted(context)) {
                context.openSettingsAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            PermissionRow(context.getString(R.string.perm_phone), context.getString(R.string.perm_phone_desc), phoneGranted(context)) {
                permissionLauncher.launch(runtimePermissions())
            },
            PermissionRow(
                context.getString(R.string.perm_sms), context.getString(R.string.perm_sms_desc), context.hasPermission(Manifest.permission.SEND_SMS),
            ) { smsLauncher.launch(Manifest.permission.SEND_SMS) },
        )
    }

    LaunchedEffect(showSettings) { if (showSettings) vm.refreshOfflineVoice() }

    // A voice command asked for a screen.
    LaunchedEffect(voiceScreen) {
        val wanted = voiceScreen ?: return@LaunchedEffect
        showMap = false; showNowPlaying = false; showPhone = false; showApps = false; showSettings = false
        when (wanted) {
            VoiceScreen.MAP -> showMap = true
            VoiceScreen.MUSIC -> showNowPlaying = true
            VoiceScreen.PHONE -> showPhone = true
            VoiceScreen.APPS -> showApps = true
            VoiceScreen.SETTINGS -> showSettings = true
            VoiceScreen.HOME -> {}
        }
        vm.voiceScreen.value = null
    }
    // The helmet's voice button (or the system "assist" gesture) asks to listen.
    LaunchedEffect(voiceRequests) {
        if (voiceRequests > 0) startVoice(false, preserveWakeOrigin = true)
    }

    BackHandler(enabled = showMap || showNowPlaying || showPhone || showTrips || showApps) {
        showApps = false
        showTrips = false
        showPhone = false
        showMap = false
        showNowPlaying = false
    }

    // Back closes the settings page; it must not drop out of the app. The first-launch question cannot be skipped
    // and an SOS countdown cannot be cancelled by accident (only its own button does that).
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = vehicleChosen == null) {}
    BackHandler(enabled = vehicleChosen != null && showPermIntro) { showPermIntro = false }
    BackHandler(enabled = sosUi.phase != SosPhase.IDLE) { if (sosUi.phase != SosPhase.COUNTDOWN) vm.sos.dismiss() }

    Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize().background(LocalMotoPalette.current.wallpaper)) {
        Box(modifier = Modifier.fillMaxSize()) {
        val risePx = with(LocalDensity.current) { 32.dp.roundToPx() }
        val overlayEnter = MotoMotion.overlayEnter(lowPower, risePx)
        val overlayExit = MotoMotion.overlayExit(lowPower, risePx)
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val dock: @Composable () -> Unit = {
                Dock(
                    battery = battery,
                    onNavigation = { showMap = !showMap; showNowPlaying = false; showPhone = false; showSettings = false; showApps = false },
                    onMusic = { showNowPlaying = !showNowPlaying; showMap = false; showPhone = false; showSettings = false; showApps = false },
                    onPhone = { vm.dialRequest.value = null; showPhone = !showPhone; showMap = false; showNowPlaying = false; showSettings = false; showApps = false },
                    onApps = { showApps = !showApps; showMap = false; showNowPlaying = false; showPhone = false; showSettings = false },
                    onSettings = {
                        Prefs.setSettingsHintCount(context, -1); settingsHint = false
                        showSettings = true; showMap = false; showNowPlaying = false; showPhone = false; showApps = false
                    },
                    settingsHint = settingsHint,
                    active = when { showMap -> DockTab.NAV; showNowPlaying -> DockTab.MUSIC; showPhone -> DockTab.PHONE; showApps -> DockTab.APPS; else -> DockTab.NONE },
                    horizontal = portrait,
                )
            }
            val content: @Composable (Modifier) -> Unit = { outer ->
                val screen = when { showPhone -> MotoScreen.PHONE; showNowPlaying -> MotoScreen.MUSIC; showMap -> MotoScreen.MAP; else -> MotoScreen.HOME }
                AnimatedContent(
                    targetState = screen, modifier = outer, label = "screen",
                    transitionSpec = { MotoMotion.screen(initialState, targetState, lowPower) },
                ) { target ->
                val m = Modifier.fillMaxSize()
                if (target == MotoScreen.PHONE) {
                    PhoneScreen(
                        recents = recents,
                        contacts = contacts,
                        hasAccess = phonebookGranted(context),
                        keypadLocked = gps.speedKmh >= PHONE_LOCK_KMH,
                        initialNumber = dialRequest,
                        onGrantAccess = { phonebookLauncher.launch(phonebookPermissions()) },
                        dialerHint = !isDefaultDialer(context),
                        onSetDialer = { dialerLauncher.launch(defaultDialerIntent(context)) },
                        // Once, offer to make RideDeck the phone app; otherwise the phone's own call screen fights with ours.
                        onDial = { number, name ->
                            if (!isDefaultDialer(context) && !Prefs.dialerPrompted(context)) {
                                Prefs.setDialerPrompted(context)
                                pendingDial = number to name
                                dialerLauncher.launch(defaultDialerIntent(context))
                            } else vm.dial(number, name)
                        },
                        onMic = { startVoice(false) },
                        modifier = m,
                    )
                } else if (target == MotoScreen.MUSIC) {
                    NowPlayingScreen(
                        state = mediaState,
                        media = vm.media,
                        onGrantAccess = { context.openSettingsAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                        onMic = { startVoice(false) },
                        modifier = m,
                    )
                } else if (target == MotoScreen.MAP) {
                    MapScreen(
                        nav = vm.nav,
                        locationState = vm.locationState,
                        speedKmh = gps.speedKmh,
                        speedLimit = speedLimit,
                        isDay = isDay,
                        lowPower = lowPower,
                        places = places,
                        onSavePlace = vm::savePlace,
                        onRemovePlace = vm::removePlace,
                        onMic = { startVoice(true) },
                        onClose = { showMap = false },
                        modifier = m,
                    )
                } else Column(modifier = m) {
                    val speedCard: @Composable (Modifier) -> Unit = { mod ->
                        SpeedCard(gps, speedLimit, locationGranted = locationGranted(context), onGrantLocation = requestLocation, maxKmh = cfg.gaugeMaxKmh, speedProvider = vm::displaySpeedKmh, lowPower = lowPower, onSos = if (sosButton) ({ vm.sos.trigger(SosReason.MANUAL) }) else null, modifier = mod, onClick = { showTrips = true })
                    }
                    val navCard: @Composable (Modifier) -> Unit = { mod ->
                        NavCard(
                            navUi, places, weather.takeIf { weatherOn },
                            me = locationState, isDay = isDay, lowPower = lowPower,
                            onOpenMap = { showMap = true },
                            onGoTo = { vm.nav.selectPlace(it); showMap = true },
                            onMic = { startVoice(false) },
                            modifier = mod)
                    }
                    val mediaCard: @Composable (Modifier) -> Unit = { mod ->
                        MediaCard(
                            state = mediaState,
                            media = vm.media,
                            onGrantAccess = { context.openSettingsAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                            onOpenNowPlaying = { showNowPlaying = true },
                            modifier = mod,
                        )
                    }
                    val appsCard: @Composable (Modifier) -> Unit = { mod ->
                        AppsCard(pinnedApps, onOpenShelf = { showApps = true }, modifier = mod)
                    }
                    // The speed card is fixed; the other two slots show what the rider chose in Settings.
                    val slotCard: @Composable (DashCard, Modifier) -> Unit = { card, mod ->
                        when (card) {
                            DashCard.NAV -> navCard(mod)
                            DashCard.MEDIA -> mediaCard(mod)
                            DashCard.APPS -> appsCard(mod)
                            DashCard.NONE -> {}
                        }
                    }
                    val shownSlots = dashSlots.filter { it != DashCard.NONE }
                    if (portrait) {
                        // Upright phone: speed on top, the chosen cards below.
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            speedCard(Modifier.weight(1.1f).fillMaxWidth())
                            shownSlots.forEach { card ->
                                slotCard(card, Modifier.weight(when (card) { DashCard.NAV -> 1.3f; DashCard.MEDIA -> 0.9f; else -> 0.6f }).fillMaxWidth())
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            speedCard(Modifier.weight(1f).fillMaxSize())
                            if (shownSlots.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.weight(1.15f).fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    shownSlots.forEach { card -> slotCard(card, Modifier.weight(1f).fillMaxSize()) }
                                }
                            }
                        }
                    }
                }
                }
            }
            if (portrait) {
                Column(modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                    content(Modifier.weight(1f).fillMaxWidth())
                    dock()
                }
            } else {
                Row(modifier = Modifier.fillMaxSize().padding(end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    dock()
                    content(Modifier.weight(1f).fillMaxSize())
                }
            }

            val shownMessage = message?.takeIf { it.id != dismissedMessageId && callState.phase == CallPhase.IDLE }
            if (shownMessage != null && !showSettings) {
                MessageBanner(
                    message = shownMessage,
                    onRead = { vm.speakMessage(shownMessage) },
                    onDismiss = { dismissedMessageId = shownMessage.id },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }

            // Not over the map: there the top left holds the route or manoeuvre card, and rain, ice and heat are also spoken.
            if (!showSettings && !showMap && !showPhone && callState.phase == CallPhase.IDLE && shownMessage == null) {
                val w = weather
                val rainIn = w?.rainInMin
                when {
                    thermal.warn && !heatDismissed -> AlertStrip(
                        text = stringResource(R.string.thermal_banner),
                        accent = MotoColors.Red,
                        onDismiss = { heatDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                    weatherOn && w != null && rainIn != null && !rainDismissed -> AlertStrip(
                        text = if (rainIn > 0) stringResource(R.string.rain_banner_fmt, rainIn) else stringResource(R.string.rain_banner_now),
                        accent = MotoColors.Blue,
                        onDismiss = { rainDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                    weatherOn && w != null && w.iceRisk && !iceDismissed -> AlertStrip(
                        text = stringResource(R.string.ice_banner_fmt, w.tempC),
                        accent = MotoColors.Cyan,
                        onDismiss = { iceDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                    weatherOn && w != null && windy && !windDismissed -> AlertStrip(
                        text = stringResource(if (Units.miles) R.string.wind_banner_fmt_mph else R.string.wind_banner_fmt, Units.speed(w.windKmh)),
                        accent = MotoColors.Orange,
                        onDismiss = { windDismissed = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                }
            }

        }

        // Settings and trips cover the whole screen, cutout strip included; they pad their own content.
            AnimatedVisibility(visible = showSettings, enter = overlayEnter, exit = overlayExit) {
                val wakeAvailable = if (voiceEngine == com.ridedeck.app.data.SelectableVoiceInput.ENGINE_VOSK) {
                    voskModelState == com.ridedeck.app.data.VoskModelState.READY
                } else WakeWord.available(context)
                val toggles = listOf(
                    ToggleItem(stringResource(R.string.msg_auto_title), stringResource(R.string.msg_auto_desc), autoRead, vm::setReadMessagesAloud),
                    ) + messageApps.map { row ->
                        ToggleItem(stringResource(R.string.msg_app_title_fmt, row.label), stringResource(R.string.msg_app_desc), !row.muted) { on -> vm.setMessageAppMuted(row.pkg, !on) }
                    } + listOf(
                    ToggleItem(stringResource(R.string.weather_title), stringResource(R.string.weather_desc), weatherOn, vm::setWeatherEnabled),
                    ToggleItem(stringResource(R.string.rain_voice_title), stringResource(R.string.rain_voice_desc), rainVoice, vm::setRainVoice),
                    ToggleItem(stringResource(R.string.thermal_title), stringResource(R.string.thermal_desc), thermalOn, vm::setThermalEnabled),
                    ToggleItem(stringResource(R.string.speed_limit_title), stringResource(R.string.speed_limit_desc), speedLimitOn, vm::setSpeedLimitEnabled),
                    ToggleItem(stringResource(R.string.voice_replies_title), stringResource(R.string.voice_replies_desc), voiceReplies) { voiceReplies = it; vm.setVoiceRepliesEnabled(it) },
                    ToggleItem(stringResource(R.string.voice_history_title), stringResource(R.string.voice_history_desc), voiceHistoryOn) { voiceHistoryOn = it; Prefs.setVoiceHistoryOn(context, it); if (!it) vm.clearVoiceHistory() },
                    ToggleItem(stringResource(R.string.voice_continuous_title), stringResource(R.string.voice_continuous_desc), voiceContinuous) { voiceContinuous = it; Prefs.setVoiceContinuous(context, it) },
                    ToggleItem(stringResource(R.string.voice_proactive_title), stringResource(R.string.voice_proactive_desc), voiceProactive, vm::setVoiceProactive),
                ) + com.ridedeck.app.data.ProactiveKind.entries.map { kind ->
                        val label = when (kind) {
                            com.ridedeck.app.data.ProactiveKind.RAIN -> R.string.voice_proactive_rain_category
                            com.ridedeck.app.data.ProactiveKind.BATTERY -> R.string.voice_proactive_battery_category
                            com.ridedeck.app.data.ProactiveKind.TEMPERATURE -> R.string.voice_proactive_temperature_category
                            com.ridedeck.app.data.ProactiveKind.ARRIVAL -> R.string.voice_proactive_arrival_category
                            com.ridedeck.app.data.ProactiveKind.BREAK -> R.string.voice_proactive_break_category
                            com.ridedeck.app.data.ProactiveKind.BLUETOOTH -> R.string.voice_proactive_bluetooth_category
                            com.ridedeck.app.data.ProactiveKind.NAVIGATION_ERROR -> R.string.voice_proactive_navigation_error_category
                        }
                        ToggleItem(stringResource(label), stringResource(R.string.voice_proactive_desc), voiceProactiveKinds[kind] ?: true) { vm.setVoiceProactiveKind(kind, it) }
                } + listOf(
                    ToggleItem(stringResource(R.string.voice_button_title), stringResource(R.string.voice_button_desc), voiceButton, vm::setVoiceButton),
                    ToggleItem(
                        stringResource(R.string.voice_wake_title),
                        if (voiceEngine == com.ridedeck.app.data.SelectableVoiceInput.ENGINE_VOSK) {
                            context.getString(if (voskModelState == com.ridedeck.app.data.VoskModelState.READY) R.string.voice_wake_vosk_desc else R.string.voice_wake_vosk_unavailable, wakeMisses)
                        } else {
                            context.getString(if (wakeAvailable) R.string.voice_wake_desc else R.string.voice_wake_unavailable, wakeMisses)
                        },
                        voiceWake,
                    ) { enabled ->
                        if (enabled && !wakeAvailable) Toast.makeText(context, R.string.voice_wake_enable_unavailable, Toast.LENGTH_SHORT).show()
                        else vm.setVoiceWake(enabled)
                    },
                    ToggleItem(stringResource(R.string.voice_helmet_title), stringResource(R.string.voice_helmet_desc), voiceHelmetMic) { voiceHelmetMic = it; Prefs.setVoiceHelmetMic(context, it) },
                    ToggleItem(stringResource(R.string.units_title), stringResource(R.string.units_desc), Units.miles) { Units.set(context, it) },
                    ToggleItem(stringResource(R.string.record_trips_title), stringResource(R.string.record_trips_desc), recordTrips, vm::setRecordTrips),
                    ToggleItem(stringResource(R.string.overlay_title), stringResource(R.string.overlay_desc), overlayEnabled) { enabled ->
                        overlayEnabled = toggleOverlay(context, enabled)
                    },
                )
                SettingsScreen(
                    items = settingItems,
                    toggles = toggles,
                    onOpenTrips = { showSettings = false; showTrips = true },
                    themeMode = themeMode,
                    onThemeMode = vm.theme::setMode,
                    onClose = { showSettings = false },
                    vehicle = profile,
                    onVehicle = vm::setVehicle,
                    dashSlots = dashSlots,
                    onDashSlot = vm::setDashSlot,
                    bikeType = routeOptions.bikeType,
                    onBikeType = { vm.nav.setOptions(routeOptions.copy(bikeType = it)) },
                    routines = voiceRoutines,
                    onSaveRoutine = vm::saveVoiceRoutine,
                    onDeleteRoutine = vm::deleteVoiceRoutine,
                    onRunRoutine = vm.voiceAssistant::runRoutine,
                    responseMode = voiceResponseMode,
                    onResponseMode = vm::setVoiceResponseMode,
                    speechRate = voiceSpeechRate,
                    onSpeechRate = vm::setVoiceSpeechRate,
                    ttsVoiceNames = ttsVoiceNames,
                    selectedTtsVoice = selectedTtsVoice,
                    onTtsVoice = vm::setTurkishTtsVoice,
                    voiceEngine = voiceEngine,
                    voskReady = voskModelState == com.ridedeck.app.data.VoskModelState.READY,
                    onVoiceEngine = vm::setVoiceEngine,
                    onVoskUnavailable = { Toast.makeText(context, R.string.voice_engine_vosk_download_first, Toast.LENGTH_SHORT).show() },
                    canEditRoutines = com.ridedeck.app.data.RideRoutines.canEditAtSpeed(gps.speedKmh, gps.hasFix),
                    safetyCard = {
                        SosSettingsCard(
                            contacts = sosContacts,
                            onAdd = vm::addSosContact,
                            onRemove = vm::removeSosContact,
                            crashEnabled = sosCrash,
                            onCrashEnabled = vm::setSosCrashEnabled,
                            buttonEnabled = sosButton,
                            onButtonEnabled = vm::setSosButtonEnabled,
                            onTest = { vm.sos.trigger(SosReason.TEST) },
                        )
                    },
                )
            }

            AnimatedVisibility(visible = showApps, enter = overlayEnter, exit = overlayExit) {
                AppsScreen(
                    pinned = pinnedApps,
                    locked = gps.speedKmh >= PHONE_LOCK_KMH,
                    onPin = vm::pinApp, onUnpin = vm::unpinApp, onMove = vm::moveApp,
                    onSettings = { showApps = false; showSettings = true },
                    onClose = { showApps = false },
                )
            }

            AnimatedVisibility(visible = showTrips, enter = overlayEnter, exit = overlayExit) {
                TripsScreen(trips = vm.trips, onClose = { showTrips = false })
            }


        // The call screen paints edge to edge (it pads its own content); inside the padded box it left a bare strip at the cutout.
        if (callState.phase != CallPhase.IDLE) {
            CallScreen(
                callState, callerName, gps.speedKmh, navUi,
                onAnswer = vm.call::answer, onHangUp = vm.call::hangUp, onMute = vm.call::toggleMute, onRoute = vm.call::setRoute,
                onVolumeUp = vm.call::volumeUp, onVolumeDown = vm.call::volumeDown, onDigit = vm.call::sendDigit,
            )
        }

        // The first-launch question sits above everything but the SOS countdown.
        if (vehicleChosen == null) VehicleChooser(onChoose = vm::setVehicle)
        else if (showPermIntro) PermissionIntro(introRows, onClose = { showPermIntro = false })

        AnimatedVisibility(visible = showVoiceHelp, enter = overlayEnter, exit = overlayExit) {
            VoiceHelpScreen(history = voiceHistory, onClear = vm::clearVoiceHistory, onClose = { vm.voiceHelp.value = false })
        }

        VoiceOverlay(voicePresentation, onCancel = { vm.clearPendingWake(); vm.voiceAssistant.cancel() }, onChoose = vm.voiceAssistant::choose, onHelp = { vm.clearPendingWake(); vm.voiceAssistant.cancel(); vm.voiceHelp.value = true })
        VoiceWakeIndicator(voicePresentation, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))

        // Last, so nothing can cover an SOS countdown.
        SosScreen(sosUi, sosContacts.map { it.name }, onCancel = vm.sos::cancel, onDismiss = vm.sos::dismiss)
        }
    }
}

/** Below this charge, a phone with no charger on board saves power. */
private const val LOW_BATTERY_PERCENT = 30

/** No number entry while riding, same limit as the map search box. */
private const val PHONE_LOCK_KMH = 5

private fun toggleOverlay(context: Context, enabled: Boolean): Boolean {
    if (enabled && !overlayGranted(context)) {
        context.openOverlaySettings()
        return false
    }
    Prefs.setOverlayEnabled(context, enabled)
    if (enabled) OverlayService.start(context) else OverlayService.stop(context)
    return enabled
}

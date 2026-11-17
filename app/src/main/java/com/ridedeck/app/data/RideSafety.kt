package com.ridedeck.app.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import com.ridedeck.app.service.SosActionReceiver
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The one SOS channel; the settings card and the alert planner must agree on its state. */
internal const val SOS_CHANNEL_ID = "sos"

/**
 * Crash detection and SOS for the whole process. A screen used to own these, so they stopped the
 * moment the rider switched to the music app or the screen went dark. Now the accelerometer listens
 * while the dashboard is on screen *or* a started ride (foreground service) is running, and a
 * countdown that begins with the screen off comes to the front through a full-screen notification.
 *
 * The notification is a convenience, not the safety net: when it cannot be posted (notifications
 * off, permission denied) or a full-screen intent is refused, the beep, the voice and the SMS still
 * run, the screen is woken and the app is brought to the front when Android allows it.
 */
class RideSafety(context: Context, private val tracking: RideTracking, navigation: RideNavigation) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = MutableStateFlow(0)
    private val power: PowerManager? = app.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    val contacts = MutableStateFlow(SosMath.fromJson(Prefs.sosContacts(app)))
    val crashEnabled = MutableStateFlow(Prefs.sosCrash(app))
    val buttonEnabled = MutableStateFlow(Prefs.sosButton(app))

    /**
     * A trigger is not "enabled" just because the preference is true: without a contact, SMS
     * permission, telephony or a usable default SIM there is nobody to reach. Both crash detection
     * and the dashboard button read this effective fact, not only the stored switch.
     */
    val ready = MutableStateFlow(false)

    val sos: SosController = SosController(
        scope = scope,
        location = tracking.location.state,
        contacts = contacts,
        sms = AndroidSmsSender(app),
        alarm = AndroidSosAlarm(app),
        smsAllowed = { app.hasPermission(Manifest.permission.SEND_SMS) },
        speak = { reason, seconds ->
            navigation.voice.speak(
                when (reason) {
                    SosReason.AUTO -> app.getString(R.string.sos_voice_auto, seconds)
                    SosReason.MANUAL -> app.getString(R.string.sos_voice_manual, seconds)
                    SosReason.TEST -> app.getString(R.string.sos_voice_test)
                },
                SpeechPriority.SOS,
            )
        },
        message = { reason, link, stale ->
            val head = if (reason == SosReason.AUTO) {
                app.getString(R.string.sos_sms_auto, app.getString(R.string.sos_noun_moto))
            } else app.getString(R.string.sos_sms_manual)
            SosMath.compose(
                head, link, stale,
                none = app.getString(R.string.sos_loc_none),
                staleFmt = app.getString(R.string.sos_loc_stale),
                fmt = app.getString(R.string.sos_loc),
            )
        },
        onCancelled = ::acknowledgeCrashCancel,
    )

    private val crashSensor: CrashSensor = CrashSensor(app, speedKmh = {
        val fix = tracking.location.state.value
        if (fix.hasSpeed && fix.hasFreshFix(System.currentTimeMillis(), SystemClock.elapsedRealtime())) fix.speedKmh else null
    }, config = { VehicleProfile.MOTORCYCLE.config }) { sos.trigger(SosReason.AUTO) }

    /** The accelerometer runs while the dashboard is on screen or an explicitly started ride is active. */
    val armed: StateFlow<Boolean> get() = armedState
    private val armedState = MutableStateFlow(false)

    init {
        AndroidSosAlarm(app).restoreVolumeIfPending()
        refreshReadiness()
        val riding = tracking.session.state.map { it.phase == RidePhase.ACTIVE }
        scope.launch {
            combine(crashEnabled, screens, riding, ready) { on, screens, riding, ready ->
                on && ready && (screens > 0 || riding)
            }
                .distinctUntilChanged().collect { armed ->
                    armedState.value = armed
                    if (armed) crashSensor.start() else crashSensor.stop()
                }
        }
        scope.launch {
            // React to BOTH the phase and screen visibility. A countdown can start while the
            // dashboard is visible and the screen can go dark a second later; a phase-only
            // collector would miss that transition and never post the lock-screen alert.
            combine(sos.ui.map { it.phase }.distinctUntilChanged(), screens) { phase, visible -> phase to visible }
                .distinctUntilChanged()
                .collect { (phase, visible) ->
                    val ui = sos.ui.value
                    when {
                        phase == SosPhase.IDLE -> cancelAlert()
                        visible > 0 -> cancelAlert()
                        // Screen off or another app in front: bring the countdown up so "I am fine" can be pressed.
                        phase == SosPhase.COUNTDOWN -> showCountdownAlert(ui.reason)
                        phase == SosPhase.SENDING -> showSendingAlert()
                        phase in setOf(SosPhase.SENT, SosPhase.PARTIAL, SosPhase.UNKNOWN, SosPhase.FAILED) -> showResultAlert(ui)
                    }
                }
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screens.value = (screens.value + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible) {
            cancelAlert()
            refreshReadiness()
        }
    }

    fun setCrashEnabled(enabled: Boolean) {
        Prefs.setSosCrash(app, enabled)
        crashEnabled.value = enabled
        refreshReadiness()
    }

    fun setButtonEnabled(enabled: Boolean) {
        Prefs.setSosButton(app, enabled)
        buttonEnabled.value = enabled
        refreshReadiness()
    }

    /**
     * Re-reads the facts that decide whether an alert can reach anybody at all. Permission
     * dialogs normally pause rather than stop the activity, so the dashboard also calls this on
     * every resume; otherwise granting SMS would leave the button hidden until a cold start.
     */
    fun refreshReadiness() {
        // Fail closed: an unreadable telephony/SIM state must not promise a send path.
        val telephony = runCatching {
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }.getOrDefault(false)
        val sim = runCatching {
            SmsSubscriptionPolicy.usable(SubscriptionManager.getDefaultSmsSubscriptionId())
        }.getOrDefault(false)
        ready.value = SosReadiness.triggersReady(
            contacts = contacts.value.size,
            smsPermission = app.hasPermission(Manifest.permission.SEND_SMS),
            telephony = telephony,
            defaultSmsUsable = sim,
        )
    }

    fun addContact(contact: SosContact) = updateContacts(SosMath.addContact(contacts.value, contact))

    fun removeContact(contact: SosContact) = updateContacts(contacts.value - contact)

    /** "İyiyim" from the lock-screen notification; the controller tells the detector to re-arm. */
    fun cancelFromNotification() {
        runCatching { sos.cancel() }
        cancelAlert()
    }

    /** "Kapat" on a result notification. */
    fun dismissFromNotification() {
        runCatching { sos.dismiss() }
        cancelAlert()
    }

    private fun acknowledgeCrashCancel() = crashSensor.acknowledgeCancel()

    private fun updateContacts(list: List<SosContact>) {
        contacts.value = list
        Prefs.setSosContacts(app, SosMath.toJson(list))
        // Without a contact there is nobody to alert, so the triggers switch themselves off.
        if (list.isEmpty()) {
            setCrashEnabled(false)
            setButtonEnabled(false)
        } else {
            refreshReadiness()
        }
    }

    private fun showCountdownAlert(reason: SosReason) {
        val plan = alertPlan()
        if (plan.wakeScreen) acquireCountdownWakeLock()
        post(
            plan,
            title = app.getString(
                when (reason) {
                    SosReason.AUTO -> R.string.sos_count_auto
                    SosReason.TEST -> R.string.sos_count_test
                    SosReason.MANUAL -> R.string.sos_count_manual
                },
            ),
            text = app.getString(
                when (reason) {
                    SosReason.AUTO -> R.string.sos_count_hint_auto
                    SosReason.TEST -> R.string.sos_test_note
                    SosReason.MANUAL -> R.string.sos_count_hint_manual
                },
            ),
            cancelLabel = app.getString(R.string.sos_action_fine),
            dismissLabel = null,
        )
    }

    private fun showSendingAlert() {
        // The countdown is over: nothing can be cancelled any more, so the cancel action goes away.
        releaseCountdownWakeLock()
        // A result is information, not an alarm: never launch the app over the lock screen for it.
        // Contact names may be in the text, so keep the result content off the lock screen.
        post(
            alertPlan().copy(fullScreenIntent = false, launchApp = false),
            app.getString(R.string.sos_sending),
            app.getString(R.string.sos_delivery_note),
            null,
            null,
            visibility = NotificationCompat.VISIBILITY_PRIVATE,
        )
    }

    private fun showResultAlert(ui: SosUi) {
        releaseCountdownWakeLock()
        post(
            alertPlan().copy(fullScreenIntent = false, launchApp = false),
            resultTitle(ui),
            resultText(ui),
            null,
            app.getString(R.string.sos_action_close),
            visibility = NotificationCompat.VISIBILITY_PRIVATE,
        )
    }

    private fun resultTitle(ui: SosUi): String = app.getString(
        if (ui.reason == SosReason.TEST) R.string.sos_count_test
        else when (ui.phase) {
            SosPhase.SENT -> R.string.sos_sent
            SosPhase.PARTIAL -> R.string.sos_partial
            SosPhase.UNKNOWN -> R.string.sos_unknown
            else -> R.string.sos_failed
        }
    )

    private fun resultText(ui: SosUi): String = when {
        // A test run must never look like a real send, not even on the lock screen.
        ui.reason == SosReason.TEST -> app.getString(R.string.sos_test_note)
        ui.phase == SosPhase.SENT && ui.sentTo.isNotEmpty() ->
            app.getString(R.string.sos_sent_to_fmt, ui.sentTo.joinToString(", "))
        ui.phase == SosPhase.FAILED -> app.getString(
            when (ui.error) {
                SosError.NO_CONTACTS -> R.string.sos_err_no_contacts
                SosError.NO_PERMISSION -> R.string.sos_err_no_permission
                SosError.NO_SIM -> R.string.sos_err_no_sim
                else -> R.string.sos_err_send_failed
            }
        )
        else -> app.getString(R.string.sos_delivery_note)
    }

    /** Platform facts for [SosAlertPolicy]; every query is defensive so a restricted OEM ROM cannot crash a countdown. */
    private fun alertPlan(): SosAlertPlan {
        val manager = NotificationManagerCompat.from(app)
        val appEnabled = runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)
        // A blocked SOS channel suppresses the notification even while app notifications stay on.
        val channelBlocked = runCatching {
            app.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(SOS_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
        val notificationsEnabled = appEnabled && !channelBlocked
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val fullScreenAllowed = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { app.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true }.getOrDefault(true)
        } else true
        val screenOff = power?.isInteractive == false
        val overlay = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)
        val canStartActivity = overlay || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        return SosAlertPolicy.plan(notificationsEnabled, permission, fullScreenAllowed, screenOff, canStartActivity)
    }

    private fun post(
        plan: SosAlertPlan,
        title: String,
        text: String,
        cancelLabel: String?,
        dismissLabel: String?,
        visibility: Int = NotificationCompat.VISIBILITY_PUBLIC,
    ) {
        if (!plan.postNotification) {
            // No notification possible: remove a stale alert from an earlier phase, then try the
            // activity fallback. The beep/voice/SMS go on regardless.
            cancelNotification()
            fallbackToActivity()
            return
        }
        createChannel()
        val open = PendingIntent.getActivity(
            app, REQUEST_OPEN,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ridedeck)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setVisibility(visibility)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
        // Full-screen intents can be refused by the user (API 34+) or by the OEM; a high-priority
        // heads-up notification plus the alarm is the fallback.
        if (plan.fullScreenIntent) builder.setFullScreenIntent(open, true)
        if (cancelLabel != null) {
            builder.addAction(0, cancelLabel, action(SosActionReceiver.ACTION_CANCEL, REQUEST_CANCEL))
        }
        if (dismissLabel != null) {
            builder.addAction(0, dismissLabel, action(SosActionReceiver.ACTION_DISMISS, REQUEST_DISMISS))
        }
        // The plan already checked this; re-check so a permission revoked in between cannot crash and lint sees it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cancelNotification()
            fallbackToActivity()
            return
        }
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
        } catch (_: RuntimeException) {
            // Permission withdrawn between the check and notify, or a vendor restriction: a stale
            // countdown notification must not outlive the phase that posted it.
            cancelNotification()
            fallbackToActivity()
        }
    }

    /**
     * A notification can disappear between planning and posting (permission revoked, channel
     * blocked, OEM rejection). Re-evaluate the activity fallback instead of reusing the stale
     * plan, and do not leave a countdown wake lock lighting a screen with nothing on it.
     */
    private fun fallbackToActivity() {
        val canLaunch = runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (!canLaunch && power?.isInteractive == false) releaseCountdownWakeLock()
        bringToFront(alertPlan().copy(launchApp = canLaunch))
    }

    private fun action(actionName: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        app, requestCode,
        Intent(app, SosActionReceiver::class.java).setAction(actionName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, app.getString(R.string.sos_title), NotificationManager.IMPORTANCE_HIGH).apply {
            description = app.getString(R.string.sos_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Wakes the screen for the countdown head start; the timeout covers a rider who cannot reach the phone. */
    private fun acquireCountdownWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            @Suppress("DEPRECATION")
            val lock = power?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$WAKELOCK_TAG:sos",
            )
            lock?.setReferenceCounted(false)
            lock?.acquire(WAKELOCK_TIMEOUT_MS)
            wakeLock = lock
        } catch (_: RuntimeException) {
            // WAKE_LOCK missing or the vendor refused it: the alarm and the notification still work.
            wakeLock = null
        }
    }

    private fun releaseCountdownWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    /** Background activity starts are refused on Android 10+ unless the app holds an overlay exemption. */
    private fun bringToFront(plan: SosAlertPlan) {
        if (!plan.launchApp) return
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        } catch (_: RuntimeException) {
            // Blocked by the launcher/OS: nothing else to try, and the countdown must not fail because of it.
        }
    }

    private fun cancelNotification() {
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
    }

    private fun cancelAlert() {
        releaseCountdownWakeLock()
        cancelNotification()
    }

    private companion object {
        const val CHANNEL = SOS_CHANNEL_ID
        const val NOTIFICATION_ID = 40
        const val REQUEST_OPEN = 40
        const val REQUEST_CANCEL = 41
        const val REQUEST_DISMISS = 42
        const val WAKELOCK_TAG = "RideDeck"
        const val WAKELOCK_TIMEOUT_MS = 60_000L
    }
}

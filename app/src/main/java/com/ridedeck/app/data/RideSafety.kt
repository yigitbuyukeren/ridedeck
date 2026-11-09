package com.ridedeck.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
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

/**
 * Crash detection and SOS for the whole process. A screen used to own these, so they stopped the
 * moment the rider switched to the music app or the screen went dark. Now the accelerometer listens
 * while the dashboard is on screen *or* a started ride (foreground service) is running, and a
 * countdown that begins with the screen off comes to the front through a full-screen notification.
 */
class RideSafety(context: Context, private val tracking: RideTracking, navigation: RideNavigation) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val screens = MutableStateFlow(0)

    val contacts = MutableStateFlow(SosMath.fromJson(Prefs.sosContacts(app)))
    val crashEnabled = MutableStateFlow(Prefs.sosCrash(app))
    val buttonEnabled = MutableStateFlow(Prefs.sosButton(app))

    val sos = SosController(
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
    )

    private val crashSensor = CrashSensor(app, speedKmh = {
        val fix = tracking.location.state.value
        if (fix.hasSpeed && fix.hasFreshFix(System.currentTimeMillis(), SystemClock.elapsedRealtime())) fix.speedKmh else null
    }, config = { VehicleProfile.MOTORCYCLE.config }) { sos.trigger(SosReason.AUTO) }

    /** The accelerometer runs while the dashboard is on screen or an explicitly started ride is active. */
    val armed: StateFlow<Boolean> get() = armedState
    private val armedState = MutableStateFlow(false)

    init {
        AndroidSosAlarm(app).restoreVolumeIfPending()
        val riding = tracking.session.state.map { it.phase == RidePhase.ACTIVE }
        scope.launch {
            combine(crashEnabled, screens, riding) { on, screens, riding -> on && (screens > 0 || riding) }
                .distinctUntilChanged().collect { armed ->
                    armedState.value = armed
                    if (armed) crashSensor.start() else crashSensor.stop()
                }
        }
        scope.launch {
            sos.ui.map { it.phase }.distinctUntilChanged().collect { phase ->
                when {
                    phase == SosPhase.IDLE -> cancelAlert()
                    // Screen off or another app in front: bring the countdown up so "I am fine" can be pressed.
                    phase == SosPhase.COUNTDOWN && screens.value == 0 -> showAlert(sos.ui.value.reason)
                }
            }
        }
    }

    fun setScreenVisible(visible: Boolean) {
        screens.value = (screens.value + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible) cancelAlert()
    }

    fun setCrashEnabled(enabled: Boolean) {
        Prefs.setSosCrash(app, enabled)
        crashEnabled.value = enabled
    }

    fun setButtonEnabled(enabled: Boolean) {
        Prefs.setSosButton(app, enabled)
        buttonEnabled.value = enabled
    }

    fun addContact(contact: SosContact) = updateContacts(SosMath.addContact(contacts.value, contact))

    fun removeContact(contact: SosContact) = updateContacts(contacts.value - contact)

    private fun updateContacts(list: List<SosContact>) {
        contacts.value = list
        Prefs.setSosContacts(app, SosMath.toJson(list))
        // Without a contact there is nobody to alert, so the triggers switch themselves off.
        if (list.isEmpty()) {
            setCrashEnabled(false)
            setButtonEnabled(false)
        }
    }

    private fun showAlert(reason: SosReason) {
        val manager = NotificationManagerCompat.from(app)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, app.getString(R.string.sos_title), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            app, 40, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = app.getString(if (reason == SosReason.AUTO) R.string.sos_count_auto else R.string.sos_count_manual)
        val text = app.getString(if (reason == SosReason.AUTO) R.string.sos_count_hint_auto else R.string.sos_count_hint_manual)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ridedeck)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { /* permission withdrawn meanwhile */ }
    }

    private fun cancelAlert() {
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
    }

    private companion object {
        const val CHANNEL = "sos"
        const val NOTIFICATION_ID = 40
    }
}

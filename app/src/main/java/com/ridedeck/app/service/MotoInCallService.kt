package com.ridedeck.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.os.OutcomeReceiver
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import com.ridedeck.app.data.CallNotificationPolicy
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import com.ridedeck.app.data.CallerNamePolicy
import com.ridedeck.app.data.TelecomCallPolicy
import com.ridedeck.app.data.CallAudioRoutePolicy
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** What the dashboard knows about calls while RideDeck is the default phone app; null state means "not managing". */
object InCallHub {
    val state = MutableStateFlow<CallState?>(null)
    @Volatile var service: MotoInCallService? = null
    @Volatile var call: Call? = null

    fun phaseOf(callState: Int): CallPhase = TelecomCallPolicy.phase(callState)
}

/**
 * Lets RideDeck carry the whole call itself instead of handing over to the phone's own call screen.
 * The system only binds this once the user has made RideDeck the default phone app.
 */
class MotoInCallService : InCallService() {
    @Volatile private var latestAudio: CallAudioState? = null
    private val modern = if (Build.VERSION.SDK_INT >= 34) ModernAudio() else null
    private val muteState = com.ridedeck.app.data.CallMuteState(Build.VERSION.SDK_INT >= 34)
    private val routeRequests = com.ridedeck.app.data.CallRouteRequests()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * API < 30 has no contact name in Call.Details, so the contacts query runs here, never on the
     * main thread. Results are posted back to the main handler and dropped after teardown.
     */
    private val contactLookup = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ridedeck-contact-lookup").apply { isDaemon = true }
    }
    private val lookupGeneration = AtomicInteger()
    @Volatile private var destroyed = false
    private var lookedUpNumber: String? = null
    private var lookedUpName: String? = null
    private var lookupStarted = false

    @RequiresApi(34)
    private class ModernAudio {
        var endpoints: List<CallEndpoint> = emptyList()
        var current: CallEndpoint? = null
        fun routeOf(endpoint: CallEndpoint?): Int = CallAudioRoutePolicy.fromEndpointType(endpoint?.endpointType ?: -1)
        val routes: Int get() = endpoints.fold(0) { mask, endpoint -> mask or routeOf(endpoint) }
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        InCallHub.service = this
    }

    override fun onDestroy() {
        destroyed = true
        lookupGeneration.incrementAndGet()
        contactLookup.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        lookedUpNumber = null
        lookedUpName = null
        lookupStarted = false
        calls.forEach { runCatching { it.stopDtmfTone(); it.unregisterCallback(callback) } }
        getSystemService(NotificationManager::class.java).cancel(IncomingCallNotification.ID)
        InCallHub.service = null
        InCallHub.call = null
        InCallHub.state.value = null
        CallBridge.callerName.value = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        publish()
        val incoming = call.state == Call.STATE_RINGING
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (incoming) {
            // A full-screen notification is how the system lets a dialer come up over whatever is on screen.
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(IncomingCallNotification.CHANNEL, getString(R.string.call_incoming), NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Android 14 can deny the full-screen intent (user switch / missing role). The ordinary
            // content intent is kept either way, so a tap still reaches the call screen.
            val systemAllows = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else false
            val fullScreenAllowed = CallNotificationPolicy.fullScreenIntentAllowed(Build.VERSION.SDK_INT, systemAllows)
            nm.notify(IncomingCallNotification.ID, IncomingCallNotification.build(
                context = this,
                caller = CallBridge.callerName.value,
                contentIntent = pi,
                fullScreenIntent = if (fullScreenAllowed) pi else null,
            ))
        }
        try { startActivity(open) } catch (_: RuntimeException) { }
    }

    override fun onCallRemoved(call: Call) {
        runCatching { call.stopDtmfTone() }
        call.unregisterCallback(callback)
        getSystemService(NotificationManager::class.java).cancel(IncomingCallNotification.ID)
        publish()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        latestAudio = audioState
        muteState.legacyChanged(audioState.isMuted)
        if (Build.VERSION.SDK_INT < 34) routeRequests.confirmed(audioState.route)
        publish()
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        modern?.current = callEndpoint
        modern?.let { routeRequests.confirmed(it.routeOf(callEndpoint)) }
        publish()
    }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        modern?.endpoints = availableEndpoints.toList()
        publish()
    }

    @RequiresApi(34)
    override fun onMuteStateChanged(isMuted: Boolean) { muteState.modernChanged(isMuted); publish() }

    /** Request an actual Telecom-provided endpoint; UI selection changes only on confirmed callbacks. */
    fun requestRoute(route: Int): Boolean {
        if (!CallAudioRoutePolicy.supports(route, InCallHub.state.value?.routes ?: 0)) return false
        val alreadySelected = if (Build.VERSION.SDK_INT >= 34) modern?.let { audio ->
            audio.current?.let { current -> audio.routeOf(current) == route && audio.endpoints.any { it.identifier == current.identifier } } == true
        } == true else InCallHub.state.value?.route == route
        if (alreadySelected) { routeRequests.confirmed(route); publish(); return true }
        val generation = routeRequests.begin(route) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val audio = modern ?: return false
                val endpoint = audio.endpoints.firstOrNull { audio.routeOf(it) == route } ?: run { routeRequests.failure(generation); publish(); return false }
                requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) {}
                    override fun onError(error: CallEndpointException) {
                        if (InCallHub.service === this@MotoInCallService) { routeRequests.failure(generation); publish() }
                    }
                })
            } else {
                @Suppress("DEPRECATION") setAudioRoute(route)
            }
            handler.postDelayed({
                if (InCallHub.service === this) {
                    routeRequests.failure(generation)
                    publish()
                }
            }, 5_000L)
            true
        } catch (_: RuntimeException) { routeRequests.failure(generation); publish(); false }
    }


    /** The call the rider cares about: one that is ringing first, otherwise the first live one. */
    private fun publish() {
        val main = calls.filter { TelecomCallPolicy.priority(it.state) != null }.minByOrNull { TelecomCallPolicy.priority(it.state)!! }
        if (main !== InCallHub.call) routeRequests.reset()
        InCallHub.call = main
        // The "incoming call" notification is for ringing only; once answered or ended it must not hang on.
        if (main?.state != Call.STATE_RINGING) getSystemService(NotificationManager::class.java).cancel(IncomingCallNotification.ID)
        if (main == null) {
            lookupGeneration.incrementAndGet()
            lookedUpNumber = null
            lookedUpName = null
            lookupStarted = false
            InCallHub.state.value = CallState(CallPhase.IDLE, managed = true)
            CallBridge.callerName.value = null
            return
        }
        val d = main.details
        val number = d.handle?.schemeSpecificPart
        val platformName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.contactDisplayName else null
        if (number != lookedUpNumber) {
            lookedUpNumber = number
            lookedUpName = null
            lookupStarted = false
        }
        // Show the best name known right now; a slower contacts lookup may replace it shortly.
        val knownContactName = platformName?.takeIf { it.isNotBlank() } ?: lookedUpName
        CallBridge.callerName.value = CallerNamePolicy.resolved(knownContactName, d.callerDisplayName, number)
        val targetNumber = number
        if (!lookupStarted && targetNumber != null && CallerNamePolicy.needsContactLookup(
                sdkInt = Build.VERSION.SDK_INT,
                platformContactName = platformName,
                number = targetNumber,
                hasReadContactsPermission = hasPermission(Manifest.permission.READ_CONTACTS),
            )
        ) {
            val carrierName = d.callerDisplayName
            val generation = lookupGeneration.incrementAndGet()
            lookupStarted = true
            contactLookup.execute {
                val name = lookupName(targetNumber)
                handler.post {
                    // A late answer must not overwrite a newer call or arrive after teardown.
                    if (!destroyed && InCallHub.service === this &&
                        lookupGeneration.get() == generation &&
                        lookedUpNumber == targetNumber && InCallHub.call === main
                    ) {
                        lookedUpName = name
                        CallBridge.callerName.value = CallerNamePolicy.resolved(name, carrierName, targetNumber)
                    }
                }
            }
        }
        @Suppress("DEPRECATION") val audio = latestAudio ?: callAudioState
        val routes = if (Build.VERSION.SDK_INT >= 34) modern?.routes ?: 0 else audio?.supportedRouteMask ?: 0
        routeRequests.availability(routes)
        InCallHub.state.value = CallState(
            phase = InCallHub.phaseOf(main.state),
            startedAtMs = d.connectTimeMillis,
            muted = muteState.muted,
            route = if (Build.VERSION.SDK_INT >= 34) modern?.routeOf(modern.current) ?: 0 else audio?.route ?: 0,
            routes = routes,
            managed = true,
            routeError = routeRequests.failed,
        )
    }

    private fun lookupName(number: String?): String? {
        if (number.isNullOrBlank() || !hasPermission(Manifest.permission.READ_CONTACTS)) return null
        return try {
            contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: RuntimeException) {
            null
        }
    }
}

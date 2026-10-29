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
import androidx.core.app.NotificationCompat
import com.ridedeck.app.MainActivity
import com.ridedeck.app.R
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import com.ridedeck.app.data.TelecomCallPolicy
import com.ridedeck.app.data.CallAudioRoutePolicy
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow

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
        InCallHub.service = this
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        calls.forEach { runCatching { it.stopDtmfTone(); it.unregisterCallback(callback) } }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.call_incoming), NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(getString(R.string.call_incoming))
                .setContentText(CallBridge.callerName.value ?: getString(R.string.phone_unknown))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setFullScreenIntent(pi, true)
                .build())
        }
        try { startActivity(open) } catch (_: RuntimeException) { }
    }

    override fun onCallRemoved(call: Call) {
        runCatching { call.stopDtmfTone() }
        call.unregisterCallback(callback)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
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
        if (main?.state != Call.STATE_RINGING) getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (main == null) {
            InCallHub.state.value = CallState(CallPhase.IDLE, managed = true)
            CallBridge.callerName.value = null
            return
        }
        val d = main.details
        val number = d.handle?.schemeSpecificPart
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.contactDisplayName else lookupName(number)
        // Last resort before the bare number: the name the carrier sends with the call, when it sends one.
        CallBridge.callerName.value = listOf(saved, d.callerDisplayName, number).firstOrNull { !it.isNullOrBlank() }
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

    private companion object {
        const val CHANNEL = "calls"
        const val NOTIFICATION_ID = 42
    }
}

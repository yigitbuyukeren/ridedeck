package com.ridedeck.app.data

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import com.ridedeck.app.R
import com.ridedeck.app.service.RideTrackingService
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.hasPermission
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reference-counted demand for the one process GPS stream.
 *
 * The screen, a started ride service and the overlay strip all need fixes, sometimes at once. The
 * first owner starts the listener, the last release stops it, and a release in any order cannot
 * stop it for the others. [retry] re-runs start for an unchanged owner set: a permission dialog or
 * a temporary provider failure must not leave the stream off forever.
 */
internal class LocationArbiter(private val start: () -> Unit, private val stop: () -> Unit) {
    private val owners = mutableSetOf<Any>()
    val held: Boolean get() = owners.isNotEmpty()

    fun acquire(owner: Any) = set(owner, true)
    fun release(owner: Any) = set(owner, false)

    fun set(owner: Any, needed: Boolean) {
        val changed = if (needed) owners.add(owner) else owners.remove(owner)
        if (!changed) return
        // Start only on the 0 -> 1 edge; a second owner must not subscribe the listener twice.
        if (needed && owners.size == 1) start()
        else if (!needed && owners.isEmpty()) stop()
    }

    fun retry() {
        if (owners.isNotEmpty()) start()
    }
}

/** Process-owned GPS/filter/recorder. A screen, a ride service and the overlay share one GPS stream. */
class RideTracking(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val location = LocationRepository(context)
    val recordTrips = MutableStateFlow(Prefs.recordTrips(context))
    val places = MutableStateFlow(SavedPlaces.fromJson(Prefs.savedPlaces(context)))
    private val recordingEnabled = MutableStateFlow(false)
    private val screens = mutableSetOf<Any>()
    private val gps = LocationArbiter(location::start, location::stop)
    private var serviceTracking = false
    private var automaticAllowed = true
    var navigating = false
    var closeService: (() -> Unit)? = null
    val trips = TripRecorder(TripStore(context), scope, location.state, recordingEnabled)
    val session = RideSessionController(
        scope, ::prerequisiteFailure,
        launchService = { RideTrackingService.launch(context, it) },
        acquireTracking = { serviceTracking = true; automaticAllowed = true; refresh() },
        releaseTracking = { serviceTracking = false; automaticAllowed = false; refresh() },
        finishRecording = trips::finishCurrent,
        closeService = { closeService?.invoke() },
        nowMs = SystemClock::elapsedRealtime,
    )

    fun prerequisiteFailure(): RideFailure? = when {
        PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED -> RideFailure.PERMISSION
        !runCatching { context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> RideFailure.GPS_DISABLED
        trips.error.value -> RideFailure.STORAGE
        else -> null
    }

    fun setScreenVisible(owner: Any, visible: Boolean) {
        if (visible) {
            if (screens.isEmpty()) automaticAllowed = true
            screens.add(owner)
        } else screens.remove(owner)
        refresh()
    }

    /** The overlay strip holds the shared GPS while it is the only consumer (screen off, no ride). */
    fun acquireLocation(owner: Any) {
        val alreadyHeld = gps.held
        gps.acquire(owner)
        // A service can be re-created while another owner kept the arbiter open; retry an earlier
        // permission/provider failure without subscribing a second listener.
        if (alreadyHeld) gps.retry()
    }

    /** Idempotent per owner, so a stopped service cannot release somebody else's fixes. */
    fun releaseLocation(owner: Any) = gps.release(owner)

    fun requestStart(visible: Boolean): Boolean = session.requestStart(visible && screens.isNotEmpty())

    fun setRecording(enabled: Boolean) {
        recordTrips.value = enabled
        Prefs.setRecordTrips(context, enabled)
        refresh()
    }

    fun refresh() {
        recordingEnabled.value = recordTrips.value && (serviceTracking || (screens.isNotEmpty() && automaticAllowed))
        gps.set(SCREEN_DEMAND, screens.isNotEmpty())
        gps.set(SERVICE_DEMAND, serviceTracking)
        // Unchanged demand still retries start: permission can be granted while the screen stays up.
        gps.retry()
    }

    private companion object {
        val SCREEN_DEMAND = Any()
        val SERVICE_DEMAND = Any()
    }
}

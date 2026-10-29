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

/** Process-owned GPS/filter/recorder. A screen and an explicitly started service share one GPS stream. */
class RideTracking(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val location = LocationRepository(context)
    val recordTrips = MutableStateFlow(Prefs.recordTrips(context))
    val places = MutableStateFlow(SavedPlaces.fromJson(Prefs.savedPlaces(context)))
    private val recordingEnabled = MutableStateFlow(false)
    private val screens = mutableSetOf<Any>()
    private var serviceTracking = false
    private var automaticAllowed = true
    var navigating = false
    var closeService: (() -> Unit)? = null
    val trips = TripRecorder(TripStore(context), scope, location.state, recordingEnabled, onParked = { stop ->
        val pattern = if (android.text.format.DateUtils.isToday(stop.t)) "HH:mm" else "d MMM HH:mm"
        val time = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(stop.t))
        val place = Place(context.getString(R.string.saved_parked), time, GeoPoint(stop.lat, stop.lon))
        places.value = SavedPlaces.put(places.value, SavedPlace(PlaceKind.PARKED, place))
        Prefs.setSavedPlaces(context, SavedPlaces.toJson(places.value))
    })
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

    fun requestStart(visible: Boolean): Boolean = session.requestStart(visible && screens.isNotEmpty())

    fun setRecording(enabled: Boolean) {
        recordTrips.value = enabled
        Prefs.setRecordTrips(context, enabled)
        refresh()
    }

    fun refresh() {
        recordingEnabled.value = recordTrips.value && (serviceTracking || (screens.isNotEmpty() && automaticAllowed))
        if (serviceTracking || screens.isNotEmpty()) location.start() else location.stop()
    }
}

package com.ridedeck.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ridedeck.app.util.SpeedUtil
import com.ridedeck.app.util.hasPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LocationState(
    val speedKmh: Int = 0,
    val fixAtMs: Long = 0L,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** Heading in degrees, or null while standing still / unknown. */
    val bearing: Float? = null,
    /** Horizontal accuracy radius in metres, when the fix has it. */
    val accuracyM: Int? = null,
    /** Raw speed in m/s as the chip reports it (speedKmh is this, rounded, with the standstill cut). */
    val speedMps: Float = 0f,
    val hasSpeed: Boolean = true,
    /** The chip's 1-sigma speed error in m/s (API 26+), when it gives one. */
    val speedAccuracyMps: Float? = null,
    /** When the fix was measured, on the elapsed-realtime clock. */
    val elapsedRealtimeMs: Long = 0L,
    /** How old the fix already was when it reached us, and how long since the previous one (for the hidden GPS debug line). */
    val fixAgeMs: Int? = null,
    val fixIntervalMs: Int? = null,
    /** GPS provider availability from LocationManager callbacks; null means the source has not reported it. */
    val gpsProviderEnabled: Boolean? = null,
    /** Fine-location permission state as last checked by the app; null means not checked yet. */
    val locationPermissionGranted: Boolean? = null,
)

class LocationRepository(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state

    private var active = false

    /** Smoothed, extrapolated speed for the screen; the raw speed in [state] stays untouched. */
    private val speedFilter = SpeedFilter()

    /** The speed to draw right now, in km/h (filtered and projected forward from the last fix); null when it is unknown (no fix for a while). */
    fun displayKmh(): Double? {
        val elapsed = SystemClock.elapsedRealtime()
        return if (_state.value.hasSpeed && _state.value.hasFreshFix(System.currentTimeMillis(), elapsed)) speedFilter.speedKmhOrNull(elapsed) else null
    }

    // An object, not a lambda: before Android 11 the listener's other callbacks are abstract, and a lambda
    // would crash with AbstractMethodError the first time the GPS is switched off or on.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val hasSpeed = location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f
            val kmh = if (hasSpeed) SpeedUtil.msToKmh(location.speed) else 0
            val nowReal = SystemClock.elapsedRealtime()
            val measuredAt = location.elapsedRealtimeNanos / 1_000_000L
            val fixTime = if (measuredAt > 0L) measuredAt else nowReal
            if (!LocationFixPolicy.accepts(fixTime, _state.value.elapsedRealtimeMs, nowReal, location.latitude, location.longitude)) return
            val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.takeIf { it.isFinite() && it >= 0f } else null
            val previous = _state.value
            val speedMps = if (hasSpeed) location.speed else 0f
            // The filter works on the time the chip measured at, so a late delivery does not shift the estimate.
            if (hasSpeed) speedFilter.onFix(fixTime, speedMps, speedAcc) else speedFilter.reset()
            // Opt-in: `adb shell setprop log.tag.RideDeckSpeed DEBUG`. Speeds and timing only, never a position.
            if (Log.isLoggable(SPEED_LOG_TAG, Log.DEBUG)) {
                Log.d(SPEED_LOG_TAG, "raw=%.2f acc=%s age=%s gap=%s shown=%.2f %s".format(
                    java.util.Locale.US, speedMps, speedAcc?.let { "%.2f".format(java.util.Locale.US, it) } ?: "?",
                    if (measuredAt > 0) (nowReal - measuredAt).toString() else "?",
                    if (previous.elapsedRealtimeMs > 0 && measuredAt > 0) (measuredAt - previous.elapsedRealtimeMs).toString() else "?",
                    speedFilter.speedMps(nowReal), speedFilter.debugState()))
            }
            _state.value = LocationState(
                speedKmh = kmh,
                fixAtMs = System.currentTimeMillis() - (nowReal - fixTime),
                lat = location.latitude,
                lon = location.longitude,
                bearing = if (location.hasBearing() && location.bearing.isFinite() && kmh > 0) location.bearing else _state.value.bearing,
                accuracyM = if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0f) Math.round(location.accuracy) else null,
                speedMps = speedMps,
                hasSpeed = hasSpeed,
                speedAccuracyMps = speedAcc,
                elapsedRealtimeMs = fixTime,
                fixAgeMs = if (measuredAt > 0) (nowReal - measuredAt).toInt().coerceAtLeast(0) else null,
                fixIntervalMs = if (previous.elapsedRealtimeMs > 0 && measuredAt > 0) (measuredAt - previous.elapsedRealtimeMs).toInt() else null,
                gpsProviderEnabled = previous.gpsProviderEnabled,
                locationPermissionGranted = previous.locationPermissionGranted,
            )
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) _state.value = _state.value.copy(gpsProviderEnabled = true)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                invalidateFix()
                _state.value = _state.value.copy(gpsProviderEnabled = false)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val providerEnabled = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        val permissionGranted = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        _state.value = _state.value.copy(gpsProviderEnabled = providerEnabled, locationPermissionGranted = permissionGranted)
        if (!permissionGranted) {
            if (active) runCatching { manager.removeUpdates(listener) }
            active = false
            invalidateFix()
            return
        }
        if (providerEnabled == false) invalidateFix()
        // Register even while GPS is disabled, so turning it back on delivers provider callbacks and fixes.
        if (active) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            active = true
        } catch (_: SecurityException) {
            invalidateFix()
            _state.value = _state.value.copy(locationPermissionGranted = false)
        } catch (_: IllegalArgumentException) {
            invalidateFix()
            _state.value = _state.value.copy(gpsProviderEnabled = false)
        }
    }

    fun stop() {
        if (active) runCatching { manager.removeUpdates(listener) }
        active = false
        invalidateFix()
    }

    private fun invalidateFix() {
        speedFilter.reset()
        _state.value = _state.value.copy(fixAtMs = 0L, elapsedRealtimeMs = 0L, speedKmh = 0, speedMps = 0f, hasSpeed = false)
    }

    private companion object {
        const val SPEED_LOG_TAG = "RideDeckSpeed"
    }
}

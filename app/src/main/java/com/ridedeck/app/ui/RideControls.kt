package com.ridedeck.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ridedeck.app.R
import com.ridedeck.app.data.RideFailure
import com.ridedeck.app.data.RideTracking
import com.ridedeck.app.util.hasPermission

/** Permissions belong to the visible UI; notification denial does not prohibit a location FGS. */
@Composable
internal fun rememberRideStart(tracking: RideTracking, onStarted: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val afterStart = rememberUpdatedState(onStarted)
    val start = {
        if (tracking.requestStart(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) afterStart.value()
        Unit
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val notifyThenStart = {
        if (Build.VERSION.SDK_INT >= 33 && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else start()
    }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) notifyThenStart() else start()
    }
    return {
        if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // On Android 12+ fine and coarse must be requested together.
            location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else notifyThenStart()
    }
}

internal fun RideFailure.textId(): Int = when (this) {
    RideFailure.NOT_VISIBLE -> R.string.ride_not_visible
    RideFailure.PERMISSION -> R.string.ride_permission_error
    RideFailure.GPS_DISABLED -> R.string.ride_gps_error
    RideFailure.START_FAILED -> R.string.ride_start_error
    RideFailure.STORAGE -> R.string.trip_storage_error
    RideFailure.GPS_LOST -> R.string.ride_gps_lost
    RideFailure.INTERRUPTED -> R.string.ride_interrupted
}

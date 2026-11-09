package com.ridedeck.app

import android.app.Application

class RideDeckApplication : Application() {
    val rideTracking by lazy { com.ridedeck.app.data.RideTracking(this) }
    val rideNavigation by lazy { com.ridedeck.app.data.RideNavigation(this, rideTracking) }
    /** Crash detection and SOS live here, not in a screen, so a started ride keeps them running with the screen off. */
    val rideSafety by lazy { com.ridedeck.app.data.RideSafety(this, rideTracking, rideNavigation) }

    override fun onCreate() {
        super.onCreate()
        // An SOS countdown that died with the process left the alarm volume raised; put it back now.
        rideSafety
    }
}

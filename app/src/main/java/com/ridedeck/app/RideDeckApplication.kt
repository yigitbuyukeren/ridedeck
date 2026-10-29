package com.ridedeck.app

import android.app.Application
import android.util.Log
import com.ridedeck.app.util.LegacyVoskDataMigration
import com.ridedeck.app.util.Prefs

class RideDeckApplication : Application() {
    val rideTracking by lazy { com.ridedeck.app.data.RideTracking(this) }
    val rideNavigation by lazy { com.ridedeck.app.data.RideNavigation(this, rideTracking) }
    override fun onCreate() {
        super.onCreate()
        Prefs.removeVoiceAssistantData(this)
        if (!LegacyVoskDataMigration.removeArtifacts(filesDir, cacheDir)) {
            Log.w("RideDeck", "Could not remove all retired speech model files; will retry next launch")
        }
    }
}

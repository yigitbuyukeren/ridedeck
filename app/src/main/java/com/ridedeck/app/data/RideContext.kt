package com.ridedeck.app.data

/** Current trip facts gathered from already maintained RideDeck sources; nullable means unavailable/stale. */
data class RideContext(
    val navigating: Boolean,
    val remainingMeters: Int?,
    val remainingSeconds: Int?,
    val arrivalAtMs: Long?,
    val speedKmh: Int?,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val bluetoothMicAvailable: Boolean?,
    val mediaTitle: String?,
    val mediaArtist: String?,
    val mediaPlaying: Boolean?,
    val weather: WeatherSnapshot?,
    val vehicleProfile: VehicleProfile,
)

/** Pure projection of live app state, deliberately refusing to invent stale GPS, absent sessions, or unknown BT state. */
object RideContextProvider {
    fun capture(
        nowMs: Long,
        nav: NavUi,
        location: LocationState,
        battery: BatteryState,
        bluetoothMicAvailable: Boolean?,
        media: MediaState,
        weather: WeatherSnapshot?,
        vehicleProfile: VehicleProfile,
    ): RideContext {
        val navigating = nav.navigating && nav.route != null
        val freshGps = location.fixAtMs > 0 && nowMs >= location.fixAtMs && nowMs - location.fixAtMs <= GPS_FRESH_MS
        val activeMedia = media.hasSession && media.title.isNotBlank()
        return RideContext(
            navigating = navigating,
            remainingMeters = nav.remainingM.takeIf { navigating },
            remainingSeconds = nav.remainingS.takeIf { navigating },
            arrivalAtMs = nav.remainingS.takeIf { navigating }?.let { nowMs + it * 1000L },
            speedKmh = location.speedKmh.takeIf { freshGps },
            batteryPercent = battery.percent.takeIf { battery.hasReading && it in 0..100 },
            charging = battery.charging.takeIf { battery.hasReading },
            bluetoothMicAvailable = bluetoothMicAvailable,
            mediaTitle = media.title.takeIf { activeMedia },
            mediaArtist = media.artist.takeIf { activeMedia && it.isNotBlank() },
            mediaPlaying = media.playing.takeIf { activeMedia },
            weather = weather,
            vehicleProfile = vehicleProfile,
        )
    }

    private const val GPS_FRESH_MS = 5_000L
}

package com.ridedeck.app

import com.ridedeck.app.data.BatteryState
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.LocationState
import com.ridedeck.app.data.MediaState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RideContextProvider
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideContextProviderTest {
    @Test
    fun capturesLiveSourcesAndDerivesArrivalFromActiveRoute() {
        val now = 20_000L
        val context = RideContextProvider.capture(
            nowMs = now,
            nav = NavUi(navigating = true, route = Route(emptyList(), emptyList(), 1_200.0, 90.0), remainingM = 1_200, remainingS = 90),
            location = LocationState(speedKmh = 48, fixAtMs = now - 1_000),
            battery = BatteryState(percent = 62, charging = true, hasReading = true),
            bluetoothMicAvailable = true,
            media = MediaState(hasSession = true, title = "Yol", artist = "Sanatçı", playing = true),
            weather = WeatherSnapshot(tempC = 14, code = 3, rainInMin = 25),
            vehicleProfile = VehicleProfile.MOTORCYCLE,
        )

        assertTrue(context.navigating)
        assertEquals(1_200, context.remainingMeters)
        assertEquals(now + 90_000, context.arrivalAtMs)
        assertEquals(48, context.speedKmh)
        assertEquals(62, context.batteryPercent)
        assertEquals(true, context.bluetoothMicAvailable)
        assertEquals("Yol", context.mediaTitle)
        assertEquals("Sanatçı", context.mediaArtist)
        assertEquals(25, context.weather?.rainInMin)
    }

    @Test
    fun omitsUnavailableOrStaleReadingsInsteadOfInventingThem() {
        val context = RideContextProvider.capture(
            nowMs = 20_000,
            nav = NavUi(navigating = true, remainingM = 500, remainingS = 40),
            location = LocationState(speedKmh = 90, fixAtMs = 1_000),
            battery = BatteryState(),
            bluetoothMicAvailable = null,
            media = MediaState(title = "Old track"),
            weather = null,
            vehicleProfile = VehicleProfile.CAR,
        )

        assertEquals(false, context.navigating)
        assertNull(context.remainingMeters)
        assertNull(context.arrivalAtMs)
        assertNull(context.speedKmh)
        assertNull(context.batteryPercent)
        assertNull(context.charging)
        assertNull(context.bluetoothMicAvailable)
        assertNull(context.mediaTitle)
        assertNull(context.weather)
    }
}

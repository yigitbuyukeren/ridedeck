package com.ridedeck.app

import com.ridedeck.app.data.CrashDetector
import com.ridedeck.app.data.FuelMath
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.StopKind
import com.ridedeck.app.data.VehicleProfile
import com.ridedeck.app.data.WeatherMath
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {
    @Test
    fun theOnlyProfileRoutesWithValhallaAutoCosting() {
        assertEquals("auto", VehicleProfile.AUTO.config.costing)
        assertEquals(listOf(VehicleProfile.AUTO), VehicleProfile.entries)
    }

    @Test
    fun theGeneralVehicleProfileCarriesItsGaugeAndCrashThresholds() {
        val config = VehicleProfile.AUTO.config
        assertEquals(240f, config.gaugeMaxKmh, 0f)
        assertEquals(5.0f, config.crashImpactG, 0f)
        assertEquals(30, config.crashMovingKmh)
        assertFalse(config.crashNeedsTilt)
    }

    @Test
    fun theDefaultCrashDetectorMatchesTheProfileThresholds() {
        assertEquals(5.0f, CrashDetector.IMPACT_G, 0f)
        assertEquals(30, CrashDetector.RIDING_KMH)
    }

    @Test
    fun theVehicleLooksForFuelOnly() {
        assertEquals(listOf(StopKind.FUEL), VehicleProfile.AUTO.config.stops.map { it.kind })
    }

    // ---- route options ---------------------------------------------------------------------

    @Test
    fun avoidingHighwaysAndTollsUsesTheAutoCosting() {
        val o = RouteOptions(avoidHighways = true, avoidTolls = true).costingOptions()!!
        assertEquals(0, o.getJSONObject("auto").getInt("use_highways"))
        assertEquals(0, o.getJSONObject("auto").getInt("use_tolls"))
    }

    @Test
    fun noPreferenceSendsNoCostingOptions() {
        assertNull(RouteOptions().costingOptions())
    }

    // ---- stops -----------------------------------------------------------------------------

    private val here = GeoPoint(41.0, 29.0)

    @Test
    fun theVehicleQueryIsFuelOnly() {
        val q = FuelMath.nearQuery(here)
        assertTrue(q.contains("\"amenity\"=\"fuel\""))
        assertFalse(q.contains("charging_station"))
        assertFalse(q.contains("bicycle"))
        assertFalse(q.contains("drinking_water"))
    }

    @Test
    fun parsedStopsAreAllFuel() {
        val json = """{"elements":[
            {"type":"node","lat":41.001,"lon":29.0,"tags":{"amenity":"fuel","name":"Opet"}},
            {"type":"node","lat":41.002,"lon":29.0,"tags":{"amenity":"fuel","brand":"Shell"}}]}"""
        val stops = FuelMath.parse(json, here)
        assertEquals(listOf("Opet", "Shell"), stops.map { it.place.name })
        assertTrue(stops.all { it.kind == StopKind.FUEL })
    }

    @Test
    fun nominatimKeepsOnlyFuel() {
        val json = """[{"lat":"41.01","lon":"29.0","name":"Şarj","type":"charging_station"},
            {"lat":"41.02","lon":"29.0","name":"Hotel","type":"hotel"},
            {"lat":"41.03","lon":"29.0","name":"Opet","type":"fuel"}]"""
        val stops = FuelMath.parseNominatim(json, here)
        assertEquals(listOf("Opet"), stops.map { it.place.name })
        assertEquals(listOf(StopKind.FUEL), stops.map { it.kind })
    }

    // ---- crash thresholds --------------------------------------------------------------------

    /** Drives at [kmh] for two seconds, takes a hit of [g], then lies still. True if an alarm came. */
    private fun crashAlarm(detector: CrashDetector, kmh: Int, g: Float): Boolean {
        var t = 0L
        repeat(20) { detector.onSample(t, 1f, kmh); t += 100 }
        detector.onSample(t, g, kmh)
        t += 100
        repeat(130) {
            if (detector.onSample(t, 1f, 0)) return true
            t += 100
        }
        return false
    }

    @Test
    fun aLowSpeedFallAt15KmhDoesNotRaiseTheAlarm() {
        val vehicle = VehicleProfile.AUTO.config
        assertFalse(crashAlarm(CrashDetector({ vehicle.crashImpactG }, { vehicle.crashMovingKmh }), 15, 5.4f))
    }

    @Test
    fun theDefaultDetectorKeepsTheVehicleThresholds() {
        // 5.0 g at the 30 km/h moving threshold: the profile defaults arm the alarm.
        assertTrue(crashAlarm(CrashDetector(), 60, 5.1f))
        assertFalse(crashAlarm(CrashDetector(), 60, 4.9f))
        // Below the moving threshold a hard hit never arms, however strong it is.
        assertFalse(crashAlarm(CrashDetector(), 29, 8f))
    }

    // ---- wind ------------------------------------------------------------------------------

    @Test
    fun theForecastCarriesTheGustSpeed() {
        val offset = ZoneOffset.ofTotalSeconds(0)
        val now = LocalDateTime.parse("2026-10-18T11:50").toEpochSecond(offset) * 1000
        val json = """{"utc_offset_seconds":0,"current":{"temperature_2m":12.0,"weather_code":3,"wind_gusts_10m":41.6}}"""
        assertEquals(42, WeatherMath.parse(json, now)!!.windKmh)
        assertEquals(0, WeatherMath.parse("""{"current":{"temperature_2m":12.0}}""", now)!!.windKmh)
    }
}

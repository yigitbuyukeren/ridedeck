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
    fun theOnlyProfileRoutesWithValhallaMotorcycleCosting() {
        assertEquals("motorcycle", VehicleProfile.MOTORCYCLE.config.costing)
        assertEquals(listOf(VehicleProfile.MOTORCYCLE), VehicleProfile.entries)
    }

    @Test
    fun theLegacyVehicleChoiceAlwaysComesBackAsMotorcycle() {
        assertEquals(VehicleProfile.MOTORCYCLE, VehicleProfile.fromName("CAR"))
        assertEquals(VehicleProfile.MOTORCYCLE, VehicleProfile.fromName("BICYCLE"))
        assertEquals(VehicleProfile.MOTORCYCLE, VehicleProfile.fromName(null))
        assertEquals(VehicleProfile.MOTORCYCLE, VehicleProfile.fromName("TRUCK"))
    }

    @Test
    fun theMotorcycleLooksForFuelOnly() {
        assertEquals(listOf(StopKind.FUEL), VehicleProfile.MOTORCYCLE.config.stops.map { it.kind })
    }

    // ---- route options ---------------------------------------------------------------------

    @Test
    fun avoidingHighwaysAndTollsUsesTheMotorcycleCosting() {
        val o = RouteOptions(avoidHighways = true, avoidTolls = true).costingOptions()!!
        assertEquals(0, o.getJSONObject("motorcycle").getInt("use_highways"))
        assertEquals(0, o.getJSONObject("motorcycle").getInt("use_tolls"))
    }

    @Test
    fun noPreferenceSendsNoCostingOptions() {
        assertNull(RouteOptions().costingOptions())
    }

    // ---- stops -----------------------------------------------------------------------------

    private val here = GeoPoint(41.0, 29.0)

    @Test
    fun theMotorcycleQueryIsFuelOnly() {
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

    /** Rides at [kmh] for two seconds, takes a hit of [g], then lies still. True if an alarm came. */
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
        val moto = VehicleProfile.MOTORCYCLE.config
        assertFalse(crashAlarm(CrashDetector({ moto.crashImpactG }, { moto.crashMovingKmh }), 15, 3.2f))
    }

    @Test
    fun theDefaultDetectorKeepsTheMotorcycleThresholds() {
        assertTrue(crashAlarm(CrashDetector(), 60, 4.5f))
        assertFalse(crashAlarm(CrashDetector(), 60, 3.5f))
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

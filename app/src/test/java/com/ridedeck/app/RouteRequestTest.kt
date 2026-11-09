package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.RouteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRequestTest {
    private val repo = RouteRepository()
    private val points = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.01, 29.0), GeoPoint(41.02, 29.0))

    @Test
    fun theStartCarriesTheHeadingAndToleranceOnlyWhenKnown() {
        val body = repo.requestBody(points, RouteOptions(), useMiles = false, alternates = 0, headingDeg = 372.4)
        val locations = body.getJSONArray("locations")
        assertEquals(12, locations.getJSONObject(0).getInt("heading"))
        assertEquals(RouteRepository.HEADING_TOLERANCE_DEG, locations.getJSONObject(0).getInt("heading_tolerance"))
        assertFalse(locations.getJSONObject(1).has("heading"))
        assertFalse(locations.getJSONObject(2).has("heading"))
        val plain = repo.requestBody(points, RouteOptions(), useMiles = false, alternates = 0, headingDeg = null)
        assertFalse(plain.getJSONArray("locations").getJSONObject(0).has("heading"))
        assertTrue(plain.getString("costing") == "motorcycle")
    }
}

package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.RouteRepository
import com.ridedeck.app.data.routeRequestBodyBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Adapter body parity only: no HTTP, no provider and no copied route JSON. */
class RouteQuoteRoutingTest {
    @Test fun adapterBuilderReusesTheCentralRouteRepositoryBodyByteForByte() {
        val repo = RouteRepository(miles = { false })
        val points = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.01, 29.01))
        val options = RouteOptions(avoidHighways = true)

        val expected = repo.requestBody(points, options, useMiles = false, alternates = 0, headingDeg = 90.0)
        val actual = routeRequestBodyBuilder(repo)
            .build(points, options, useMiles = false, alternates = 0, headingDeg = 90.0)

        assertEquals(expected.toString(), actual.toString())
    }
}

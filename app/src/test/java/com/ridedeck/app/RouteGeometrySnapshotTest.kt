package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteGeometrySnapshot
import com.ridedeck.app.data.RouteOptions
import com.ridedeck.app.data.TripParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometrySnapshotTest {
    private val shape = listOf(
        GeoPoint(41.0, 29.0),
        GeoPoint(41.001, 29.0),
        GeoPoint(41.002, 29.0),
    )
    private val maneuvers = listOf(
        Maneuver(1, "Go", "", "Head north", 100.0, 0),
        Maneuver(4, "Arrive", "", "Arrived", 0.0, 2),
    )
    private val stopManeuver = Maneuver(4, "Stop", "", "Stop", 0.0, 1, isStop = true)
    private val route = Route(shape, maneuvers, 200.0, 30.0)
    private val dest = Place("D", "", shape.last())

    private fun snapshot(
        stops: List<Place> = emptyList(),
        options: RouteOptions = RouteOptions(),
        savedAtMs: Long = 1_000,
        route: Route = this.route,
    ) = RouteGeometrySnapshot(dest, null, stops, options, savedAtMs, route)

    @Test
    fun boundedGeometryRoundTripsWithRemainingStopsAndOptions() {
        val stop = Place("S", "", shape[1])
        val trip = snapshot(
            stops = listOf(stop),
            options = RouteOptions(avoidHighways = true, avoidTolls = true),
            route = route.copy(
                maneuvers = listOf(maneuvers[0], stopManeuver, maneuvers[1]),
                stopIndices = listOf(1),
            ),
        )

        val restored = RouteGeometrySnapshot.decode(trip.toJson(), 1_000)

        assertEquals(trip, restored)
        assertEquals(listOf(stop), restored!!.remainingStops)
        assertEquals(RouteOptions(avoidHighways = true, avoidTolls = true), restored.options)
    }

    @Test
    fun fuelLegMayCarryPostFuelStopsWithoutLinkingThemToThisGeometry() {
        val stop = Place("S", "", shape[1])
        val fuelLeg = snapshot(stops = listOf(stop), route = route).copy(finalDestination = dest)
        assertEquals(fuelLeg, RouteGeometrySnapshot.decode(fuelLeg.toJson(), 1_000))

        // The current leg is the pump leg; stop indices/maneuvers cannot describe post-fuel stops.
        val stopRoute = route.copy(maneuvers = listOf(maneuvers[0], stopManeuver, maneuvers[1]), stopIndices = listOf(1))
        val invalid = snapshot(stops = listOf(stop), route = stopRoute).copy(finalDestination = dest)
        assertTrue(runCatching { invalid.toJson() }.isFailure)
    }

    @Test
    fun mismatchedStopsEndpointOrderOrDistanceAreRejected() {
        val stop = Place("S", "", shape[1])

        // A stop list without matching stop geometry/maneuver.
        assertTrue(runCatching { snapshot(stops = listOf(stop)).toJson() }.isFailure)

        // Stop index has no isStop maneuver.
        val noStopManeuver = route.copy(stopIndices = listOf(1))
        assertTrue(runCatching { snapshot(stops = listOf(stop), route = noStopManeuver).toJson() }.isFailure)

        // Stop place is too far from its linked shape point.
        val stopRoute = route.copy(maneuvers = listOf(maneuvers[0], stopManeuver, maneuvers[1]), stopIndices = listOf(1))
        val farStop = Place("Far", "", GeoPoint(42.0, 29.0))
        assertTrue(runCatching { snapshot(stops = listOf(farStop), route = stopRoute).toJson() }.isFailure)

        // Destination is too far from the saved geometry endpoint.
        val far = Place("X", "", GeoPoint(42.0, 29.0))
        assertTrue(runCatching {
            RouteGeometrySnapshot(far, null, emptyList(), RouteOptions(), 1_000, route).toJson()
        }.isFailure)

        // Maneuver indices must start at 0 and be nondecreasing.
        val badOrder = route.copy(maneuvers = listOf(maneuvers[1], maneuvers[0]))
        assertTrue(runCatching { snapshot(route = badOrder).toJson() }.isFailure)

        // Distance must stay near the decoded cumulative length.
        assertTrue(runCatching { snapshot(route = route.copy(distanceM = 5_000.0)).toJson() }.isFailure)
    }

    @Test
    fun stableTripIdentityUsesCoordinatesStopsAndOptionsNotNames() {
        val active = ActiveTrip(dest, null, 1_000, emptyList(), RouteOptions())
        val trip = snapshot()
        assertTrue(trip.matches(active))
        assertTrue(trip.matches(active.copy(destination = dest.copy(name = "Renamed after reverse geocode"))))
        assertTrue(!trip.matches(active.copy(destination = Place("Moved", "", GeoPoint(41.1, 29.0)))))
        assertTrue(!trip.matches(active.copy(options = RouteOptions(avoidTolls = true))))

        val stop = Place("S", "", shape[1])
        val stopTrip = snapshot(
            stops = listOf(stop),
            route = route.copy(maneuvers = listOf(maneuvers[0], stopManeuver, maneuvers[1]), stopIndices = listOf(1)),
        )
        val withStop = ActiveTrip(dest, null, 1_000, listOf(stop), RouteOptions())
        assertTrue(stopTrip.matches(withStop))
        assertTrue(!stopTrip.matches(withStop.copy(stops = listOf(stop.copy(point = GeoPoint(41.0, 29.0))))))
    }

    // Synthetic TripParser-shaped fixture, not a captured live-provider response.
    @Test
    fun valhallaStyleSyntheticFixtureCoversLegBoundaryIndicesOnly() {
        fun enc(points: List<GeoPoint>): String {
            val sb = StringBuilder(); var pl = 0; var pn = 0
            fun v(x: Int) {
                var n = if (x < 0) (x shl 1).inv() else x shl 1
                while (n >= 0x20) { sb.append((((n and 0x1f) or 0x20) + 63).toChar()); n = n shr 5 }
                sb.append((n + 63).toChar())
            }
            for (p in points) {
                val la = Math.round(p.lat * 1e6).toInt()
                val lo = Math.round(p.lon * 1e6).toInt()
                v(la - pl); v(lo - pn); pl = la; pn = lo
            }
            return sb.toString()
        }
        fun man(type: Int, begin: Int) = JSONObject().put("type", type).put("instruction", "i")
            .put("length", 0.1).put("begin_shape_index", begin).put("time", 10)
        val a = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.0005, 29.0), GeoPoint(41.001, 29.0))
        val b = listOf(GeoPoint(41.001, 29.0), GeoPoint(41.0015, 29.0), GeoPoint(41.002, 29.0))
        val trip = JSONObject()
            .put("summary", JSONObject().put("length", 0.22).put("time", 40))
            .put("legs", JSONArray()
                .put(JSONObject().put("shape", enc(a)).put("maneuvers", JSONArray().put(man(1, 0)).put(man(5, 2))))
                .put(JSONObject().put("shape", enc(b)).put("maneuvers", JSONArray().put(man(1, 0)).put(man(4, 2)))))
        val route = TripParser.parse(trip, 1_000.0)
        val stop = Place("S", "", route.shape[2])
        val candidate = RouteGeometrySnapshot(Place("D", "", route.shape.last()), null, listOf(stop), RouteOptions(), 1_000, route)

        assertEquals(candidate, RouteGeometrySnapshot.decode(candidate.toJson(), 1_000))
    }

    @Test
    fun distanceAndEndpointTolerancesAreExplicitApproximations() {
        val longShape = listOf(GeoPoint(41.0, 29.0), GeoPoint(41.02, 29.0))
        val longManeuvers = listOf(
            Maneuver(1, "Go", "", "Go", 1_000.0, 0),
            Maneuver(4, "Arrive", "", "Arrive", 0.0, 1),
        )
        val longRoute = Route(longShape, longManeuvers, 2_600.0, 1_000.0)
        val endpoint = Place("D", "", longShape.last())

        assertTrue(runCatching {
            RouteGeometrySnapshot(endpoint, null, emptyList(), RouteOptions(), 1_000, longRoute).toJson()
        }.isSuccess)
        assertTrue(runCatching {
            RouteGeometrySnapshot(endpoint, null, emptyList(), RouteOptions(), 1_000, longRoute.copy(distanceM = 2_800.0)).toJson()
        }.isFailure)

        val near = Place("D", "", GeoPoint(41.024, 29.0))
        assertTrue(runCatching {
            RouteGeometrySnapshot(near, null, emptyList(), RouteOptions(), 1_000, longRoute).toJson()
        }.isSuccess)
        val far = Place("D", "", GeoPoint(41.026, 29.0))
        assertTrue(runCatching {
            RouteGeometrySnapshot(far, null, emptyList(), RouteOptions(), 1_000, longRoute).toJson()
        }.isFailure)
    }

    @Test
    fun fuelFinalDestinationIsRejectedDirectlyDuringDecode() {
        val stop = Place("S", "", shape[1])
        val stopRoute = route.copy(
            maneuvers = listOf(maneuvers[0], stopManeuver, maneuvers[1]),
            stopIndices = listOf(1),
        )
        val json = org.json.JSONObject(snapshot(stops = listOf(stop), route = stopRoute).toJson())
            .put("final", org.json.JSONObject().put("n", "F").put("d", "").put("lat", 0.0).put("lon", 0.0))
        assertNull(RouteGeometrySnapshot.fromJson(json.toString()))
    }

    @Test
    fun corruptChecksumIsRejected() {
        val corrupt = snapshot().toJson().replace("\"hash\":\"", "\"hash\":\"0")
        assertNull(RouteGeometrySnapshot.fromJson(corrupt))
    }

    @Test
    fun ageIsCheckedAfterDecodeAndFutureOrGarbageFails() {
        val trip = snapshot()
        assertNull(RouteGeometrySnapshot.decode(trip.toJson(), 1_000 + RouteGeometrySnapshot.MAX_AGE_MS + 1))
        assertNull(RouteGeometrySnapshot.decode(trip.toJson(), 999))
        assertNull(RouteGeometrySnapshot.decode("not json", 1_000))
        assertNull(RouteGeometrySnapshot.decode(null, 1_000))
    }

    @Test
    fun invalidBoundsAreRejectedBeforeEncoding() {
        val manyStops = List(RouteGeometrySnapshot.MAX_STOPS + 1) { Place("S$it", "", shape[1]) }
        assertTrue(runCatching { snapshot(stops = manyStops).toJson() }.isFailure)

        val tooManyPoints = List(RouteGeometrySnapshot.MAX_SHAPE_POINTS + 1) { GeoPoint(41.0 + it * 1e-6, 29.0) }
        assertTrue(runCatching { snapshot(route = route.copy(shape = tooManyPoints)).toJson() }.isFailure)
    }
}

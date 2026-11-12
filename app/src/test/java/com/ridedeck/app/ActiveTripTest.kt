package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTripTest {
    private val dest = Place("Ev", "Kadıköy", GeoPoint(40.99, 29.03))
    private val destJson = """{"n":"Ev","d":"Kadıköy","lat":40.99,"lon":29.03}"""

    @Test
    fun roundTripsWithAndWithoutFinalDestination() {
        val a = ActiveTrip(dest, null, 1000)
        assertEquals(a, ActiveTrip.fromJson(a.toJson()))
        val b = ActiveTrip(dest, Place("İş", "", GeoPoint(41.0, 29.0)), 5)
        assertEquals(b, ActiveTrip.fromJson(b.toJson()))
        val c = ActiveTrip(dest, null, 7, listOf(Place("S1", "", GeoPoint(41.01, 29.0)), Place("S2", "d", GeoPoint(41.02, 29.0))))
        assertEquals(c, ActiveTrip.fromJson(c.toJson()))
        // Older saves have no stops field.
        assertEquals(emptyList<Place>(), ActiveTrip.fromJson(ActiveTrip(dest, null, 1).toJson())!!.stops)
    }

    @Test
    fun versionTwoKeepsRemainingStopOrderAndRouteChoices() {
        val trip = ActiveTrip(
            dest,
            Place("İş", "", GeoPoint(41.0, 29.0)),
            7,
            listOf(
                Place("S1", "", GeoPoint(41.01, 29.0)),
                Place("S2", "d", GeoPoint(41.02, 29.0)),
            ),
            RouteOptions(avoidHighways = true, avoidTolls = true),
        )

        val restored = ActiveTrip.fromJson(trip.toJson())
        assertEquals(trip, restored)
        assertEquals(listOf("S1", "S2"), restored!!.stops.map { it.name })
        assertEquals(RouteOptions(avoidHighways = true, avoidTolls = true), restored.options)
    }

    @Test
    fun versionOneRecordsWithoutOptionsKeepTheChoiceUnknown() {
        val v1 = """{"dest":$destJson,"stops":[{"n":"S1","d":"","lat":41.01,"lon":29.0}],"at":12345}"""

        val restored = ActiveTrip.fromJson(v1)
        assertEquals(dest, restored!!.destination)
        assertEquals(listOf("S1"), restored.stops.map { it.name })
        // Null tells restore to keep the current choices; it is not the same as explicit false/false.
        assertNull(restored.options)
    }

    @Test
    fun versionTwoRequiresExplicitStrictOptions() {
        val base = """{"v":2,"dest":$destJson,"at":1000"""

        assertNull(ActiveTrip.fromJson("$base}"))
        assertNull(ActiveTrip.fromJson("""$base,"opts":null}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":"x"}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":[]}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":{}}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":{"h":true}}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":{"h":"true","t":false}}"""))
        assertNull(ActiveTrip.fromJson("""$base,"opts":{"h":1,"t":0}}"""))
    }

    @Test
    fun malformedStopsAndFinalAreRejectedInsteadOfSilentlyDropped() {
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":"x","at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":null,"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":{},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":[1],"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":[{"n":"S1","d":"","lat":41.0}],"at":1}"""))
        assertNull(
            ActiveTrip.fromJson(
                """{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"stops":[
                    {"n":"S1","d":"","lat":41.0,"lon":29.0},
                    {"n":"S2","d":"","lat":41.0,"lon":29.0},
                    {"n":"S3","d":"","lat":41.0,"lon":29.0},
                    {"n":"S4","d":"","lat":41.0,"lon":29.0}
                ],"at":1}"""
            )
        )

        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"final":"x","at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"final":null,"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"final":[],"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":"x","opts":{"h":false,"t":false},"at":1}"""))
    }

    @Test
    fun coordinatesAndNamesAreStrictAndBounded() {
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","d":"","lat":"40.99","lon":29.03},"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","d":"","lat":91.0,"lon":29.03},"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","d":"","lat":40.99,"lon":181.0},"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","d":"","lat":null,"lon":29.03},"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":5,"d":"","lat":40.99,"lon":29.03},"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","d":5,"lat":40.99,"lon":29.03},"opts":{"h":false,"t":false},"at":1}"""))

        val withoutDetail = ActiveTrip.fromJson("""{"v":2,"dest":{"n":"Ev","lat":-90,"lon":180},"opts":{"h":false,"t":false},"at":0}""")
        assertEquals(Place("Ev", "", GeoPoint(-90.0, 180.0)), withoutDetail!!.destination)
    }

    @Test
    fun timestampsAreNonNegativeIntegralAndOverflowSafe() {
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false}}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":null}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":"1000"}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":-1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":1.5}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":1e30}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":9223372036854775808}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":-9223372036854775809}"""))
        assertEquals(0L, ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":0}""")!!.savedAtMs)
        assertEquals(
            Long.MAX_VALUE,
            ActiveTrip.fromJson("""{"v":2,"dest":$destJson,"opts":{"h":false,"t":false},"at":9223372036854775807}""")!!.savedAtMs,
        )
        assertEquals(
            Long.MAX_VALUE,
            ActiveTrip.fromJson(ActiveTrip(dest, null, Long.MAX_VALUE, options = RouteOptions()).toJson())!!.savedAtMs,
        )

        assertFalse(ActiveTrip(dest, null, Long.MAX_VALUE).fresh(1))
        assertFalse(ActiveTrip(dest, null, 1).fresh(Long.MIN_VALUE))
    }

    @Test
    fun versionTypesAreStrict() {
        assertNull(ActiveTrip.fromJson("""{"v":"2","dest":$destJson,"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":null,"dest":$destJson,"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":0,"dest":$destJson,"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":3,"dest":$destJson,"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2.5,"dest":$destJson,"opts":{"h":false,"t":false},"at":1}"""))
        assertNull(ActiveTrip.fromJson("""{"v":2.0,"dest":$destJson,"opts":{"h":false,"t":false},"at":1}"""))
    }

    @Test
    fun malformedOrUnknownRecordsAreRejectedForSafeClearing() {
        assertNull(ActiveTrip.fromJson("nope"))
        assertNull(ActiveTrip.fromJson(null))
        assertNull(ActiveTrip.fromJson("""{"v":3,"dest":$destJson,"at":1}"""))
    }

    @Test
    fun restoreIsAgeBoundedAndRejectsFutureTimestamps() {
        val trip = ActiveTrip(dest, null, 1_000)
        assertEquals(trip, ActiveTrip.restore(trip.toJson(), 1_000 + 60_000))
        assertNull(ActiveTrip.restore(trip.toJson(), 1_000 + ActiveTrip.MAX_AGE_MS + 1))
        assertNull(ActiveTrip.restore(trip.toJson(), 999))
        assertNull(ActiveTrip.restore("nope", 1_000))
        assertNull(ActiveTrip.restore(null, 1_000))
    }

    @Test
    fun garbageIsIgnoredAndOldTripsExpire() {
        assertNull(ActiveTrip.fromJson("nope"))
        assertNull(ActiveTrip.fromJson(null))
        val t = ActiveTrip(dest, null, 1_000)
        assertTrue(t.fresh(1_000 + 60_000))
        assertFalse(t.fresh(1_000 + ActiveTrip.MAX_AGE_MS + 1))
        assertFalse(t.fresh(0))
    }
}

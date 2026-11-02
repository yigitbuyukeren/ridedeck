package com.ridedeck.app

import com.ridedeck.app.data.ActiveTrip
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTripTest {
    private val dest = Place("Ev", "Kadıköy", GeoPoint(40.99, 29.03))

    @Test
    fun roundTripsWithAndWithoutFinalDestination() {
        val a = ActiveTrip(dest, null, 1000)
        assertEquals(a, ActiveTrip.fromJson(a.toJson()))
        val b = ActiveTrip(dest, Place("İş", "", GeoPoint(41.0, 29.0)), 5)
        assertEquals(b, ActiveTrip.fromJson(b.toJson()))
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

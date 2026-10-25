package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.PlaceQuery
import com.ridedeck.app.data.RecentPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceQueryTest {
    private val known = PlaceQuery.WELL_KNOWN

    @Test
    fun normalizeFoldsTurkishLettersAndCase() {
        assertEquals("besiktas", PlaceQuery.normalize("Beşiktaş"))
        assertEquals("istanbul", PlaceQuery.normalize("İSTANBUL"))
        assertEquals("kadikoy iskele", PlaceQuery.normalize("  Kadıköy   İskele "))
        assertEquals("taksim e", PlaceQuery.normalize("Taksim'e"))
    }

    @Test
    fun stripSuffixDropsTheCaseEnding() {
        assertEquals("Taksim", PlaceQuery.stripSuffix("Taksim'e"))
        assertEquals("Kadıköy", PlaceQuery.stripSuffix("Kadıköy’den"))
        assertEquals("Moda", PlaceQuery.stripSuffix("Moda"))
    }

    @Test
    fun missingLetterAndSwappedLettersAreSuggested() {
        assertEquals("Taksim", PlaceQuery.suggest("taksm", known).first().name)
        assertEquals("Taksim", PlaceQuery.suggest("takism", known).first().name)
        assertEquals("Kadıköy", PlaceQuery.suggest("kadikoy", known).first().name)
        assertEquals("Beşiktaş", PlaceQuery.suggest("besiktas", known).first().name)
    }

    @Test
    fun anExactNameOrAnUnrelatedWordIsNotSuggested() {
        assertTrue(PlaceQuery.suggest("Taksim", known).none { it.name == "Taksim" })
        assertTrue(PlaceQuery.suggest("qwzxv", known).isEmpty())
        assertTrue(PlaceQuery.suggest("ab", known).isEmpty())
    }

    @Test
    fun suggestionsAreCappedAndBestFirst() {
        val out = PlaceQuery.suggest("kartl", listOf("Kartal", "Kartepe", "Kastamonu", "Kars"), threshold = 0.5, limit = 2)
        assertEquals(2, out.size)
        assertEquals("Kartal", out.first().name)
        assertTrue(out[0].score >= out[1].score)
    }

    @Test
    fun shortQueriesGetEditVariantsUpToTheCap() {
        val out = PlaceQuery.candidates("taksm", known)
        assertTrue(out.size <= PlaceQuery.MAX_SHORT_CANDIDATES)
        assertEquals("Taksim", out.first())
        assertFalse("taksm" in out)
        assertEquals(out.size, out.toSet().size)
    }

    @Test
    fun longQueriesOnlyGetLetterAndEndingVariants() {
        val out = PlaceQuery.candidates("Kadıköy Rıhtım Caddesi'ne", known)
        assertTrue(out.size <= PlaceQuery.MAX_LONG_CANDIDATES)
        assertTrue("Kadıköy Rıhtım Caddesi" in out)
        // no letter swaps or fuzzy names for a long query
        assertTrue(out.none { it == "Kadıköy" })
    }

    @Test
    fun turkishCharacterVariantIsFoundForPlainLetters() {
        assertTrue("Kadıköy" in PlaceQuery.candidates("kadikoy", known))
    }

    @Test
    fun strongLocalMatchesNeedAVeryCloseName() {
        val evim = Place("Evim", "", GeoPoint(41.0, 29.0))
        val isyeri = Place("Kadıköy İskele", "", GeoPoint(40.99, 29.02))
        assertEquals(listOf(isyeri), PlaceQuery.strongLocal("kadikoy iskele", listOf(evim, isyeri)))
        assertTrue(PlaceQuery.strongLocal("evm", listOf(evim)).isEmpty())
        assertTrue(PlaceQuery.strongLocal("kadikoy", listOf(isyeri)).isEmpty())
    }

    @Test
    fun recentPlacesKeepTheNewestOnceAndAreCapped() {
        var list = emptyList<Place>()
        repeat(15) { list = RecentPlaces.put(list, Place("Yer $it", "", GeoPoint(41.0 + it * 0.01, 29.0))) }
        assertEquals(RecentPlaces.MAX, list.size)
        assertEquals("Yer 14", list.first().name)
        list = RecentPlaces.put(list, Place("yer 12", "", GeoPoint(50.0, 20.0)))
        assertEquals("yer 12", list.first().name)
        assertEquals(1, list.count { PlaceQuery.normalize(it.name) == "yer 12" })
        assertEquals(RecentPlaces.MAX, list.size)
    }

    @Test
    fun recentPlacesSurviveJsonAndTolerateDamage() {
        val list = listOf(Place("A", "x", GeoPoint(1.0, 2.0)), Place("B", "", GeoPoint(3.0, 4.0)))
        assertEquals(list, RecentPlaces.fromJson(RecentPlaces.toJson(list)))
        assertTrue(RecentPlaces.fromJson("{not json").isEmpty())
        assertTrue(RecentPlaces.fromJson(null).isEmpty())
    }
}

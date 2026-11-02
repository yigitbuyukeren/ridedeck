package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.PlaceKind
import com.ridedeck.app.data.SavedPlace
import com.ridedeck.app.data.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesTest {
    private fun saved(kind: PlaceKind, name: String, lat: Double = 41.0, lon: Double = 29.0) =
        SavedPlace(kind, Place(name, "", GeoPoint(lat, lon)))

    @Test
    fun newHomeReplacesTheOldOne() {
        val list = SavedPlaces.put(listOf(saved(PlaceKind.HOME, "A")), saved(PlaceKind.HOME, "B", 40.0))
        assertEquals(listOf("B"), list.map { it.place.name })
    }

    @Test
    fun homeAndWorkComeBeforeFavorites() {
        var list = emptyList<SavedPlace>()
        list = SavedPlaces.put(list, saved(PlaceKind.FAVORITE, "Fav", 40.0))
        list = SavedPlaces.put(list, saved(PlaceKind.WORK, "Work", 40.1))
        list = SavedPlaces.put(list, saved(PlaceKind.HOME, "Home", 40.2))
        assertEquals(listOf("Home", "Work", "Fav"), list.map { it.place.name })
    }

    @Test
    fun sameSpotIsKeptOnceAsFavorite() {
        var list = SavedPlaces.put(emptyList(), saved(PlaceKind.FAVORITE, "Cafe", 41.0, 29.0))
        list = SavedPlaces.put(list, saved(PlaceKind.FAVORITE, "Cafe again", 41.0001, 29.0))
        assertEquals(listOf("Cafe again"), list.map { it.place.name })
    }

    @Test
    fun oldestFavoriteDropsWhenFull() {
        var list = emptyList<SavedPlace>()
        for (i in 0..SavedPlaces.MAX_FAVORITES) list = SavedPlaces.put(list, saved(PlaceKind.FAVORITE, "F$i", 30.0 + i))
        assertEquals(SavedPlaces.MAX_FAVORITES, list.size)
        assertTrue(list.none { it.place.name == "F0" })
        assertEquals("F${SavedPlaces.MAX_FAVORITES}", list.last().place.name)
    }

    @Test
    fun removeTakesOnlyThatEntry() {
        val a = saved(PlaceKind.HOME, "A")
        val b = saved(PlaceKind.WORK, "B", 40.0)
        assertEquals(listOf(b), SavedPlaces.remove(listOf(a, b), a))
    }

    @Test
    fun jsonRoundTrips() {
        val list = listOf(saved(PlaceKind.HOME, "Ev", 41.5, 29.5), saved(PlaceKind.FAVORITE, "Kafe", 40.5, 28.5))
        assertEquals(list, SavedPlaces.fromJson(SavedPlaces.toJson(list)))
    }

    @Test
    fun damagedJsonGivesAnEmptyList() {
        assertTrue(SavedPlaces.fromJson("{not json").isEmpty())
        assertTrue(SavedPlaces.fromJson(null).isEmpty())
    }

    @Test
    fun savedDataFromBeforeTheParkedKindStillLoads() {
        val old = """[{"kind":"HOME","name":"Ev","detail":"","lat":41.0,"lon":29.0},{"kind":"FAVORITE","name":"Kafe","detail":"","lat":40.5,"lon":28.5}]"""
        assertEquals(listOf("Ev", "Kafe"), SavedPlaces.fromJson(old).map { it.place.name })
    }

    @Test
    fun aSavedParkedPlaceFromAnOlderVersionIsDropped() {
        val old = """[{"kind":"PARKED","name":"Park","detail":"17:30","lat":41.0,"lon":29.0},{"kind":"HOME","name":"Ev","detail":"","lat":41.0,"lon":29.0}]"""
        assertEquals(listOf("Ev"), SavedPlaces.fromJson(old).map { it.place.name })
    }
}

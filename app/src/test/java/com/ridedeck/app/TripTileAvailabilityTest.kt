package com.ridedeck.app

import com.ridedeck.app.ui.TripTileAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.tile.TileOperation

class TripTileAvailabilityTest {
    @Test
    fun failedTileMarksTheStickyWarning() {
        val state = TripTileAvailability().onAction(TileOperation.Error, "openmaptiles")
        assertTrue(state.hasError)
    }

    @Test
    fun goodTileThenErrorStillWarns() {
        val state = TripTileAvailability()
            .onAction(TileOperation.LoadFromNetwork, "openmaptiles")
            .onAction(TileOperation.Error, "openmaptiles")
        assertTrue("one loaded tile is not proof of a complete map", state.hasError)
    }

    @Test
    fun aLaterGoodTileDoesNotClearTheWarning() {
        val state = TripTileAvailability()
            .onAction(TileOperation.Error, "openmaptiles")
            .onAction(TileOperation.LoadFromCache, "openmaptiles")
            .onAction(TileOperation.EndParse, "openmaptiles")
        assertTrue(state.hasError)
    }

    @Test
    fun goodTilesAloneDoNotWarn() {
        val state = TripTileAvailability()
            .onAction(TileOperation.LoadFromNetwork, "openmaptiles")
            .onAction(TileOperation.EndParse, "openmaptiles")
        assertFalse(state.hasError)
    }

    @Test
    fun tripGeoJsonSourcesNeverAffectTileAvailability() {
        val initial = TripTileAvailability()
        assertEquals(initial, initial.onAction(TileOperation.Error, "trip-lines"))
        assertEquals(initial, initial.onAction(TileOperation.Error, "trip-start"))
        assertEquals(initial, initial.onAction(TileOperation.Error, "trip-end"))
    }

    @Test
    fun nonTerminalTileOperationsAreIgnored() {
        val initial = TripTileAvailability()
        assertEquals(initial, initial.onAction(TileOperation.RequestedFromNetwork, "openmaptiles"))
        assertEquals(initial, initial.onAction(TileOperation.StartParse, "openmaptiles"))
        assertEquals(initial, initial.onAction(TileOperation.Cancelled, "openmaptiles"))
        assertEquals(initial, initial.onAction(null, "openmaptiles"))
    }
}

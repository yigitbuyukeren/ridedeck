package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineBlock
import com.ridedeck.app.data.OfflineBudget
import com.ridedeck.app.data.OfflinePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlanTest {
    private val istanbul = GeoPoint(41.0, 29.0)

    @Test
    fun biggerRadiusNeedsMoreTiles() {
        val a = OfflinePlan.tileCount(istanbul, 15)
        val b = OfflinePlan.tileCount(istanbul, 30)
        val c = OfflinePlan.tileCount(istanbul, 60)
        assertTrue(a < b && b < c)
        assertTrue("60 km needs $c tiles", c > OfflinePlan.MAX_TILES_PER_DOWNLOAD)
        assertTrue(OfflinePlan.estimateMb(c) > OfflinePlan.estimateMb(a))
    }

    @Test
    fun cornersSpanTwiceTheRadius() {
        val (sw, ne) = OfflinePlan.corners(istanbul, 30)
        assertEquals(60000.0, com.ridedeck.app.data.Geo.distance(sw.copy(lon = istanbul.lon), ne.copy(lon = istanbul.lon)), 1000.0)
        assertTrue(sw.lon < istanbul.lon && ne.lon > istanbul.lon)
    }

    @Test
    fun dayAndNightShareOneDownloadBudget() {
        assertEquals(2, OfflinePlan.STYLES_PER_DOWNLOAD)
        val uniqueTiles = OfflinePlan.tileCount(istanbul, 15)
        val budget = OfflinePlan.budget(istanbul, 15)
        // The pair is one download; counting each style separately would double-charge the shared tiles.
        assertEquals(uniqueTiles, budget.tiles)
        assertEquals(OfflinePlan.estimateBytes(uniqueTiles), budget.bytes)
    }

    @Test
    fun megabyteEstimateIsDecimalAndRoundedUp() {
        val tiles = OfflinePlan.tileCount(istanbul, 15)
        val bytes = OfflinePlan.estimateBytes(tiles)
        // 420 tiles ≈ 23.65 MB; display 24 MB so the estimate never understates the provider budget.
        assertEquals(24, OfflinePlan.estimateMb(tiles))
        assertTrue(OfflinePlan.estimateMb(tiles) * 1_000_000L >= bytes)
    }

    @Test
    fun providerCapsAreConservativeAndInclusive() {
        assertEquals(3_000, OfflinePlan.MAX_TILES_PER_DOWNLOAD)
        assertEquals(50_000_000L, OfflinePlan.MAX_BYTES_PER_DOWNLOAD)
        assertEquals(4, OfflinePlan.MAX_CONCURRENT_REQUESTS)
        assertTrue(OfflineBudget(3_000, 50_000_000L).fits)
        assertFalse(OfflineBudget(3_001, 1L).fitsTiles)
        assertFalse(OfflineBudget(1, 50_000_001L).fitsBytes)
    }

    @Test
    fun tileGuardStopsTheLargestAreaBeforeAnyDownload() {
        val block = OfflinePlan.blockFor(istanbul, 60)
        assertTrue("60 km: $block", block is OfflineBlock.TileLimit)
        val limited = block as OfflineBlock.TileLimit
        assertEquals(OfflinePlan.tileCount(istanbul, 60), limited.tiles)
        assertTrue(limited.tiles > limited.limit)
        assertEquals(3_000, limited.limit)
    }

    @Test
    fun byteGuardStopsAnAreaThatPassesTheTileCap() {
        val budget = OfflinePlan.budget(istanbul, 30)
        assertTrue("30 km tiles: ${budget.tiles}", budget.fitsTiles)
        val block = OfflinePlan.blockFor(istanbul, 30)
        assertTrue("30 km: $block", block is OfflineBlock.ByteLimit)
        val limited = block as OfflineBlock.ByteLimit
        assertEquals(budget.bytes, limited.bytes)
        assertTrue(limited.bytes > limited.limit)
        assertEquals(50_000_000L, limited.limit)
    }

    @Test
    fun fittingAreaStillFailsClosedWhileProviderConcurrencyIsUnverified() {
        assertTrue(OfflinePlan.budget(istanbul, 15).fits)
        assertEquals(listOf(15), OfflinePlan.fittingRadii(istanbul))
        assertEquals(OfflineBlock.ProviderUnverified, OfflinePlan.blockFor(istanbul, 15))
        OfflinePlan.RADII_KM.forEach { km ->
            assertTrue("$km km must not start a download", OfflinePlan.blockFor(istanbul, km) != null)
        }
    }

    @Test
    fun coverageAndPersistence() {
        val area = OfflineArea("x", istanbul, 30, 5L)
        assertTrue(OfflinePlan.covered(listOf(area), GeoPoint(41.1, 29.1)))
        assertFalse(OfflinePlan.covered(listOf(area), GeoPoint(39.9, 32.8)))
        assertEquals(listOf(area), OfflinePlan.fromJson(OfflinePlan.toJson(listOf(area))))
        assertTrue(OfflinePlan.fromJson("broken").isEmpty())
    }
}

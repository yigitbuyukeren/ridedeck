package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.NativeRegion
import com.ridedeck.app.data.NativeRegionStatus
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineRegionIdentity
import com.ridedeck.app.data.OfflineRegions
import com.ridedeck.app.data.OfflineStyle
import com.ridedeck.app.data.NativeRegionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRegionsTest {
    private val area = OfflineArea("istanbul", GeoPoint(41.0, 29.0), 15, 10L)
    private val other = OfflineArea("ankara", GeoPoint(39.9, 32.8), 30, 20L)

    private fun status(
        complete: Boolean = true,
        required: Long = 100,
        completed: Long = if (complete) required else 40,
        precise: Boolean = true,
        active: Boolean = false,
    ) = NativeRegionStatus(complete, required, completed, precise, active)

    private fun region(
        id: Long,
        style: OfflineStyle,
        status: NativeRegionStatus? = status(),
        areaName: String? = area.name,
        error: String? = null,
        identity: OfflineRegionIdentity? = OfflineRegionIdentity(
            area.name, area.center.lat, area.center.lon, area.radiusKm,
        ),
    ) = NativeRegion(id, areaName, style, identity, status, error)

    @Test
    fun metadataAloneNeverMarksNativeComplete() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(1, OfflineStyle.DAY, status = null), region(2, OfflineStyle.NIGHT, status = null)),
        ).single()
        assertEquals(NativeRegionState.UNKNOWN, result.state)
    }

    @Test
    fun nameOnlyMetadataIsNeverNativeComplete() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(
                region(1, OfflineStyle.DAY, identity = null),
                region(2, OfflineStyle.NIGHT, identity = null),
            ),
        ).single()
        assertEquals(NativeRegionState.UNKNOWN, result.state)
        assertEquals("native identity unverified", result.error)
    }

    @Test
    fun sameNameDifferentCenterOrRadiusIsNotTrustedAsNativeComplete() {
        val differentCenter = listOf(
            region(1, OfflineStyle.DAY, identity = OfflineRegionIdentity(area.name, 41.5, 29.5, area.radiusKm)),
            region(2, OfflineStyle.NIGHT, identity = OfflineRegionIdentity(area.name, 41.5, 29.5, area.radiusKm)),
        )
        val differentRadius = listOf(
            region(1, OfflineStyle.DAY, identity = OfflineRegionIdentity(area.name, area.center.lat, area.center.lon, 60)),
            region(2, OfflineStyle.NIGHT, identity = OfflineRegionIdentity(area.name, area.center.lat, area.center.lon, 60)),
        )
        assertEquals(NativeRegionState.UNKNOWN, OfflineRegions.reconcile(listOf(area), differentCenter).single().state)
        assertEquals(NativeRegionState.UNKNOWN, OfflineRegions.reconcile(listOf(area), differentRadius).single().state)
    }

    @Test
    fun bothPreciseCompleteNativeStatusesAreNativeComplete() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(1, OfflineStyle.DAY), region(2, OfflineStyle.NIGHT)),
        ).single()
        assertEquals(NativeRegionState.NATIVE_COMPLETE, result.state)
        assertNull(result.error)
    }

    @Test
    fun completeButNotSoundNativeEvidenceIsNotNativeComplete() {
        val noRequired = listOf(
            region(1, OfflineStyle.DAY, status(required = 0, completed = 0)),
            region(2, OfflineStyle.NIGHT, status(required = 0, completed = 0)),
        )
        val imprecise = listOf(
            region(1, OfflineStyle.DAY, status(precise = false)),
            region(2, OfflineStyle.NIGHT, status(precise = false)),
        )
        assertEquals(NativeRegionState.UNKNOWN, OfflineRegions.reconcile(listOf(area), noRequired).single().state)
        assertEquals(NativeRegionState.UNKNOWN, OfflineRegions.reconcile(listOf(area), imprecise).single().state)
    }

    @Test
    fun missingOneStyleIsPartialNotNativeComplete() {
        val result = OfflineRegions.reconcile(listOf(area), listOf(region(1, OfflineStyle.DAY))).single()
        assertEquals(NativeRegionState.PARTIAL, result.state)
        assertEquals(region(1, OfflineStyle.DAY).id, result.day?.id)
        assertNull(result.night)
    }

    @Test
    fun incompleteStylesArePausedOrPartial() {
        val bothPaused = listOf(
            region(1, OfflineStyle.DAY, status(complete = false)),
            region(2, OfflineStyle.NIGHT, status(complete = false)),
        )
        assertEquals(NativeRegionState.PAUSED, OfflineRegions.reconcile(listOf(area), bothPaused).single().state)

        val oneStillMissing = listOf(
            region(1, OfflineStyle.DAY),
            region(2, OfflineStyle.NIGHT, status(complete = false)),
        )
        assertEquals(NativeRegionState.PARTIAL, OfflineRegions.reconcile(listOf(area), oneStillMissing).single().state)
    }

    @Test
    fun activeDownloadWinsEvenWhenOneStyleLooksComplete() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(1, OfflineStyle.DAY), region(2, OfflineStyle.NIGHT, status(active = true))),
        ).single()
        assertEquals(NativeRegionState.DOWNLOADING, result.state)
    }

    @Test
    fun nativeStatusErrorKeepsTheUserArea() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(1, OfflineStyle.DAY, status = null, error = "disk"), region(2, OfflineStyle.NIGHT)),
        ).single()
        assertEquals(NativeRegionState.ERROR, result.state)
        assertEquals("disk", result.error)
        assertEquals(area, result.area)
    }

    @Test
    fun listingErrorPreservesEveryAreaAsError() {
        val result = OfflineRegions.reconcile(listOf(area, other), emptyList(), listingError = "list failed")
        assertEquals(2, result.size)
        assertEquals(listOf(area, other), result.map { it.area })
        assertTrue(result.all { it.state == NativeRegionState.ERROR && it.error == "list failed" })
    }

    @Test
    fun duplicateNativeRegionsFailClosed() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(1, OfflineStyle.DAY), region(2, OfflineStyle.DAY), region(3, OfflineStyle.NIGHT)),
        ).single()
        assertEquals(NativeRegionState.UNKNOWN, result.state)
        assertEquals("duplicate native region", result.error)
    }

    @Test
    fun missingNativeRegionIsMissingAndOrphansDoNotCreateAreas() {
        val result = OfflineRegions.reconcile(
            listOf(area),
            listOf(region(9, OfflineStyle.DAY, areaName = "orphan"), region(10, OfflineStyle.NIGHT, areaName = null)),
        ).single()
        assertEquals(NativeRegionState.MISSING, result.state)
        assertEquals(listOf(area), OfflineRegions.reconcile(listOf(area), emptyList()).map { it.area })
    }

    @Test
    fun reconciliationPreservesAreaOrderAndIdentity() {
        val ready = listOf(region(1, OfflineStyle.DAY), region(2, OfflineStyle.NIGHT))
        val result = OfflineRegions.reconcile(listOf(other, area), ready)
        assertEquals(listOf(other, area), result.map { it.area })
        assertEquals(NativeRegionState.MISSING, result[0].state)
        assertEquals(NativeRegionState.NATIVE_COMPLETE, result[1].state)
    }
}

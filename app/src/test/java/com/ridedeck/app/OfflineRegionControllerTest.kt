package com.ridedeck.app

import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.NativeDownloadState
import com.ridedeck.app.data.NativeOfflineGateway
import com.ridedeck.app.data.NativeOfflineRegion
import com.ridedeck.app.data.NativeRegionStatus
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineBlock
import com.ridedeck.app.data.OfflineRegionController
import com.ridedeck.app.data.OfflineRegionIdentity
import com.ridedeck.app.data.NativeRegionState
import java.util.ArrayDeque
import java.util.LinkedHashMap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRegionControllerTest {
    private val area = OfflineArea("istanbul", GeoPoint(41.0, 29.0), 15, 10L)

    private fun meta(
        name: String,
        day: Boolean,
        lat: Double = area.center.lat,
        lon: Double = area.center.lon,
        radiusKm: Int = area.radiusKm,
        canonical: Boolean = true,
    ): ByteArray = JSONObject()
        .apply { if (canonical) put("v", OfflineRegionIdentity.SCHEMA).put("lat", lat).put("lon", lon).put("r", radiusKm) }
        .put("area", name)
        .put("day", day)
        .toString()
        .toByteArray()

    private fun ref(
        id: Long,
        day: Boolean,
        name: String = area.name,
        lat: Double = area.center.lat,
        lon: Double = area.center.lon,
        radiusKm: Int = area.radiusKm,
        canonical: Boolean = true,
    ) = FakeRegion(id, meta(name, day, lat, lon, radiusKm, canonical))

    private fun status(
        complete: Boolean = true,
        required: Long = 100,
        completed: Long = if (complete) required else 40,
        precise: Boolean = true,
        active: Boolean = false,
    ) = NativeRegionStatus(complete, required, completed, precise, active)

    private fun controller(
        gateway: FakeGateway,
        areas: List<OfflineArea> = listOf(area),
        saved: MutableList<OfflineArea> = mutableListOf(),
        blockFor: (GeoPoint, Int) -> OfflineBlock? = { _, _ -> null },
    ): OfflineRegionController {
        val controller = OfflineRegionController(
            gateway = gateway,
            loadAreas = { areas },
            saveAreas = { saved.clear(); saved.addAll(it) },
            blockFor = blockFor,
        )
        controller.load()
        return controller
    }

    @Test
    fun resumeFailsClosedBeforeAnyActivationOrListRequest() {
        val gateway = FakeGateway()
        val controller = controller(gateway, blockFor = { _, _ -> null })
        val listsBefore = gateway.listCalls.size

        assertEquals(OfflineBlock.ProviderUnverified, controller.resume(area))
        assertEquals(OfflineBlock.ProviderUnverified, controller.actionBlock.value)
        assertTrue("resume must not activate a native region", gateway.states.isEmpty())
        assertEquals(listsBefore, gateway.listCalls.size)
        assertTrue(gateway.deleteCalls.isEmpty())
    }

    @Test
    fun resumeKeepsTheSpecificSizeBlockWhenTheGuardReportsOne() {
        val gateway = FakeGateway()
        val block = OfflineBlock.TileLimit(3_001, 3_000)
        val controller = controller(gateway, blockFor = { _, _ -> block })

        assertEquals(block, controller.resume(area))
        assertEquals(block, controller.actionBlock.value)
        assertTrue(gateway.states.isEmpty())
    }

    @Test
    fun staleRefreshGenerationIsIgnoredBeforeItCanQueryStatuses() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.refresh()

        val stale = gateway.listCalls.removeFirst()
        val current = gateway.listCalls.removeFirst()
        stale(Result.success(listOf(ref(1, true))))
        current(Result.success(emptyList()))

        assertEquals(NativeRegionState.MISSING, controller.regions.value.single().state)
        assertTrue("stale list must not query statuses", gateway.statusCalls.isEmpty())
    }

    @Test
    fun outOfOrderStatusCallbacksPublishOnlyAfterEveryStatusArrives() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        gateway.listSuccess(emptyList())
        controller.refresh()
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))

        assertEquals(2, gateway.statusCalls.size)
        gateway.statusSuccess(2, status())
        assertEquals(NativeRegionState.MISSING, controller.regions.value.single().state)
        gateway.statusSuccess(1, status())

        assertEquals(NativeRegionState.NATIVE_COMPLETE, controller.regions.value.single().state)
    }

    @Test
    fun listAndStatusFailuresPreserveTheUserArea() {
        val saved = mutableListOf<OfflineArea>()
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))

        gateway.statusSuccess(1, status())
        gateway.statusFailure(2, "disk")
        assertEquals(NativeRegionState.ERROR, controller.regions.value.single().state)
        assertEquals("disk", controller.regions.value.single().error)
        assertEquals(listOf(area), controller.areas.value)
        assertTrue(saved.isEmpty())

        controller.refresh()
        gateway.listFailure("list failed")
        assertEquals(NativeRegionState.ERROR, controller.regions.value.single().state)
        assertEquals("list failed", controller.reconcileError.value)
        assertEquals(listOf(area), controller.areas.value)
    }

    @Test
    fun cancelDisposesObserversAndNeverActivates() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        gateway.listSuccess(emptyList())
        controller.cancel(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))

        assertEquals(listOf(1L, 2L), gateway.disposed)
        assertEquals(
            listOf(1L to NativeDownloadState.INACTIVE, 2L to NativeDownloadState.INACTIVE),
            gateway.states,
        )
        assertNull(controller.actionError.value)
    }

    @Test
    fun partialDeleteFailureKeepsMetadataAndRetryRemovesMissingStyle() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.deleteSuccess(1)
        gateway.deleteFailure(2, "disk")

        assertEquals(listOf(area), saved)
        assertEquals("disk", controller.actionError.value)
        assertEquals(listOf(1L, 2L), gateway.disposed)

        // Let the post-failure refresh settle, then retry with only the remaining night region.
        gateway.listSuccess(emptyList())
        controller.delete(area)
        gateway.listSuccess(listOf(ref(2, false)))
        gateway.deleteSuccess(2)

        assertTrue(saved.isEmpty())
        assertNull(controller.actionError.value)
    }

    @Test
    fun refreshDuringDeleteDoesNotAbandonSuccessfulPartialDeletes() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.deleteSuccess(1)

        // A concurrent refresh must not invalidate the in-flight delete callbacks.
        controller.refresh()
        gateway.deleteFailure(2, "disk")

        assertEquals(listOf(area), saved)
        assertEquals("disk", controller.actionError.value)
        assertTrue(controller.areas.value.contains(area))
    }

    @Test
    fun sameNamedDifferentIdentityIsNeverDeleted() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true, lat = 41.5, lon = 29.5), ref(2, false, lat = 41.5, lon = 29.5)))

        assertEquals(listOf(area), saved)
        assertEquals("native identity unverified", controller.actionError.value)
        assertTrue("unrelated native regions must not be deleted", gateway.deleteCalls.isEmpty())
        assertTrue(gateway.states.isEmpty())
    }

    @Test
    fun nameOnlyMetadataIsNotDeletedByName() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true, canonical = false), ref(2, false, canonical = false)))

        assertEquals(listOf(area), saved)
        assertEquals("native identity unverified", controller.actionError.value)
        assertTrue(gateway.deleteCalls.isEmpty())
    }

    @Test
    fun missingNativeDeleteNeedsExplicitStaleRemovalConfirmation() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(emptyList())
        gateway.listSuccess(emptyList()) // settle the refresh triggered by the failed delete
        assertEquals(listOf(area), saved)
        assertEquals("native region missing", controller.actionError.value)

        controller.delete(area, allowStaleRemoval = true)
        gateway.listSuccess(emptyList())
        assertTrue(saved.isEmpty())
        assertNull(controller.actionError.value)
    }

    @Test
    fun failedListNeverErasesMetadata() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area, allowStaleRemoval = true)
        gateway.listFailure("list failed")

        assertEquals(listOf(area), saved)
        assertEquals("list failed", controller.actionError.value)
        assertTrue(gateway.deleteCalls.isEmpty())
    }

    @Test
    fun callbacksFromANonMainThreadAreSynchronized() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        val thread = Thread { gateway.listSuccess(emptyList()) }
        thread.start()
        thread.join(2_000)

        assertEquals(NativeRegionState.MISSING, controller.regions.value.single().state)
    }

    @Test
    fun futureVersionAndMalformedMetadataAreNeverDeleted() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        val future = FakeRegion(1, JSONObject().put("v", 3).put("area", area.name)
            .put("lat", area.center.lat).put("lon", area.center.lon).put("r", area.radiusKm).put("day", true).toString().toByteArray())
        val fractionalRadius = FakeRegion(2, JSONObject().put("v", 2).put("area", area.name)
            .put("lat", area.center.lat).put("lon", area.center.lon).put("r", 15.5).put("day", false).toString().toByteArray())
        val stringDay = FakeRegion(3, JSONObject().put("v", 2).put("area", area.name)
            .put("lat", area.center.lat).put("lon", area.center.lon).put("r", area.radiusKm).put("day", "true").toString().toByteArray())

        controller.delete(area, allowStaleRemoval = true)
        gateway.listSuccess(listOf(future, fractionalRadius, stringDay))

        assertEquals(listOf(area), saved)
        assertEquals("native identity unverified", controller.actionError.value)
        assertTrue(gateway.deleteCalls.isEmpty())
        assertTrue(gateway.statusCalls.isEmpty())
    }

    @Test
    fun deletingOneSameNameAreaKeepsTheOtherRecord() {
        val replacement = OfflineArea(area.name, GeoPoint(42.0, 30.0), 30, 20L)
        val saved = mutableListOf(area, replacement)
        val gateway = FakeGateway()
        val controller = controller(gateway, areas = listOf(area, replacement), saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.deleteSuccess(1)
        gateway.deleteSuccess(2)

        assertEquals(listOf(replacement), saved)
        assertEquals(listOf(replacement), controller.areas.value)
    }

    @Test
    fun saveFailureKeepsTheAreaAndReportsTheError() {
        val gateway = FakeGateway()
        var attempted: List<OfflineArea>? = null
        val controller = OfflineRegionController(
            gateway = gateway,
            loadAreas = { listOf(area) },
            saveAreas = {
                attempted = it
                throw IllegalStateException("disk full")
            },
            blockFor = { _, _ -> null },
        )
        controller.load()
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.deleteSuccess(1)
        gateway.deleteSuccess(2)

        assertEquals(listOf(area), controller.areas.value)
        assertTrue(controller.actionError.value?.contains("disk full") == true)
        assertTrue(attempted != null && attempted!!.isEmpty())
    }

    @Test
    fun duplicateStatusCallbackCannotCompleteTheAggregateTwice() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        gateway.listSuccess(emptyList())
        controller.refresh()
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))

        gateway.statusSuccess(1, status(), times = 2)
        assertEquals(NativeRegionState.MISSING, controller.regions.value.single().state)
        gateway.statusSuccess(2, status())
        assertEquals(NativeRegionState.NATIVE_COMPLETE, controller.regions.value.single().state)
    }

    @Test
    fun statusCallbackFollowedByThrowIsCountedOnlyOnce() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        gateway.listSuccess(emptyList())
        controller.refresh()
        gateway.statusThrowAfter[1] = status()
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.statusSuccess(2, status())

        assertEquals(NativeRegionState.NATIVE_COMPLETE, controller.regions.value.single().state)
    }

    @Test
    fun duplicateDeleteCallbackCannotRemoveMetadataEarly() {
        val saved = mutableListOf(area)
        val gateway = FakeGateway()
        val controller = controller(gateway, saved = saved)
        gateway.listSuccess(emptyList())

        controller.delete(area)
        gateway.listSuccess(listOf(ref(1, true), ref(2, false)))
        gateway.deleteSuccess(1, times = 2)
        assertEquals(listOf(area), saved)
        assertTrue(gateway.deleteCalls.containsKey(2))

        gateway.deleteSuccess(2)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun loadCanBeRetriedAfterPersistenceThrows() {
        val gateway = FakeGateway()
        var attempts = 0
        val controller = OfflineRegionController(
            gateway = gateway,
            loadAreas = {
                attempts += 1
                if (attempts == 1) throw IllegalStateException("prefs read failed")
                listOf(area)
            },
            saveAreas = {},
            blockFor = { _, _ -> null },
        )

        controller.load()
        assertTrue(controller.areas.value.isEmpty())
        assertTrue(controller.reconcileError.value?.contains("prefs read failed") == true)

        controller.load()
        assertEquals(listOf(area), controller.areas.value)
        assertEquals(1, gateway.listCalls.size)
    }

    @Test
    fun duplicateAndOrphanNativeRegionsNeverChangeTheAreaList() {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        gateway.listSuccess(emptyList())
        controller.refresh()
        gateway.listSuccess(
            listOf(
                ref(1, true),
                ref(2, true),
                ref(3, false),
                ref(9, true, name = "orphan"),
            ),
        )
        assertEquals(3, gateway.statusCalls.size)
        assertTrue(!gateway.statusCalls.containsKey(9))
        gateway.statusSuccess(1, status())
        gateway.statusSuccess(2, status())
        gateway.statusSuccess(3, status())

        assertEquals(listOf(area), controller.areas.value)
        assertEquals(NativeRegionState.UNKNOWN, controller.regions.value.single().state)
        assertEquals("duplicate native region", controller.regions.value.single().error)
    }
}

private class FakeRegion(
    override val id: Long,
    override val metadata: ByteArray,
) : NativeOfflineRegion

private class FakeGateway : NativeOfflineGateway {
    val listCalls = ArrayDeque<(Result<List<NativeOfflineRegion>>) -> Unit>()
    val statusCalls = LinkedHashMap<Long, (Result<NativeRegionStatus>) -> Unit>()
    val deleteCalls = LinkedHashMap<Long, (Result<Unit>) -> Unit>()
    val states = mutableListOf<Pair<Long, NativeDownloadState>>()
    val disposed = mutableListOf<Long>()
    val statusThrowAfter = LinkedHashMap<Long, NativeRegionStatus>()

    override fun list(onResult: (Result<List<NativeOfflineRegion>>) -> Unit) {
        listCalls.addLast(onResult)
    }

    override fun status(region: NativeOfflineRegion, onResult: (Result<NativeRegionStatus>) -> Unit) {
        val thrownAfter = statusThrowAfter.remove(region.id)
        if (thrownAfter != null) {
            onResult(Result.success(thrownAfter))
            throw IllegalStateException("status threw")
        }
        statusCalls[region.id] = onResult
    }

    override fun setDownloadState(region: NativeOfflineRegion, state: NativeDownloadState) {
        states.add(region.id to state)
    }

    override fun disposeObserver(region: NativeOfflineRegion) {
        disposed.add(region.id)
    }

    override fun delete(region: NativeOfflineRegion, onResult: (Result<Unit>) -> Unit) {
        deleteCalls[region.id] = onResult
    }

    fun listSuccess(regions: List<NativeOfflineRegion>) {
        check(listCalls.isNotEmpty()) { "no pending list callback" }
        listCalls.removeFirst()(Result.success(regions))
    }

    fun listFailure(message: String) {
        check(listCalls.isNotEmpty()) { "no pending list callback" }
        listCalls.removeFirst()(Result.failure(IllegalStateException(message)))
    }

    fun statusSuccess(id: Long, value: NativeRegionStatus, times: Int = 1) {
        val callback = statusCalls[id] ?: error("no pending status callback for $id")
        repeat(times) { callback(Result.success(value)) }
        statusCalls.remove(id)
    }

    fun statusFailure(id: Long, message: String) {
        val callback = statusCalls.remove(id) ?: error("no pending status callback for $id")
        callback(Result.failure(IllegalStateException(message)))
    }

    fun deleteSuccess(id: Long, times: Int = 1) {
        val callback = deleteCalls[id] ?: error("no pending delete callback for $id")
        repeat(times) { callback(Result.success(Unit)) }
        deleteCalls.remove(id)
    }

    fun deleteFailure(id: Long, message: String) {
        val callback = deleteCalls.remove(id) ?: error("no pending delete callback for $id")
        callback(Result.failure(IllegalStateException(message)))
    }
}

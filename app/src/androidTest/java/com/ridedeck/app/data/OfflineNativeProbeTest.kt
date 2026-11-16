package com.ridedeck.app.data

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Native adapter probe. Never run as part of normal JVM tests. It uses a source-free style packaged
 * in the target app assets and the target application context, then touches only the region it
 * creates. It never resets MapLibre's process-global singleton and must never be used as
 * provider-compliance or tile-availability evidence.
 *
 * SDK inspection: NativeHttpRequest special-cases local:// and LocalRequestTask reads
 * integration/<path> from the target app assets. It does not use OkHttp and does not issue an
 * external URL. file:// is not supported by HttpRequestImpl (OkHttp HttpUrl.parse), so the probe
 * deliberately uses a packaged local asset instead of a file or loopback URL.
 */
class OfflineNativeProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun sourceFreeNativeCreateStatusListDeleteCleansOnlyOwnRegion() {
        val context = instrumentation.targetContext.applicationContext
        val manager = onMainResult {
            MapLibre.getInstance(context)
            OfflineManager.getInstance(context)
        }
        val marker = "__ridedeck_probe_${UUID.randomUUID()}"
        val beforeRegions = listRegions(manager, 5_000)
        assertNotNull("initial native list failed", beforeRegions)
        val before = beforeRegions.orEmpty().map { it.id }.toSet()
        var after: Set<Long>? = null
        var createdId: Long? = null
        try {
            val definition = OfflineTilePyramidRegionDefinition(
                "local://offline_probe_style.json",
                LatLngBounds.from(0.01, 0.01, -0.01, -0.01),
                0.0,
                1.0,
                1.0f,
            )
            val metadata = JSONObject()
                .put("v", OfflineRegionIdentity.SCHEMA)
                .put("area", marker)
                .put("lat", 0.0)
                .put("lon", 0.0)
                .put("r", 1)
                .put("day", true)
                .toString()
                .toByteArray()

            val created = createRegion(manager, definition, metadata, 10_000)
            createdId = created.id
            assertTrue("probe must not reuse an existing native id", created.id !in before)

            // No STATE_ACTIVE call: create/list/status/delete only.
            val status = statusOf(created, 5_000)
            assertNotNull("native region returned no status", status)

            val listed = listRegions(manager, 5_000).orEmpty()
            assertTrue("created region must be listed by id", listed.any { it.id == created.id })
        } finally {
            cleanupOwnOnly(manager, marker, createdId, before)
            after = listRegions(manager, 5_000)?.map { it.id }?.toSet()
        }
        assertNotNull("final native list failed", after)
        assertEquals("pre-existing native ids must be preserved", before, after)
    }

    private fun createRegion(
        manager: OfflineManager,
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
        timeoutMs: Long,
    ): OfflineRegion {
        val result = AtomicReference<OfflineRegion?>()
        val failure = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        onMain {
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    result.set(offlineRegion)
                    latch.countDown()
                }

                override fun onError(error: String) {
                    failure.set(error)
                    latch.countDown()
                }
            })
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "create region timed out" }
        failure.get()?.let { error("create region failed: $it") }
        return checkNotNull(result.get()) { "create region returned no region" }
    }

    private fun statusOf(region: OfflineRegion, timeoutMs: Long): OfflineRegionStatus? {
        val result = AtomicReference<OfflineRegionStatus?>()
        val failure = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        onMain {
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    result.set(status)
                    latch.countDown()
                }

                override fun onError(error: String?) {
                    failure.set(error ?: "unknown status error")
                    latch.countDown()
                }
            })
        }
        check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "status timed out" }
        failure.get()?.let { error("status failed: $it") }
        return result.get()
    }

    private fun listRegions(manager: OfflineManager, timeoutMs: Long): List<OfflineRegion>? {
        val result = AtomicReference<List<OfflineRegion>?>()
        val latch = CountDownLatch(1)
        onMain {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    result.set(offlineRegions?.toList())
                    latch.countDown()
                }

                override fun onError(error: String) {
                    latch.countDown()
                }
            })
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result.get()
    }

    private fun cleanupOwnOnly(
        manager: OfflineManager,
        marker: String,
        createdId: Long?,
        before: Set<Long>,
    ) {
        for (attempt in 0..2) {
            val candidates = listRegions(manager, 5_000).orEmpty().filter { region ->
                val id = region.id
                when {
                    createdId != null -> id == createdId
                    // If create timed out before returning an id, clean only a brand-new marker match.
                    id in before -> false
                    else -> markerOf(region) == marker
                }
            }
            if (candidates.isNotEmpty()) {
                candidates.forEach { region -> deleteOwn(region) }
                return
            }
            if (attempt < 2) Thread.sleep(250)
        }
    }

    private fun deleteOwn(region: OfflineRegion) {
        val latch = CountDownLatch(1)
        onMain {
            runCatching {
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        latch.countDown()
                    }

                    override fun onError(error: String) {
                        latch.countDown()
                    }
                })
            }.onFailure { latch.countDown() }
        }
        latch.await(5_000, TimeUnit.MILLISECONDS)
    }

    private fun markerOf(region: OfflineRegion): String? = runCatching {
        JSONObject(String(region.metadata, Charsets.UTF_8)).optString("area").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun <T> onMainResult(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        error.get()?.let { throw it }
        return checkNotNull(result.get())
    }
}

package com.ridedeck.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.ridedeck.app.ui.styleUrl
import com.ridedeck.app.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Downloads the map around the rider for use without a connection. Each area is two MapLibre regions (the day and the
 * night style), which share their tiles. Everything runs on the main thread, as MapLibre requires.
 */
object OfflineMaps {
    data class Progress(
        val name: String,
        val fraction: Float,
        val error: String? = null,
        val block: OfflineBlock? = null,
    )

    private val state = OfflineRegionState()
    val areas: StateFlow<List<OfflineArea>> = state.areas.asStateFlow()
    val regions: StateFlow<List<ReconciledRegion>> = state.regions.asStateFlow()
    val reconcileError: StateFlow<String?> = state.reconcileError.asStateFlow()
    val actionError: StateFlow<String?> = state.actionError.asStateFlow()
    val actionBlock: StateFlow<OfflineBlock?> = state.actionBlock.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val initLock = Any()
    private var controller: OfflineRegionController? = null

    @MainThread
    fun load(context: Context) {
        synchronized(initLock) {
            val app = context.applicationContext
            val active = controller ?: OfflineRegionController(
                gateway = AndroidOfflineGateway(app),
                loadAreas = { OfflinePlan.fromJson(Prefs.offlineAreas(app)) },
                saveAreas = { Prefs.setOfflineAreas(app, OfflinePlan.toJson(it)) },
                state = state,
            ).also { controller = it }
            // Controller.load() is idempotent after success and retryable after a persistence failure.
            active.load()
        }
    }

    /** Re-reads native regions/status without changing the persisted user areas. */
    @MainThread
    fun refresh(context: Context) {
        val active = synchronized(initLock) { controller }
        if (active == null) load(context) else active.refresh()
    }

    @MainThread
    fun download(context: Context, center: GeoPoint, radiusKm: Int, name: String) {
        if (_progress.value?.let { it.error == null && it.block == null } == true) return
        when (val block = OfflinePlan.blockFor(center, radiusKm)) {
            null -> startDownload(context, center, radiusKm, name)
            else -> _progress.value = Progress(name, 0f, block = block)
        }
    }

    /**
     * Only reached when [OfflinePlan.blockFor] allows the download. Today it always returns a block,
     * so nothing below is executed and no provider request is sent.
     */
    private fun startDownload(context: Context, center: GeoPoint, radiusKm: Int, name: String) {
        // Redundant with download(), intentionally keeps the guard immediately before every
        // native create/activate path in this legacy, currently unreachable function.
        OfflinePlan.blockFor(center, radiusKm)?.let {
            _progress.value = Progress(name, 0f, block = it)
            return
        }
        MapLibre.getInstance(context)
        val manager = OfflineManager.getInstance(context)
        manager.setOfflineMapboxTileCountLimit(OfflinePlan.MAX_TILES_PER_DOWNLOAD.toLong())
        val (sw, ne) = OfflinePlan.corners(center, radiusKm)
        val bounds = LatLngBounds.from(ne.lat, ne.lon, sw.lat, sw.lon)
        val density = context.resources.displayMetrics.density.coerceIn(1f, 2f)
        _progress.value = Progress(name, 0f)
        fun fail(message: String) { _progress.value = Progress(name, 0f, message) }

        fun fetch(day: Boolean, done: () -> Unit) {
            val definition = OfflineTilePyramidRegionDefinition(styleUrl(day), bounds, OfflinePlan.MIN_ZOOM, OfflinePlan.MAX_ZOOM, density)
            val meta = JSONObject()
                .put("v", OfflineRegionIdentity.SCHEMA)
                .put("area", name)
                .put("lat", center.lat)
                .put("lon", center.lon)
                .put("r", radiusKm)
                .put("day", day)
                .toString()
                .toByteArray()
            manager.createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    var finished = false
                    fun finish(action: () -> Unit) {
                        if (finished) return
                        finished = true
                        runCatching { offlineRegion.setObserver(null) }
                        runCatching { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                        action()
                    }
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val required = status.requiredResourceCount.coerceAtLeast(1)
                            val part = status.completedResourceCount.toFloat() / required
                            _progress.value = Progress(name, ((if (day) 0f else 0.5f) + part * 0.5f).coerceIn(0f, 1f))
                            if (status.isComplete) finish(done)
                        }
                        override fun onError(error: OfflineRegionError) {
                            finish { fail(error.message ?: error.reason ?: "error") }
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            finish { fail("tile limit $limit") }
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { fail(error) }
            })
        }

        fetch(day = true) {
            fetch(day = false) {
                val area = OfflineArea(name, center, radiusKm, System.currentTimeMillis())
                state.areas.value = state.areas.value.filter { it.name != name } + area
                Prefs.setOfflineAreas(context, OfflinePlan.toJson(state.areas.value))
                _progress.value = null
                controller?.refresh()
            }
        }
    }

    fun clearError() {
        val p = _progress.value
        if (p != null && (p.error != null || p.block != null)) _progress.value = null
    }

    /**
     * Stops an existing native download. Resume is capped by the same guard as a new download;
     * this candidate never activates a region or sends provider bytes.
     */
    @MainThread
    fun cancel(context: Context, area: OfflineArea) {
        controller?.cancel(area)
    }

    @MainThread
    fun resume(context: Context, area: OfflineArea): OfflineBlock {
        return controller?.resume(area) ?: OfflineBlock.ProviderUnverified
    }

    @MainThread
    fun delete(context: Context, area: OfflineArea, allowStaleRemoval: Boolean = false) {
        controller?.delete(area, allowStaleRemoval)
    }
}

/** MapLibre adapter; the callback/generation policy lives in [OfflineRegionController]. */
private class AndroidOfflineGateway(context: Context) : NativeOfflineGateway {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private fun manager(): OfflineManager {
        MapLibre.getInstance(appContext)
        return OfflineManager.getInstance(appContext)
    }

    override fun list(onResult: (Result<List<NativeOfflineRegion>>) -> Unit) {
        val finish = once(onResult)
        try {
            main.post {
                try {
                    manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                        override fun onList(offlineRegions: Array<OfflineRegion>?) {
                            finish(Result.success(offlineRegions.orEmpty().map { AndroidNativeRegion(it) }))
                        }

                        override fun onError(error: String) {
                            finish(Result.failure(IllegalStateException(error)))
                        }
                    })
                } catch (e: Exception) {
                    finish(Result.failure(e))
                }
            }
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }

    override fun status(region: NativeOfflineRegion, onResult: (Result<NativeRegionStatus>) -> Unit) {
        val finish = once(onResult)
        try {
            main.post {
                try {
                    native(region).getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status == null) {
                                finish(Result.failure(IllegalStateException("status unavailable")))
                            } else {
                                finish(Result.success(status.toModel()))
                            }
                        }

                        override fun onError(error: String?) {
                            finish(Result.failure(IllegalStateException(error ?: "status unavailable")))
                        }
                    })
                } catch (e: Exception) {
                    finish(Result.failure(e))
                }
            }
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }

    override fun setDownloadState(region: NativeOfflineRegion, state: NativeDownloadState) {
        main.post {
            runCatching {
                native(region).setDownloadState(
                    when (state) {
                        NativeDownloadState.ACTIVE -> OfflineRegion.STATE_ACTIVE
                        NativeDownloadState.INACTIVE -> OfflineRegion.STATE_INACTIVE
                    },
                )
            }
        }
    }

    override fun disposeObserver(region: NativeOfflineRegion) {
        main.post { runCatching { native(region).setObserver(null) } }
    }

    override fun delete(region: NativeOfflineRegion, onResult: (Result<Unit>) -> Unit) {
        val finish = once(onResult)
        try {
            main.post {
                try {
                    native(region).delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            finish(Result.success(Unit))
                        }

                        override fun onError(error: String) {
                            finish(Result.failure(IllegalStateException(error)))
                        }
                    })
                } catch (e: Exception) {
                    finish(Result.failure(e))
                }
            }
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }

    private fun <T> once(onResult: (Result<T>) -> Unit): (Result<T>) -> Unit {
        var delivered = false
        return { result ->
            if (!delivered) {
                delivered = true
                onResult(result)
            }
        }
    }

    private fun native(region: NativeOfflineRegion): OfflineRegion =
        (region as? AndroidNativeRegion)?.region ?: error("unknown native region handle")
}

private class AndroidNativeRegion(val region: OfflineRegion) : NativeOfflineRegion {
    override val id: Long get() = region.id
    override val metadata: ByteArray get() = region.metadata
}

private fun OfflineRegionStatus.toModel() = NativeRegionStatus(
    complete = isComplete,
    requiredResourceCount = requiredResourceCount,
    completedResourceCount = completedResourceCount,
    requiredResourceCountPrecise = isRequiredResourceCountPrecise,
    active = downloadState == OfflineRegion.STATE_ACTIVE,
)

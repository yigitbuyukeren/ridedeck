package com.ridedeck.app.data

import android.content.Context
import com.ridedeck.app.ui.styleUrl
import com.ridedeck.app.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
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
    data class Progress(val name: String, val fraction: Float, val error: String? = null)

    private val _areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val areas: StateFlow<List<OfflineArea>> = _areas
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _areas.value = OfflinePlan.fromJson(Prefs.offlineAreas(context))
    }

    fun download(context: Context, center: GeoPoint, radiusKm: Int, name: String) {
        if (_progress.value != null && _progress.value?.error == null) return
        MapLibre.getInstance(context)
        val manager = OfflineManager.getInstance(context)
        manager.setOfflineMapboxTileCountLimit(200_000L)
        val (sw, ne) = OfflinePlan.corners(center, radiusKm)
        val bounds = LatLngBounds.from(ne.lat, ne.lon, sw.lat, sw.lon)
        val density = context.resources.displayMetrics.density.coerceIn(1f, 2f)
        _progress.value = Progress(name, 0f)
        fun fail(message: String) { _progress.value = Progress(name, 0f, message) }

        fun fetch(day: Boolean, done: () -> Unit) {
            val definition = OfflineTilePyramidRegionDefinition(styleUrl(day), bounds, OfflinePlan.MIN_ZOOM, OfflinePlan.MAX_ZOOM, density)
            val meta = JSONObject().put("area", name).put("day", day).toString().toByteArray()
            manager.createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val required = status.requiredResourceCount.coerceAtLeast(1)
                            val part = status.completedResourceCount.toFloat() / required
                            _progress.value = Progress(name, ((if (day) 0f else 0.5f) + part * 0.5f).coerceIn(0f, 1f))
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                done()
                            }
                        }
                        override fun onError(error: OfflineRegionError) { fail(error.message ?: error.reason ?: "error") }
                        override fun mapboxTileCountLimitExceeded(limit: Long) { fail("tile limit $limit") }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) { fail(error) }
            })
        }

        fetch(day = true) {
            fetch(day = false) {
                val area = OfflineArea(name, center, radiusKm, System.currentTimeMillis())
                _areas.value = _areas.value.filter { it.name != name } + area
                Prefs.setOfflineAreas(context, OfflinePlan.toJson(_areas.value))
                _progress.value = null
            }
        }
    }

    fun clearError() { if (_progress.value?.error != null) _progress.value = null }

    fun delete(context: Context, area: OfflineArea) {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions?.filter { runCatching { JSONObject(String(it.metadata)).getString("area") }.getOrNull() == area.name }
                    ?.forEach { it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {}
                        override fun onError(error: String) {}
                    }) }
            }
            override fun onError(error: String) {}
        })
        _areas.value = _areas.value.filter { it.name != area.name }
        Prefs.setOfflineAreas(context, OfflinePlan.toJson(_areas.value))
    }
}

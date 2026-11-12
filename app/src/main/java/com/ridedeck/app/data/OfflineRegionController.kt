package com.ridedeck.app.data

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Opaque native region handle supplied by an [NativeOfflineGateway]. */
interface NativeOfflineRegion {
    val id: Long
    val metadata: ByteArray
}

enum class NativeDownloadState { ACTIVE, INACTIVE }

/**
 * Injectable boundary around MapLibre. The controller never talks to MapLibre directly, so callback
 * ordering, failures and observer disposal stay deterministic in JVM tests.
 */
interface NativeOfflineGateway {
    fun list(onResult: (Result<List<NativeOfflineRegion>>) -> Unit)
    fun status(region: NativeOfflineRegion, onResult: (Result<NativeRegionStatus>) -> Unit)
    fun setDownloadState(region: NativeOfflineRegion, state: NativeDownloadState)
    fun disposeObserver(region: NativeOfflineRegion)
    fun delete(region: NativeOfflineRegion, onResult: (Result<Unit>) -> Unit)
}

/** State shared with the Android facade; tests can use the same flows. */
class OfflineRegionState {
    val areas = MutableStateFlow<List<OfflineArea>>(emptyList())
    val regions = MutableStateFlow<List<ReconciledRegion>>(emptyList())
    val reconcileError = MutableStateFlow<String?>(null)
    val actionError = MutableStateFlow<String?>(null)
    val actionBlock = MutableStateFlow<OfflineBlock?>(null)
}

/**
 * Native region lifecycle coordinator. Refresh generations are separate from per-area action
 * tokens:
 * - a refresh can never cancel a delete/cancel;
 * - another action for the same area supersedes the old one, and its own refresh makes the
 *   resulting partial native state visible;
 * - areas are removed from persistence only after every native delete succeeds.
 */
class OfflineRegionController(
    private val gateway: NativeOfflineGateway,
    private val loadAreas: () -> List<OfflineArea>,
    private val saveAreas: (List<OfflineArea>) -> Unit,
    private val state: OfflineRegionState = OfflineRegionState(),
    private val blockFor: (GeoPoint, Int) -> OfflineBlock? = OfflinePlan::blockFor,
) {
    private val lock = Any()
    private var loaded = false
    private var refreshGeneration = 0L
    private var actionSequence = 0L
    private val actionTokens = HashMap<OfflineRegionIdentity, Long>()

    val areas: StateFlow<List<OfflineArea>> = state.areas.asStateFlow()
    val regions: StateFlow<List<ReconciledRegion>> = state.regions.asStateFlow()
    val reconcileError: StateFlow<String?> = state.reconcileError.asStateFlow()
    val actionError: StateFlow<String?> = state.actionError.asStateFlow()
    val actionBlock: StateFlow<OfflineBlock?> = state.actionBlock.asStateFlow()

    fun load() = synchronized(lock) {
        if (loaded) return
        val persisted = try {
            loadAreas()
        } catch (e: Exception) {
            state.reconcileError.value = e.message ?: "areas unavailable"
            return
        }
        loaded = true
        state.areas.value = persisted
        refresh()
    }

    fun refresh() = synchronized(lock) {
        val token = ++refreshGeneration
        val areas = state.areas.value
        if (areas.isEmpty()) {
            state.regions.value = emptyList()
            state.reconcileError.value = null
            return
        }
        gateway.list { result ->
            synchronized(lock) {
                if (token != refreshGeneration) return@synchronized
                result.fold(
                    onSuccess = { natives -> statusAll(token, areas, natives) },
                    onFailure = { publishListingFailure(token, areas, it.message ?: "list failed") },
                )
            }
        }
    }

    fun cancel(area: OfflineArea) = synchronized(lock) {
        val token = nextActionToken(area)
        state.actionBlock.value = null
        gateway.list { result ->
            synchronized(lock) {
                if (!isCurrentAction(area, token)) return@synchronized
                result.fold(
                    onSuccess = { natives ->
                        val named = natives.map { it to nativeRegion(it) }.filter { it.second.areaName == area.name }
                        val matches = named.filter { it.second.identity?.matches(area) == true }.map { it.first }
                        var failure: String? = null
                        when {
                            named.isNotEmpty() && matches.isEmpty() -> failure = "native identity unverified"
                            matches.isEmpty() -> failure = "native region missing"
                        }
                        matches.forEach { region ->
                            try {
                                gateway.disposeObserver(region)
                                gateway.setDownloadState(region, NativeDownloadState.INACTIVE)
                            } catch (e: Exception) {
                                failure = e.message ?: "cancel failed"
                            }
                        }
                        finishAction(area, token, failure)
                    },
                    onFailure = { failAction(area, token, it.message ?: "list failed") },
                )
            }
        }
    }

    /**
     * Resume is capped by the same policy gate as a new download. There is deliberately no native
     * call before this returns; a null guard result still falls back to ProviderUnverified so this
     * candidate can never activate an existing region and send provider bytes.
     */
    fun resume(area: OfflineArea): OfflineBlock = synchronized(lock) {
        val block = blockFor(area.center, area.radiusKm) ?: OfflineBlock.ProviderUnverified
        state.actionBlock.value = block
        state.actionError.value = null
        block
    }

    fun delete(area: OfflineArea, allowStaleRemoval: Boolean = false) = synchronized(lock) {
        val token = nextActionToken(area)
        state.actionBlock.value = null
        gateway.list { result ->
            synchronized(lock) {
                if (!isCurrentAction(area, token)) return@synchronized
                result.fold(
                    onSuccess = { natives ->
                        val named = natives.map { it to nativeRegion(it) }.filter { it.second.areaName == area.name }
                        val matches = named.filter { it.second.identity?.matches(area) == true }.map { it.first }
                        when {
                            named.isNotEmpty() && matches.isEmpty() -> {
                                finishAction(area, token, "native identity unverified")
                                return@fold
                            }
                            matches.isEmpty() -> {
                                if (!allowStaleRemoval) {
                                    finishAction(area, token, "native region missing")
                                    return@fold
                                }
                                val saveError = removeArea(area)
                                finishAction(area, token, saveError)
                                return@fold
                            }
                        }
                        var remaining = matches.size
                        var failure: String? = null
                        val done = BooleanArray(matches.size)
                        fun oneDone(index: Int) {
                            if (!isCurrentAction(area, token) || done[index]) return
                            done[index] = true
                            if (--remaining != 0) return
                            if (failure == null) failure = removeArea(area)
                            finishAction(area, token, failure)
                        }
                        matches.forEachIndexed { index, region ->
                            try {
                                gateway.setDownloadState(region, NativeDownloadState.INACTIVE)
                                gateway.disposeObserver(region)
                                gateway.delete(region) { outcome ->
                                    synchronized(lock) {
                                        if (!isCurrentAction(area, token)) return@synchronized
                                        outcome.onFailure { failure = it.message ?: "delete failed" }
                                        oneDone(index)
                                    }
                                }
                            } catch (e: Exception) {
                                if (!done[index]) {
                                    failure = e.message ?: "delete failed"
                                    oneDone(index)
                                }
                            }
                        }
                    },
                    onFailure = { failAction(area, token, it.message ?: "list failed") },
                )
            }
        }
    }

    private fun statusAll(token: Long, areas: List<OfflineArea>, natives: List<NativeOfflineRegion>) {
        val parsed = natives.map { nativeRegion(it) }
        val trusted = parsed.indices.filter { index ->
            areas.any { area -> parsed[index].identity?.matches(area) == true }
        }
        if (trusted.isEmpty()) {
            // Orphan, legacy and mismatched regions are still visible to the pure model, but no
            // native status call is made for them.
            publish(token, areas, parsed)
            return
        }
        val results = parsed.toTypedArray()
        val done = BooleanArray(natives.size)
        var remaining = trusted.size
        fun complete(index: Int, native: NativeRegion) {
            if (token != refreshGeneration || done[index]) return
            done[index] = true
            results[index] = native
            if (--remaining != 0) return
            publish(token, areas, results.filterNotNull())
        }
        trusted.forEach { index ->
            val native = parsed[index]
            try {
                gateway.status(natives[index]) { result ->
                    synchronized(lock) {
                        result.fold(
                            onSuccess = { complete(index, native.copy(status = it)) },
                            onFailure = { complete(index, native.copy(error = it.message ?: "status unavailable")) },
                        )
                    }
                }
            } catch (e: Exception) {
                complete(index, native.copy(error = e.message ?: "status unavailable"))
            }
        }
    }

    private fun nativeRegion(region: NativeOfflineRegion): NativeRegion {
        val meta = runCatching { JSONObject(String(region.metadata, StandardCharsets.UTF_8)) }.getOrNull()
        val areaName = (meta?.opt("area") as? String)?.takeIf { it.isNotBlank() }
        val rawDay = meta?.opt("day")
        val style = when (rawDay) {
            is Boolean -> if (rawDay) OfflineStyle.DAY else OfflineStyle.NIGHT
            else -> null
        }
        return NativeRegion(region.id, areaName, style, canonicalIdentity(meta, areaName))
    }

    private fun canonicalIdentity(meta: JSONObject?, areaName: String?): OfflineRegionIdentity? {
        if (meta == null || areaName == null) return null
        val schema = meta.opt("v")
        if (schema !is Number || !schema.toDouble().isFinite() ||
            schema.toDouble() != OfflineRegionIdentity.SCHEMA.toDouble()
        ) {
            return null
        }
        if (meta.opt("day") !is Boolean) return null
        val rawLat = meta.opt("lat")
        val rawLon = meta.opt("lon")
        val rawRadius = meta.opt("r")
        if (rawLat !is Number || rawLon !is Number || rawRadius !is Number) return null
        val lat = rawLat.toDouble()
        val lon = rawLon.toDouble()
        val radius = rawRadius.toDouble()
        if (!lat.isFinite() || !lon.isFinite() || !radius.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (radius <= 0.0 || radius % 1.0 != 0.0 || radius > Int.MAX_VALUE) return null
        return OfflineRegionIdentity(areaName, lat, lon, radius.toInt(), OfflineRegionIdentity.SCHEMA)
    }

    private fun publish(token: Long, areas: List<OfflineArea>, natives: List<NativeRegion>) {
        if (token != refreshGeneration) return
        state.regions.value = OfflineRegions.reconcile(areas, natives)
        state.reconcileError.value = null
    }

    private fun publishListingFailure(token: Long, areas: List<OfflineArea>, error: String) {
        if (token != refreshGeneration) return
        state.regions.value = OfflineRegions.reconcile(areas, emptyList(), listingError = error)
        state.reconcileError.value = error
    }

    private fun removeArea(area: OfflineArea): String? {
        val remaining = state.areas.value.filter { it != area }
        return try {
            saveAreas(remaining)
            state.areas.value = remaining
            null
        } catch (e: Exception) {
            e.message ?: "save failed"
        }
    }

    private fun nextActionToken(area: OfflineArea): Long {
        val token = ++actionSequence
        actionTokens[area.identity()] = token
        return token
    }

    private fun isCurrentAction(area: OfflineArea, token: Long): Boolean =
        actionTokens[area.identity()] == token

    private fun finishAction(area: OfflineArea, token: Long, error: String?) {
        if (!isCurrentAction(area, token)) return
        actionTokens.remove(area.identity())
        state.actionError.value = error
        refresh()
    }

    private fun failAction(area: OfflineArea, token: Long, error: String) {
        finishAction(area, token, error)
    }
}

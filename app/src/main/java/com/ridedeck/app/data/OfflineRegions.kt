package com.ridedeck.app.data

/** Which MapLibre style a native offline region was created for. */
enum class OfflineStyle { DAY, NIGHT }

/**
 * Canonical identity written into metadata from schema 2 onward. A display name alone cannot
 * distinguish two user areas that reused the same name with a different center or radius.
 */
data class OfflineRegionIdentity(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Int,
    val schema: Int = SCHEMA,
) {
    fun matches(area: OfflineArea): Boolean =
        schema == SCHEMA && name == area.name && radiusKm == area.radiusKm &&
            kotlin.math.abs(lat - area.center.lat) <= COORD_TOLERANCE &&
            kotlin.math.abs(lon - area.center.lon) <= COORD_TOLERANCE

    companion object {
        const val SCHEMA = 2
        private const val COORD_TOLERANCE = 1e-6
    }
}

fun OfflineArea.identity(): OfflineRegionIdentity =
    OfflineRegionIdentity(name, center.lat, center.lon, radiusKm)

/**
 * Native MapLibre status for one region, reduced to the values needed for reconciliation.
 * A missing status is deliberately not treated as evidence: metadata alone never proves tiles.
 */
data class NativeRegionStatus(
    val complete: Boolean,
    val requiredResourceCount: Long,
    val completedResourceCount: Long,
    val requiredResourceCountPrecise: Boolean,
    val active: Boolean,
)

/**
 * One native MapLibre region. [areaName] and [style] come from the region metadata and only
 * identify the user area; they are never readiness evidence by themselves.
 */
data class NativeRegion(
    val id: Long,
    val areaName: String?,
    val style: OfflineStyle?,
    val identity: OfflineRegionIdentity? = null,
    val status: NativeRegionStatus? = null,
    val error: String? = null,
)

enum class NativeRegionState {
    /**
     * Both native statuses are precise and complete. This is native evidence only: it does not
     * prove a tile renders, and it must not be mapped to a global offlineStorageVerified flag.
     */
    NATIVE_COMPLETE,
    DOWNLOADING,
    PAUSED,
    PARTIAL,
    MISSING,
    ERROR,
    UNKNOWN,
}

/** A user area matched against its native day/night regions without removing the area. */
data class ReconciledRegion(
    val area: OfflineArea,
    val state: NativeRegionState,
    val day: NativeRegion? = null,
    val night: NativeRegion? = null,
    val error: String? = null,
)

/**
 * Pure reconciliation of persisted user areas with native MapLibre regions. It never deletes or
 * rewrites an area: missing, stale, duplicate or errored native data can only downgrade native state.
 *
 * NATIVE_COMPLETE requires sound native evidence for both styles: a status callback with a precise,
 * non-zero required resource count and isComplete. It is not a global READY/offlineStorageVerified
 * marker; metadata/name matching alone always yields UNKNOWN or MISSING.
 */
object OfflineRegions {
    fun reconcile(
        areas: List<OfflineArea>,
        natives: List<NativeRegion>,
        listingError: String? = null,
    ): List<ReconciledRegion> = areas.map { area ->
        val named = natives.filter { it.areaName == area.name }
        // Name-only/legacy metadata is not trusted. It can belong to a same-named area with a
        // different center or radius, so only a canonical identity match may carry status evidence.
        val matches = named.filter { it.identity?.matches(area) == true }
        val identityError = if (matches.isEmpty() && named.isNotEmpty()) "native identity unverified" else null
        val (day, duplicateDay) = oneFor(matches, OfflineStyle.DAY)
        val (night, duplicateNight) = oneFor(matches, OfflineStyle.NIGHT)
        val nativeError = listingError ?: identityError ?: day?.error ?: night?.error
        val error = when {
            nativeError != null -> nativeError
            duplicateDay || duplicateNight -> "duplicate native region"
            else -> null
        }
        val dayStatus = day?.status
        val nightStatus = night?.status
        val state = when {
            listingError != null -> NativeRegionState.ERROR
            identityError != null -> NativeRegionState.UNKNOWN
            day?.error != null || night?.error != null -> NativeRegionState.ERROR
            duplicateDay || duplicateNight -> NativeRegionState.UNKNOWN
            day == null && night == null -> NativeRegionState.MISSING
            day == null || night == null -> NativeRegionState.PARTIAL
            dayStatus == null || nightStatus == null -> NativeRegionState.UNKNOWN
            dayStatus.active || nightStatus.active -> NativeRegionState.DOWNLOADING
            dayStatus.complete && nightStatus.complete -> if (
                dayStatus.requiredResourceCount > 0 && nightStatus.requiredResourceCount > 0 &&
                dayStatus.requiredResourceCountPrecise && nightStatus.requiredResourceCountPrecise
            ) {
                NativeRegionState.NATIVE_COMPLETE
            } else {
                // Complete without a precise, non-zero required count is not sound enough for NATIVE_COMPLETE.
                NativeRegionState.UNKNOWN
            }
            !dayStatus.complete && !nightStatus.complete -> NativeRegionState.PAUSED
            else -> NativeRegionState.PARTIAL
        }
        ReconciledRegion(area, state, day, night, error)
    }

    private fun oneFor(regions: List<NativeRegion>, style: OfflineStyle): Pair<NativeRegion?, Boolean> {
        val matches = regions.filter { it.style == style }
        return when (matches.size) {
            0 -> null to false
            1 -> matches[0] to false
            else -> null to true
        }
    }
}

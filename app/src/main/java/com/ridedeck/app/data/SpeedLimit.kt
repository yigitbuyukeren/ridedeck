package com.ridedeck.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object SpeedLimitMath {
    /** GPS speed is rounded and slightly noisy; only warn clearly above the limit. */
    const val WARN_TOLERANCE_KMH = 5

    /**
     * Reads the posted limit of the last matched edge from a Valhalla `trace_attributes` reply.
     * Null when the road has no limit in OpenStreetMap or the limit is not a plain number.
     */
    fun parseLimit(json: String): Int? {
        val edges = JSONObject(json).optJSONArray("edges") ?: return null
        val last = edges.optJSONObject(edges.length() - 1) ?: return null
        val limit = last.optInt("speed_limit", -1)
        return limit.takeIf { it in 5..200 }
    }

    fun isOver(speedKmh: Int, limit: Int?): Boolean = limit != null && speedKmh > limit

    fun shouldWarn(speedKmh: Int, limit: Int?): Boolean = limit != null && speedKmh > limit + WARN_TOLERANCE_KMH
}

/** Posted speed limit of the road under the last few GPS fixes, from the public Valhalla server. */
class SpeedLimitRepository(
    /** Valhalla costing that matches the vehicle, so the trail snaps to roads the vehicle may use. */
    private val costing: () -> String = { "motorcycle" },
) {
    suspend fun limitAt(trail: List<GeoPoint>): Result<Int?> = withContext(Dispatchers.IO) {
        try {
            val shape = JSONArray().also { arr -> trail.forEach { arr.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) } }
            val body = JSONObject()
                .put("shape", shape)
                .put("costing", costing())
                .put("shape_match", "map_snap")
                .put("filters", JSONObject().put("attributes", JSONArray().put("edge.speed_limit")).put("action", "include"))
            Result.success(SpeedLimitMath.parseLimit(Http.postJson("https://valhalla1.openstreetmap.de/trace_attributes", body.toString(), connectMs = 8_000, readMs = 10_000, emptyOn400 = true)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RideDeck", "speed limit lookup failed (${e.javaClass.simpleName})")
            Result.failure(e)
        }
    }
}

/**
 * Keeps [limit] up to date while [active] (navigation guidance). Looks up at most every
 * [MIN_INTERVAL_MS] and only after moving [MIN_MOVE_M], so the public server sees roughly one
 * small request every ten seconds per rider. Calls [onWarn] when the rider stays over the limit.
 */
class SpeedLimitController(
    private val scope: CoroutineScope,
    location: StateFlow<LocationState>,
    private val active: StateFlow<Boolean>,
    private val repo: SpeedLimitRepository = SpeedLimitRepository(),
    private val onWarn: (Int) -> Unit,
) {
    private val _limit = MutableStateFlow<Int?>(null)
    val limit: StateFlow<Int?> = _limit

    private val trail = ArrayDeque<GeoPoint>()
    private var lastFixAt = 0L
    private var lastQueryAt = 0L
    private var lastQueryPoint: GeoPoint? = null
    private var lastKnownAt = 0L
    private var inFlight = false
    private var overSince = 0L
    private var lastWarnAt = 0L

    init {
        scope.launch { location.collect { onFix(it) } }
    }

    private fun onFix(fix: LocationState) {
        if (fix.fixAtMs == 0L || fix.fixAtMs == lastFixAt) return
        lastFixAt = fix.fixAtMs
        if (!active.value) {
            reset()
            return
        }
        val here = GeoPoint(fix.lat, fix.lon)
        if (trail.lastOrNull()?.let { Geo.distance(it, here) >= TRAIL_SPACING_M } != false) {
            trail.addLast(here)
            while (trail.size > TRAIL_SIZE) trail.removeFirst()
        }
        val movedSinceQuery = lastQueryPoint?.let { Geo.distance(it, here) } ?: Double.MAX_VALUE
        if (!inFlight && trail.size >= 3 && fix.speedKmh >= MIN_SPEED_KMH &&
            fix.fixAtMs - lastQueryAt >= MIN_INTERVAL_MS && movedSinceQuery >= MIN_MOVE_M
        ) {
            query(fix.fixAtMs, here, trail.toList())
        }
        if (_limit.value != null && fix.fixAtMs - lastKnownAt > STALE_MS) _limit.value = null
        checkWarning(fix)
    }

    private fun query(now: Long, here: GeoPoint, points: List<GeoPoint>) {
        inFlight = true
        lastQueryAt = now
        lastQueryPoint = here
        scope.launch {
            val result = repo.limitAt(points)
            inFlight = false
            result.onSuccess { found ->
                _limit.value = found
                if (found != null) lastKnownAt = lastFixAt
            }
        }
    }

    private fun checkWarning(fix: LocationState) {
        val limit = _limit.value
        if (!SpeedLimitMath.shouldWarn(fix.speedKmh, limit)) {
            overSince = 0L
            return
        }
        if (overSince == 0L) overSince = fix.fixAtMs
        if (fix.fixAtMs - overSince >= WARN_AFTER_MS && fix.fixAtMs - lastWarnAt >= WARN_COOLDOWN_MS) {
            lastWarnAt = fix.fixAtMs
            onWarn(limit!!)
        }
    }

    private fun reset() {
        trail.clear()
        _limit.value = null
        lastQueryPoint = null
        lastQueryAt = 0L
        overSince = 0L
    }

    private companion object {
        const val TRAIL_SIZE = 4
        const val TRAIL_SPACING_M = 40.0
        const val MIN_SPEED_KMH = 10
        const val MIN_INTERVAL_MS = 8_000L
        const val MIN_MOVE_M = 60.0
        const val STALE_MS = 60_000L
        const val WARN_AFTER_MS = 3_000L
        const val WARN_COOLDOWN_MS = 60_000L
    }
}

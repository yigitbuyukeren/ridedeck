package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The trip being guided, kept so it can be offered again after the process was killed. Only the places are
 * stored (destination, the final destination behind a fuel stop, and the stops still ahead); the route is recomputed.
 *
 * [stops] deliberately contains only waypoints not yet passed, in travel order. A restored trip is a preview:
 * it never starts guidance, tracking or a service by itself.
 *
 * [options] is null for legacy v1 records that predate route-option persistence. Restore must keep the current
 * choices in that case instead of silently replacing them with defaults.
 */
data class ActiveTrip(
    val destination: Place,
    val finalDestination: Place?,
    val savedAtMs: Long,
    val stops: List<Place> = emptyList(),
    val options: RouteOptions? = null,
) {
    fun toJson(): String {
        val o = JSONObject()
            .put("dest", place(destination))
            .apply { finalDestination?.let { put("final", place(it)) } }
            .apply { if (stops.isNotEmpty()) put("stops", stopsJson(stops)) }
            .put("at", savedAtMs)
        if (options != null) {
            o.put("v", VERSION)
                .put("opts", JSONObject().put("h", options.avoidHighways).put("t", options.avoidTolls))
        }
        return o.toString()
    }

    /** Age check that cannot overflow on an extreme stored or current timestamp. */
    fun fresh(nowMs: Long): Boolean =
        savedAtMs >= 0 && savedAtMs <= nowMs && nowMs - savedAtMs <= MAX_AGE_MS

    companion object {
        /** 1 = destination/final/timestamp/stops; 2 adds mandatory route options. */
        const val VERSION = 2

        /** Kept equal to NavigationController's maximum intermediate stops. */
        const val MAX_STOPS = 3

        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private fun place(p: Place) = JSONObject()
            .put("n", p.name).put("d", p.detail).put("lat", p.point.lat).put("lon", p.point.lon)

        private fun stopsJson(stops: List<Place>) = JSONArray().also { arr -> stops.forEach { arr.put(place(it)) } }

        /** Strict place parsing: strings stay strings, coordinates stay finite numbers in range. */
        private fun place(o: JSONObject): Place? {
            val name = o.opt("n") as? String ?: return null
            val detail = if (o.has("d")) (o.opt("d") as? String ?: return null) else ""
            val lat = coordinate(o, "lat", 90.0) ?: return null
            val lon = coordinate(o, "lon", 180.0) ?: return null
            return Place(name, detail, GeoPoint(lat, lon))
        }

        private fun coordinate(o: JSONObject, key: String, limit: Double): Double? {
            val raw = o.opt(key) as? Number ?: return null
            val value = raw.toDouble()
            return value.takeIf { it.isFinite() && it in -limit..limit }
        }

        /**
         * Milliseconds must be a non-negative integral number within the exact Long range. Android org.json
         * normally yields Int/Long/Double, but BigInteger/BigDecimal are handled without a lossy toDouble round trip.
         */
        private fun timestamp(raw: Any?): Long? {
            val value = when (raw) {
                is Byte -> raw.toLong()
                is Short -> raw.toLong()
                is Int -> raw.toLong()
                is Long -> raw
                is BigInteger -> raw.takeIf {
                    it.signum() >= 0 && it <= BigInteger.valueOf(Long.MAX_VALUE)
                }?.toLong()
                is BigDecimal -> raw.takeIf {
                    it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 && it <= BigDecimal.valueOf(Long.MAX_VALUE)
                }?.toLong()
                is Float -> exactDoubleTimestamp(raw.toDouble())
                is Double -> exactDoubleTimestamp(raw)
                else -> null
            }
            return value?.takeIf { it >= 0 }
        }

        /** A Double is accepted only below 2^63; Long.MAX_VALUE itself cannot be represented exactly as Double. */
        private fun exactDoubleTimestamp(value: Double): Long? {
            if (!value.isFinite() || value < 0 || value >= 9_223_372_036_854_775_808.0 || value % 1.0 != 0.0) return null
            return value.toLong()
        }

        private fun parseVersion(o: JSONObject): Int? {
            if (!o.has("v")) return 1
            return when (val raw = o.opt("v")) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
        }

        /** v2 options are mandatory and both booleans; absent in v1 means "keep the current choices". */
        private fun parseOptions(raw: Any?): RouteOptions? {
            val o = raw as? JSONObject ?: return null
            if (!o.has("h") || !o.has("t")) return null
            val highways = o.opt("h") as? Boolean ?: return null
            val tolls = o.opt("t") as? Boolean ?: return null
            return RouteOptions(avoidHighways = highways, avoidTolls = tolls)
        }

        /**
         * Parses a saved trip. v1 records without options load with [ActiveTrip.options] null so the caller keeps
         * its current choices. Malformed values, coercible strings, wrong types, too many stops and unknown newer
         * versions are rejected instead of being partially guessed.
         */
        fun fromJson(json: String?): ActiveTrip? = runCatching {
            val o = JSONObject(json ?: return null)
            val version = parseVersion(o) ?: return null
            if (version !in 1..VERSION) return null

            val destJson = o.opt("dest") as? JSONObject ?: return null
            val destination = place(destJson) ?: return null

            val finalDestination = if (o.has("final")) {
                val raw = o.opt("final") as? JSONObject ?: return null
                place(raw) ?: return null
            } else null

            val stops = if (o.has("stops")) {
                val raw = o.opt("stops") as? JSONArray ?: return null
                if (raw.length() > MAX_STOPS) return null
                val parsed = ArrayList<Place>(raw.length())
                for (i in 0 until raw.length()) {
                    val item = raw.opt(i) as? JSONObject ?: return null
                    parsed += place(item) ?: return null
                }
                parsed
            } else emptyList()

            val options = when {
                o.has("opts") -> parseOptions(o.opt("opts")) ?: return null
                version >= 2 -> return null
                else -> null
            }

            val savedAtMs = timestamp(o.opt("at")) ?: return null
            ActiveTrip(destination, finalDestination, savedAtMs, stops, options)
        }.getOrNull()

        /** Age-bounded restore used after a process kill; stale, future-dated or malformed records are ignored. */
        fun restore(json: String?, nowMs: Long): ActiveTrip? = fromJson(json)?.takeIf { it.fresh(nowMs) }
    }
}

package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * FEAT-004 candidate only: a bounded, versioned, integrity-checked route geometry record.
 *
 * This file is intentionally storage-agnostic and is not wired into navigation yet. It never downloads tiles,
 * never computes a route, and never starts guidance. A future integration must still apply the normal age,
 * preview-only and explicit-continue rules.
 */
data class RouteGeometrySnapshot(
    val destination: Place,
    val finalDestination: Place?,
    val remainingStops: List<Place>,
    val options: RouteOptions,
    val savedAtMs: Long,
    val route: Route,
) {
    fun fresh(nowMs: Long): Boolean =
        savedAtMs >= 0 && savedAtMs <= nowMs && nowMs - savedAtMs <= MAX_AGE_MS

    /**
     * Stable linkage against the existing ActiveTrip: coordinates, remaining-stop order and route options,
     * not display names. A passed-stop or option change invalidates the geometry.
     */
    fun matches(activeTrip: ActiveTrip): Boolean =
        destination.point == activeTrip.destination.point &&
            finalDestination?.point == activeTrip.finalDestination?.point &&
            remainingStops.map { it.point } == activeTrip.stops.map { it.point } &&
            activeTrip.options != null &&
            options == activeTrip.options

    /** Serializes only after every bound is satisfied, so oversized geometry cannot silently enter storage. */
    fun toJson(): String {
        require(savedAtMs >= 0) { "negative timestamp" }
        require(remainingStops.size <= MAX_STOPS) { "too many stops" }
        require(route.shape.size in 2..MAX_SHAPE_POINTS) { "shape size" }
        require(route.maneuvers.size in 1..MAX_MANEUVERS) { "maneuver count" }
        require(route.stopIndices.size <= MAX_STOPS) { "too many stop indices" }
        require(route.isUsable()) { "unusable route" }
        require(linkageValid(destination, finalDestination, remainingStops, route)) { "trip/route linkage" }
        require(route.distanceM.isFinite() && route.distanceM > 0) { "distance" }
        require(route.durationS.isFinite() && route.durationS >= 0) { "duration" }
        route.maneuvers.forEach(::requireBoundedManeuver)

        return JSONObject()
            .put("v", VERSION)
            .put("dest", placeJson(destination))
            .apply { finalDestination?.let { put("final", placeJson(it)) } }
            .put("stops", JSONArray().also { arr -> remainingStops.forEach { arr.put(placeJson(it)) } })
            .put("opts", JSONObject().put("h", options.avoidHighways).put("t", options.avoidTolls))
            .put("route", routeJson(route).put("hash", checksum(route)))
            .put("at", savedAtMs)
            .toString()
    }

    companion object {
        const val VERSION = 1
        const val MAX_SHAPE_POINTS = 20_000
        const val MAX_MANEUVERS = 512
        const val MAX_STOPS = ActiveTrip.MAX_STOPS
        const val MAX_STREET_NAMES = 8
        const val MAX_TEXT_CHARS = 512
        const val MAX_PAYLOAD_CHARS = 1_000_000
        const val MAX_PAYLOAD_BYTES = 4_000_000
        const val MAX_AGE_MS = ActiveTrip.MAX_AGE_MS
        const val MAX_ENDPOINT_LINK_M = 500.0
        const val MAX_DISTANCE_SKEW_RATIO = 0.20
        const val MIN_DISTANCE_SKEW_M = 100.0

        fun fromJson(json: String?): RouteGeometrySnapshot? = runCatching {
            if (json == null || json.length > MAX_PAYLOAD_CHARS) return null
            val o = JSONObject(json)
            if (int(o.opt("v")) != VERSION) return null

            val destination = place(o.opt("dest")) ?: return null
            val finalDestination = if (o.has("final")) place(o.opt("final")) ?: return null else null
            val stops = places(o.opt("stops")) ?: return null
            if (stops.size > MAX_STOPS) return null
            val options = options(o.opt("opts")) ?: return null
            val routeJson = o.opt("route") as? JSONObject ?: return null
            val route = route(routeJson) ?: return null
            val savedAtMs = timestamp(o.opt("at")) ?: return null
            if (!linkageValid(destination, finalDestination, stops, route)) return null
            RouteGeometrySnapshot(destination, finalDestination, stops, options, savedAtMs, route)
        }.getOrNull()

        fun decode(json: String?, nowMs: Long): RouteGeometrySnapshot? =
            fromJson(json)?.takeIf { it.fresh(nowMs) }

        private fun placeJson(p: Place): JSONObject = JSONObject()
            .put("n", p.name).put("d", p.detail).put("lat", p.point.lat).put("lon", p.point.lon)

        private fun routeJson(route: Route): JSONObject = JSONObject()
            .put("shape", JSONArray().also { arr ->
                route.shape.forEach { p -> arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon)) }
            })
            .put("maneuvers", JSONArray().also { arr ->
                route.maneuvers.forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("type", m.type)
                            .put("instr", m.instruction)
                            .put("alert", m.alert)
                            .put("verbal", m.verbal)
                            .put("len", m.lengthM)
                            .put("begin", m.beginIndex)
                            .put("time", m.timeS)
                            .put("street", JSONArray().also { names -> m.streetNames.forEach { names.put(it) } })
                            .put("exit", m.roundaboutExit)
                            .put("post", m.postVerbal)
                            .put("stop", m.isStop)
                    )
                }
            })
            .put("distance", route.distanceM)
            .put("duration", route.durationS)
            .put("stopIndices", JSONArray().also { arr -> route.stopIndices.forEach { arr.put(it) } })

        private fun requireBoundedManeuver(m: Maneuver) {
            require(m.instruction.length <= MAX_TEXT_CHARS) { "instruction text" }
            require(m.alert.length <= MAX_TEXT_CHARS) { "alert text" }
            require(m.verbal.length <= MAX_TEXT_CHARS) { "verbal text" }
            require(m.postVerbal.length <= MAX_TEXT_CHARS) { "post text" }
            require(m.streetNames.size <= MAX_STREET_NAMES) { "street names" }
            require(m.streetNames.all { it.length <= MAX_TEXT_CHARS }) { "street text" }
        }

        private fun place(raw: Any?): Place? {
            val o = raw as? JSONObject ?: return null
            val name = text(o.opt("n")) ?: return null
            val detail = if (o.has("d")) text(o.opt("d")) ?: return null else ""
            val lat = coordinate(o.opt("lat"), 90.0) ?: return null
            val lon = coordinate(o.opt("lon"), 180.0) ?: return null
            return Place(name, detail, GeoPoint(lat, lon))
        }

        private fun places(raw: Any?): List<Place>? {
            val arr = raw as? JSONArray ?: return null
            if (arr.length() > MAX_STOPS) return null
            val result = ArrayList<Place>(arr.length())
            for (i in 0 until arr.length()) result += place(arr.opt(i)) ?: return null
            return result
        }

        private fun options(raw: Any?): RouteOptions? {
            val o = raw as? JSONObject ?: return null
            val highways = o.opt("h") as? Boolean ?: return null
            val tolls = o.opt("t") as? Boolean ?: return null
            return RouteOptions(avoidHighways = highways, avoidTolls = tolls)
        }

        private fun route(o: JSONObject): Route? {
            val shapeRaw = o.opt("shape") as? JSONArray ?: return null
            if (shapeRaw.length() !in 2..MAX_SHAPE_POINTS) return null
            val shape = ArrayList<GeoPoint>(shapeRaw.length())
            for (i in 0 until shapeRaw.length()) {
                val p = shapeRaw.opt(i) as? JSONObject ?: return null
                val lat = coordinate(p.opt("lat"), 90.0) ?: return null
                val lon = coordinate(p.opt("lon"), 180.0) ?: return null
                shape += GeoPoint(lat, lon)
            }

            val maneuversRaw = o.opt("maneuvers") as? JSONArray ?: return null
            if (maneuversRaw.length() !in 1..MAX_MANEUVERS) return null
            val maneuvers = ArrayList<Maneuver>(maneuversRaw.length())
            for (i in 0 until maneuversRaw.length()) {
                val m = maneuversRaw.opt(i) as? JSONObject ?: return null
                maneuvers += maneuver(m) ?: return null
            }

            val distance = positiveNumber(o.opt("distance")) ?: return null
            val duration = nonNegativeNumber(o.opt("duration")) ?: return null
            val stopIndices = stopIndices(o.opt("stopIndices"), shape.size) ?: return null
            val route = Route(shape, maneuvers, distance, duration, stopIndices)
            if (!route.isUsable()) return null

            val expectedHash = text(o.opt("hash")) ?: return null
            if (expectedHash != checksum(route)) return null
            return route
        }

        private fun maneuver(o: JSONObject): Maneuver? {
            val type = int(o.opt("type")) ?: return null
            val instruction = text(o.opt("instr")) ?: return null
            val alert = text(o.opt("alert")) ?: return null
            val verbal = text(o.opt("verbal")) ?: return null
            val lengthM = nonNegativeNumber(o.opt("len")) ?: return null
            val beginIndex = int(o.opt("begin")) ?: return null
            val timeS = nonNegativeNumber(o.opt("time")) ?: return null
            val streetNames = streetNames(o.opt("street")) ?: return null
            val roundaboutExit = int(o.opt("exit")) ?: return null
            val postVerbal = text(o.opt("post")) ?: return null
            val isStop = o.opt("stop") as? Boolean ?: return null
            return Maneuver(type, instruction, alert, verbal, lengthM, beginIndex, timeS, streetNames, roundaboutExit, postVerbal, isStop)
        }

        private fun streetNames(raw: Any?): List<String>? {
            if (raw == null || raw == JSONObject.NULL) return emptyList()
            val arr = raw as? JSONArray ?: return null
            if (arr.length() > MAX_STREET_NAMES) return null
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) result += text(arr.opt(i)) ?: return null
            return result
        }

        private fun stopIndices(raw: Any?, shapeSize: Int): List<Int>? {
            val arr = raw as? JSONArray ?: return null
            if (arr.length() > MAX_STOPS) return null
            val result = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) {
                val index = int(arr.opt(i)) ?: return null
                if (index !in 0 until shapeSize) return null
                if (result.isNotEmpty() && index <= result.last()) return null
                result += index
            }
            return result
        }

        private fun coordinate(raw: Any?, limit: Double): Double? {
            val value = number(raw) ?: return null
            return value.takeIf { it in -limit..limit }
        }

        private fun number(raw: Any?): Double? {
            val n = raw as? Number ?: return null
            val value = n.toDouble()
            return value.takeIf { it.isFinite() }
        }

        private fun positiveNumber(raw: Any?): Double? = number(raw)?.takeIf { it > 0 }
        private fun nonNegativeNumber(raw: Any?): Double? = number(raw)?.takeIf { it >= 0 }

        private fun text(raw: Any?): String? {
            val value = raw as? String ?: return null
            return value.takeIf { it.length <= MAX_TEXT_CHARS }
        }

        private fun int(raw: Any?): Int? = when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        }

        /** Exact Long parsing; never routes a rounded Double back into a timestamp. */
        private fun timestamp(raw: Any?): Long? {
            val value = when (raw) {
                is Byte -> raw.toLong()
                is Short -> raw.toLong()
                is Int -> raw.toLong()
                is Long -> raw
                is BigInteger -> raw.takeIf { it.signum() >= 0 && it <= BigInteger.valueOf(Long.MAX_VALUE) }?.toLong()
                is BigDecimal -> raw.takeIf {
                    it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 && it <= BigDecimal.valueOf(Long.MAX_VALUE)
                }?.toLong()
                is Float -> exactDoubleTimestamp(raw.toDouble())
                is Double -> exactDoubleTimestamp(raw)
                else -> null
            }
            return value?.takeIf { it >= 0 }
        }

        private fun exactDoubleTimestamp(value: Double): Long? {
            if (!value.isFinite() || value < 0 || value >= 9_223_372_036_854_775_808.0 || value % 1.0 != 0.0) return null
            return value.toLong()
        }

        /** Cross-checks the saved trip against the geometry instead of trusting two independently parsed halves. */
        internal fun linkageValid(destination: Place, finalDestination: Place?, stops: List<Place>, route: Route): Boolean {
            if (finalDestination != null) {
                // Fuel leg: this geometry is only the pump leg; post-fuel stops belong to the next leg.
                if (route.stopIndices.isNotEmpty() || route.maneuvers.any { it.isStop }) return false
            } else {
                if (stops.size != route.stopIndices.size) return false
                if (route.stopIndices.any { index -> route.maneuvers.none { it.isStop && it.beginIndex == index } }) return false
                if (stops.indices.any { Geo.distance(stops[it].point, route.shape[route.stopIndices[it]]) > MAX_ENDPOINT_LINK_M }) return false
            }
            if (route.maneuvers.first().beginIndex != 0) return false
            if (route.maneuvers.zipWithNext().any { (a, b) -> b.beginIndex < a.beginIndex }) return false
            if (Geo.distance(route.shape.last(), destination.point) > MAX_ENDPOINT_LINK_M) return false
            val cumulative = route.cumulative.last()
            return abs(route.distanceM - cumulative) <= max(MIN_DISTANCE_SKEW_M, cumulative * MAX_DISTANCE_SKEW_RATIO)
        }

        /** Route-only corruption checksum; its scope excludes trip metadata/options and it is not authentication. */
        private fun checksum(route: Route): String {
            val canonical = buildString {
                route.shape.forEach { append(it.lat).append(',').append(it.lon).append(';') }
                append('|').append(route.distanceM).append('|').append(route.durationS).append('|')
                route.stopIndices.forEach { append(it).append(',') }
                route.maneuvers.forEach { m ->
                    append('|').append(m.type).append(',').append(m.beginIndex).append(',')
                    append(m.lengthM).append(',').append(m.timeS).append(',').append(m.roundaboutExit)
                    append(',').append(m.isStop).append(',').append(m.instruction).append(',')
                    append(m.alert).append(',').append(m.verbal).append(',').append(m.postVerbal)
                    m.streetNames.forEach { append(',').append(it) }
                }
            }
            val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }
}

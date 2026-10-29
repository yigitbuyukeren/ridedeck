package com.ridedeck.app.data

import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val detail: String, val point: GeoPoint)

data class Maneuver(
    val type: Int,
    val instruction: String,
    /** Spoken far in advance ("Sağa dönün"), may be empty. */
    val alert: String,
    /** Spoken close to the manoeuvre. */
    val verbal: String,
    val lengthM: Double,
    val beginIndex: Int,
)

data class Route(
    val shape: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val distanceM: Double,
    val durationS: Double,
) {
    /** cumulative[i] = metres from the start to shape[i]. */
    val cumulative: DoubleArray = DoubleArray(shape.size).also { c ->
        for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.distance(shape[i - 1], shape[i])
    }

    fun isUsable(): Boolean = shape.size >= 2 && shape.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 } &&
        maneuvers.isNotEmpty() && maneuvers.all { it.beginIndex in shape.indices } &&
        distanceM.isFinite() && distanceM > 0 && durationS.isFinite() && durationS >= 0 && cumulative.last() > 0
}

/** Valhalla `bicycle_type`: how much road bike the cyclist is on. */
enum class BikeType(val valhalla: String) { ROAD("Road"), CITY("Hybrid"), MOUNTAIN("Mountain") }

/**
 * Who is travelling and what they want to avoid. Everything off leaves Valhalla's own defaults for the
 * vehicle untouched (a bicycle always names its [bikeType]).
 */
data class RouteOptions(
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    val profile: VehicleProfile = VehicleProfile.MOTORCYCLE,
    val avoidHills: Boolean = false,
    val avoidBadSurfaces: Boolean = false,
    val bikeType: BikeType = BikeType.CITY,
) {
    /** Valhalla `costing_options`, or null when there is nothing to change. */
    fun costingOptions(): JSONObject? {
        val costing = profile.config.costing
        val options = JSONObject()
        if (profile == VehicleProfile.BICYCLE) {
            options.put("bicycle_type", bikeType.valhalla)
            if (avoidHills) options.put("use_hills", 0)
            if (avoidBadSurfaces) options.put("avoid_bad_surfaces", 1)
        } else {
            if (avoidHighways) options.put("use_highways", 0)
            if (avoidTolls) options.put("use_tolls", 0)
        }
        if (options.length() == 0) return null
        return JSONObject().put(costing, options)
    }
}

interface Routing {
    suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route>
    suspend fun search(query: String, near: GeoPoint?): Result<List<Place>>

    /** The address of a spot picked on the map; null when there is nothing worth naming. */
    suspend fun reverse(point: GeoPoint): Result<Place?> = Result.success(null)

    /** Other routes to the same place (best first), for "another route"; empty when the service has none. */
    suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<List<Route>> = Result.success(emptyList())
}

object PlaceNames {
    /** Nominatim `reverse` reply to a short name and a one-line detail, e.g. "Cadde 12" / "Fatih, İstanbul". */
    fun fromReverse(json: String, at: GeoPoint): Place? {
        val root = JSONObject(json)
        if (root.has("error")) return null
        val address = root.optJSONObject("address")
        val road = address?.optString("road").orEmpty()
        val number = address?.optString("house_number").orEmpty()
        val name = root.optString("name").ifBlank { listOf(road, number).filter { it.isNotBlank() }.joinToString(" ") }
        if (name.isBlank()) return null
        val detail = listOfNotNull(
            address?.optString("suburb")?.takeIf { it.isNotBlank() } ?: address?.optString("neighbourhood")?.takeIf { it.isNotBlank() },
            address?.optString("city")?.takeIf { it.isNotBlank() } ?: address?.optString("town")?.takeIf { it.isNotBlank() }
                ?: address?.optString("county")?.takeIf { it.isNotBlank() },
        ).filter { it != name }.joinToString(", ")
        return Place(name, detail, at)
    }
}

private const val METRES_PER_MILE = 1609.344

/** Valhalla (OSM) routing for the chosen vehicle and Nominatim geocoding, both public no-key services. */
class RouteRepository(
    /** Spoken and written route texts follow the rider's unit choice; lengths are converted back to metres. */
    private val miles: () -> Boolean = { false },
) : Routing {
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> = withContext(Dispatchers.IO) {
        safely("route") {
            val useMiles = miles()
            val body = requestBody(from, to, options, useMiles, alternates = 0)
            parseTrip(Http.postJson("https://valhalla1.openstreetmap.de/route", body.toString()), if (useMiles) METRES_PER_MILE else 1000.0)
        }
    }

    override suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<List<Route>> = withContext(Dispatchers.IO) {
        safely("alternatives") {
            val useMiles = miles()
            val body = requestBody(from, to, options, useMiles, alternates = 2)
            val reply = Http.postJson("https://valhalla1.openstreetmap.de/route", body.toString())
            val unit = if (useMiles) METRES_PER_MILE else 1000.0
            val json = JSONObject(reply)
            val out = mutableListOf(parseTripObject(json.getJSONObject("trip"), unit))
            json.optJSONArray("alternates")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("trip")?.let { out += parseTripObject(it, unit) }
            }
            out
        }
    }

    private fun requestBody(from: GeoPoint, to: GeoPoint, options: RouteOptions, useMiles: Boolean, alternates: Int): JSONObject {
        val body = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", from.lat).put("lon", from.lon))
                .put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", options.profile.config.costing)
            .put("directions_options", JSONObject().put("language", valhallaLanguage()).put("units", if (useMiles) "miles" else "kilometers"))
        if (alternates > 0) body.put("alternates", alternates)
        options.costingOptions()?.let { body.put("costing_options", it) }
        return body
    }

    /** Parses a Valhalla `route` or `trace_route` reply. */
    private fun parseTrip(reply: String, metresPerUnit: Double): Route = parseTripObject(JSONObject(reply).getJSONObject("trip"), metresPerUnit)

    private fun parseTripObject(trip: JSONObject, metresPerUnit: Double): Route {
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val maneuvers = leg.getJSONArray("maneuvers").let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Maneuver(
                    type = m.getInt("type"),
                    instruction = InstructionText.clean(m.optString("instruction")),
                    alert = InstructionText.clean(m.optString("verbal_transition_alert_instruction")),
                    verbal = InstructionText.clean(m.optString("verbal_pre_transition_instruction", m.optString("instruction"))),
                    lengthM = m.getDouble("length") * metresPerUnit,
                    beginIndex = m.getInt("begin_shape_index"),
                )
            }
        }
        val summary = trip.getJSONObject("summary")
        return Route(
            shape = Geo.decodePolyline(leg.getString("shape")),
            maneuvers = maneuvers,
            distanceM = summary.getDouble("length") * metresPerUnit,
            durationS = summary.getDouble("time"),
        )
    }

    override suspend fun reverse(point: GeoPoint): Result<Place?> = withContext(Dispatchers.IO) {
        safely("reverse") {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=${Locale.getDefault().language}" +
                "&lat=${"%.6f".format(Locale.US, point.lat)}&lon=${"%.6f".format(Locale.US, point.lon)}"
            PlaceNames.fromReverse(Http.get(url), point)
        }
    }

    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> = withContext(Dispatchers.IO) {
        safely("search") {
            val base = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=${Locale.getDefault().language}" +
                "&q=${URLEncoder.encode(query, "UTF-8")}"
            if (near == null) return@safely parsePlaces(Http.get(base))
            val view = "&viewbox=${near.lon - 1.0},${near.lat + 1.0},${near.lon + 1.0},${near.lat - 1.0}"
            // Places around the rider first; the whole country only when nothing is near (one request per second at most).
            val nearby = parsePlaces(Http.get("$base$view&bounded=1"))
            if (nearby.isNotEmpty()) return@safely nearby
            delay(SEARCH_GAP_MS)
            parsePlaces(Http.get("$base$view"))
        }
    }

    private fun parsePlaces(body: String): List<Place> {
        val items = JSONArray(body)
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val full = item.optString("display_name")
            val name = item.optString("name").ifBlank { full.substringBefore(",").trim() }
            if (name.isBlank()) return@mapNotNull null
            val detail = full.split(",").map { it.trim() }.drop(1).take(3).joinToString(", ")
            Place(name, detail, GeoPoint(item.getDouble("lat"), item.getDouble("lon")))
        }
    }

    private companion object {
        const val SEARCH_GAP_MS = 1_000L
    }
}

/** Valhalla only ships a fixed set of instruction languages; fall back to English. */
private fun valhallaLanguage(): String = when (Locale.getDefault().language) {
    "tr" -> "tr-TR"
    else -> "en-US"
}

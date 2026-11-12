package com.ridedeck.app.data

import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The cancellable HTTP calls used by the service repositories. Production uses [CancellableServiceHttp];
 * tests inject a transport so no request leaves the JVM.
 */
interface ServiceHttp {
    suspend fun get(url: String, connectMs: Int = 10_000, readMs: Int = 12_000): String

    suspend fun postJson(
        url: String,
        body: String,
        connectMs: Int = 10_000,
        readMs: Int = 12_000,
        emptyOn400: Boolean = false,
    ): String

    suspend fun postForm(url: String, body: String, connectMs: Int = 10_000, readMs: Int = 30_000): String
}

object CancellableServiceHttp : ServiceHttp {
    override suspend fun get(url: String, connectMs: Int, readMs: Int): String =
        Http.getCancellable(url, connectMs, readMs)

    override suspend fun postJson(
        url: String,
        body: String,
        connectMs: Int,
        readMs: Int,
        emptyOn400: Boolean,
    ): String = Http.postJsonCancellable(url, body, connectMs, readMs, emptyOn400)

    override suspend fun postForm(url: String, body: String, connectMs: Int, readMs: Int): String =
        Http.postFormCancellable(url, body, connectMs, readMs)
}

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
    /** Seconds Valhalla expects this manoeuvre's stretch to take; 0 when unknown. */
    val timeS: Double = 0.0,
    /** Road names after the manoeuvre ("Atatürk Cd."), may be empty. */
    val streetNames: List<String> = emptyList(),
    /** Roundabout exit to take (1-based), 0 when this is not a roundabout. */
    val roundaboutExit: Int = 0,
    /** Spoken right after the manoeuvre ("Continue for 2 km"), may be empty. */
    val postVerbal: String = "",
    /** The end of a leg before the last: reaching an intermediate stop, not the destination. */
    val isStop: Boolean = false,
)

data class Route(
    val shape: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val distanceM: Double,
    val durationS: Double,
    /** Shape indices of the intermediate stops, in order (empty for a plain A-to-B route). */
    val stopIndices: List<Int> = emptyList(),
) {
    /** Practically the same road: same length within 1% and the same middle point. */
    fun sameAs(other: Route): Boolean =
        abs(distanceM - other.distanceM) < distanceM * 0.01 && Geo.distance(shape[shape.size / 2], other.shape[other.shape.size / 2]) < 50.0

    /**
     * Seconds still to ride when [travelledM] metres are done, from the manoeuvre times Valhalla gave (the share of the
     * current stretch left plus every later stretch); proportional to the distance when it gave none.
     */
    fun remainingTimeS(travelledM: Double): Double {
        val remaining = (distanceM - travelledM).coerceAtLeast(0.0)
        val proportional = durationS * remaining / distanceM
        if (maneuvers.none { it.timeS > 0 }) return proportional
        val k = maneuvers.indexOfLast { cumulative[it.beginIndex.coerceIn(0, shape.lastIndex)] <= travelledM }.coerceAtLeast(0)
        val begin = cumulative[maneuvers[k].beginIndex.coerceIn(0, shape.lastIndex)]
        val end = maneuvers.getOrNull(k + 1)?.let { cumulative[it.beginIndex.coerceIn(0, shape.lastIndex)] } ?: cumulative.last()
        val share = if (end > begin) ((end - travelledM) / (end - begin)).coerceIn(0.0, 1.0) else 0.0
        return maneuvers[k].timeS * share + maneuvers.drop(k + 1).sumOf { it.timeS }
    }

    /** cumulative[i] = metres from the start to shape[i]. */
    val cumulative: DoubleArray = DoubleArray(shape.size).also { c ->
        for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.distance(shape[i - 1], shape[i])
    }

    fun isUsable(): Boolean = shape.size >= 2 && shape.all { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 } &&
        maneuvers.isNotEmpty() && maneuvers.all { it.beginIndex in shape.indices } &&
        distanceM.isFinite() && distanceM > 0 && durationS.isFinite() && durationS >= 0 && cumulative.last() > 0
}

/**
 * What the rider wants to avoid. Everything off leaves Valhalla's own motorcycle defaults untouched.
 */
data class RouteOptions(
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
) {
    /** Valhalla `costing_options`, or null when there is nothing to change. */
    fun costingOptions(): JSONObject? {
        val options = JSONObject()
        if (avoidHighways) options.put("use_highways", 0)
        if (avoidTolls) options.put("use_tolls", 0)
        if (options.length() == 0) return null
        return JSONObject().put(VehicleProfile.MOTORCYCLE.config.costing, options)
    }
}

interface Routing {
    suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route>

    /** Like [route], telling the service which way the rider is already heading so the route does not begin with a U-turn. */
    suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions, headingDeg: Double?): Result<Route> = route(from, to, options)
    suspend fun search(query: String, near: GeoPoint?): Result<List<Place>>

    /** The address of a spot picked on the map; null when there is nothing worth naming. */
    suspend fun reverse(point: GeoPoint): Result<Place?> = Result.success(null)

    /** One route through [points] (start, intermediate stops, destination); the default ignores the stops. */
    suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions): Result<Route> = route(points.first(), points.last(), options)

    /** [routeVia] with the rider's heading at the start. */
    suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions, headingDeg: Double?): Result<Route> = routeVia(points, options)

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

/** Valhalla (OSM) motorcycle routing and Nominatim geocoding, both public no-key services. */
class RouteRepository(
    private val http: ServiceHttp = CancellableServiceHttp,
    /** Spoken and written route texts follow the rider's unit choice; lengths are converted back to metres. */
    private val miles: () -> Boolean = { false },
) : Routing {
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> = route(from, to, options, null)

    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions, headingDeg: Double?): Result<Route> = withContext(Dispatchers.IO) {
        safely("route") {
            val useMiles = miles()
            val body = requestBody(listOf(from, to), options, useMiles, alternates = 0, headingDeg = headingDeg)
            parseTrip(http.postJson("https://valhalla1.openstreetmap.de/route", body.toString()), if (useMiles) METRES_PER_MILE else 1000.0)
        }
    }

    override suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions): Result<Route> = routeVia(points, options, null)

    override suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions, headingDeg: Double?): Result<Route> = withContext(Dispatchers.IO) {
        safely("routeVia") {
            val useMiles = miles()
            val body = requestBody(points, options, useMiles, alternates = 0, headingDeg = headingDeg)
            parseTrip(http.postJson("https://valhalla1.openstreetmap.de/route", body.toString()), if (useMiles) METRES_PER_MILE else 1000.0)
        }
    }

    override suspend fun alternatives(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<List<Route>> = withContext(Dispatchers.IO) {
        safely("alternatives") {
            val useMiles = miles()
            val body = requestBody(from, to, options, useMiles, alternates = 2)
            val reply = http.postJson("https://valhalla1.openstreetmap.de/route", body.toString())
            val unit = if (useMiles) METRES_PER_MILE else 1000.0
            val json = JSONObject(reply)
            val out = mutableListOf(parseTripObject(json.getJSONObject("trip"), unit))
            json.optJSONArray("alternates")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("trip")?.let { out += parseTripObject(it, unit) }
            }
            out
        }
    }

    private fun requestBody(from: GeoPoint, to: GeoPoint, options: RouteOptions, useMiles: Boolean, alternates: Int): JSONObject =
        requestBody(listOf(from, to), options, useMiles, alternates, null)

    /** The start carries the rider's heading (±[HEADING_TOLERANCE_DEG]) when known, so the first instruction is not a U-turn. */
    internal fun requestBody(points: List<GeoPoint>, options: RouteOptions, useMiles: Boolean, alternates: Int, headingDeg: Double?): JSONObject {
        val body = JSONObject()
            .put("locations", JSONArray().also { arr ->
                points.forEachIndexed { i, p ->
                    val loc = JSONObject().put("lat", p.lat).put("lon", p.lon)
                    if (i == 0 && headingDeg != null && headingDeg.isFinite()) {
                        loc.put("heading", ((headingDeg % 360 + 360) % 360).toInt()).put("heading_tolerance", HEADING_TOLERANCE_DEG)
                    }
                    arr.put(loc)
                }
            })
            .put("costing", VehicleProfile.MOTORCYCLE.config.costing)
            .put("directions_options", JSONObject().put("language", GuidanceLanguage.current()).put("units", if (useMiles) "miles" else "kilometers"))
        if (alternates > 0) body.put("alternates", alternates)
        options.costingOptions()?.let { body.put("costing_options", it) }
        return body
    }

    /** Parses a Valhalla `route` or `trace_route` reply. */
    private fun parseTrip(reply: String, metresPerUnit: Double): Route = parseTripObject(JSONObject(reply).getJSONObject("trip"), metresPerUnit)

    private fun parseTripObject(trip: JSONObject, metresPerUnit: Double): Route = TripParser.parse(trip, metresPerUnit)

    override suspend fun reverse(point: GeoPoint): Result<Place?> = withContext(Dispatchers.IO) {
        safely("reverse") {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=${Locale.getDefault().language}" +
                "&lat=${"%.6f".format(Locale.US, point.lat)}&lon=${"%.6f".format(Locale.US, point.lon)}"
            PlaceNames.fromReverse(http.get(url), point)
        }
    }

    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> = withContext(Dispatchers.IO) {
        safely("search") {
            val base = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=${Locale.getDefault().language}" +
                "&q=${URLEncoder.encode(query, "UTF-8")}"
            if (near == null) return@safely parsePlaces(http.get(base))
            val view = "&viewbox=${near.lon - 1.0},${near.lat + 1.0},${near.lon + 1.0},${near.lat - 1.0}"
            // Places around the rider first; the whole country only when nothing is near (one request per second at most).
            val nearby = parsePlaces(http.get("$base$view&bounded=1"))
            if (nearby.isNotEmpty()) return@safely nearby
            delay(SEARCH_GAP_MS)
            parsePlaces(http.get("$base$view"))
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

    companion object {
        private const val SEARCH_GAP_MS = 1_000L
        const val HEADING_TOLERANCE_DEG = 45
    }
}

/** Valhalla `trip` to a [Route]; the legs of a multi-stop trip are joined into one continuous shape. */
internal object TripParser {
    fun parse(trip: JSONObject, metresPerUnit: Double): Route {
        val legs = trip.getJSONArray("legs")
        val shape = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        val stops = ArrayList<Int>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val points = Geo.decodePolyline(leg.getString("shape"))
            // Each leg starts where the last one ended: drop its duplicate first point.
            val offset = if (l == 0) 0 else shape.size - 1
            shape += if (l == 0) points else points.drop(1)
            val arr = leg.getJSONArray("maneuvers")
            val last = l == legs.length() - 1
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val endOfLeg = i == arr.length() - 1
                maneuvers += Maneuver(
                    type = m.getInt("type"),
                    instruction = InstructionText.clean(m.optString("instruction")),
                    alert = InstructionText.clean(m.optString("verbal_transition_alert_instruction")),
                    verbal = InstructionText.clean(m.optString("verbal_pre_transition_instruction", m.optString("instruction"))),
                    lengthM = m.getDouble("length") * metresPerUnit,
                    beginIndex = m.getInt("begin_shape_index") + offset,
                    timeS = m.optDouble("time", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    streetNames = m.optJSONArray("street_names")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    roundaboutExit = m.optInt("roundabout_exit_count", 0),
                    postVerbal = InstructionText.clean(m.optString("verbal_post_transition_instruction")),
                    isStop = endOfLeg && !last,
                )
                if (endOfLeg && !last) stops += m.getInt("begin_shape_index") + offset
            }
        }
        val summary = trip.getJSONObject("summary")
        return Route(shape, maneuvers, summary.getDouble("length") * metresPerUnit, summary.getDouble("time"), stops)
    }
}

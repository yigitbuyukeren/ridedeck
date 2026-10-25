package com.ridedeck.app.data

import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FuelStation(val place: Place, val distanceM: Int, val kind: StopKind = StopKind.FUEL)

interface FuelFinder {
    suspend fun near(here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>>
    suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>>
}

object FuelMath {
    const val NEAR_RADIUS_M = 5000
    const val ROUTE_RADIUS_M = 500
    const val MAX_ROUTE_POINTS = 40
    private const val MIN_SPACING_M = 1500.0
    private const val LIMIT = 25
    private const val PER_KIND = 8

    private fun around(radiusM: Int, points: List<GeoPoint>): String =
        "(around:$radiusM," + points.joinToString(",") { "%.5f,%.5f".format(Locale.US, it.lat, it.lon) } + ")"

    /** What the motorcycle profile looks for; the default wherever no vehicle is given. */
    val FUEL_ONLY: List<StopSource> = VehicleProfile.MOTORCYCLE.config.stops

    // Nodes and ways only: relations make the public servers scan far more for no gain here.
    // One statement (and one cap) per kind: a single shared cap let a plentiful kind such as drinking water
    // use up all the slots and push repair stations and charging points off the answer.
    private fun query(around: String, sources: List<StopSource>): String {
        val cap = if (sources.size == 1) 80 else 40
        val parts = sources.joinToString("") {
            "(node[\"${it.key}\"=\"${it.value}\"]$around;way[\"${it.key}\"=\"${it.value}\"]$around;);out center $cap;"
        }
        return "[out:json][timeout:15];$parts"
    }

    fun nearQuery(here: GeoPoint, sources: List<StopSource> = FUEL_ONLY): String = query(around(NEAR_RADIUS_M, listOf(here)), sources)

    fun routeQuery(points: List<GeoPoint>, sources: List<StopSource> = FUEL_ONLY): String = query(around(ROUTE_RADIUS_M, points), sources)

    /** Which kind of stop an OSM element is, from its tags; fuel when it says nothing clearer. */
    fun kindOf(tags: JSONObject): StopKind = when {
        tags.optString("amenity") == "charging_station" -> StopKind.CHARGE
        tags.optString("amenity") == "bicycle_repair_station" -> StopKind.BIKE_REPAIR
        tags.optString("shop") == "bicycle" -> StopKind.BIKE_SHOP
        tags.optString("amenity") == "drinking_water" -> StopKind.WATER
        else -> StopKind.FUEL
    }

    /**
     * Thins the rest of a route down to a handful of points, no closer than [MIN_SPACING_M] and never
     * more than [MAX_ROUTE_POINTS], so one Overpass request covers the road ahead.
     */
    fun samplePoints(shape: List<GeoPoint>, cumulative: DoubleArray, fromIndex: Int): List<GeoPoint> {
        if (shape.isEmpty()) return emptyList()
        val start = fromIndex.coerceIn(0, shape.lastIndex)
        val total = cumulative[shape.lastIndex] - cumulative[start]
        val spacing = maxOf(MIN_SPACING_M, total / MAX_ROUTE_POINTS)
        val out = ArrayList<GeoPoint>()
        var nextAt = 0.0
        for (i in start..shape.lastIndex) {
            if (cumulative[i] - cumulative[start] >= nextAt) {
                out += shape[i]
                nextAt += spacing
            }
        }
        if (out.last() != shape.last()) out += shape.last()
        return out.take(MAX_ROUTE_POINTS + 1)
    }

    /** Stations nearest to [from] first. Skips mapping mistakes such as hotels tagged as fuel. */
    fun parse(json: String, from: GeoPoint): List<FuelStation> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val found = ArrayList<FuelStation>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            if (tags.has("tourism") || tags.optString("disused") == "yes") continue
            val point = when {
                e.has("lat") -> GeoPoint(e.getDouble("lat"), e.getDouble("lon"))
                e.optJSONObject("center") != null -> e.getJSONObject("center").let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }
                else -> continue
            }
            val brand = tags.optString("brand").ifBlank { tags.optString("operator") }
            val name = tags.optString("name").ifBlank { brand }
            // Unnamed stations get a generic label from the UI layer (blank name).
            val detail = brand.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }.orEmpty()
            found += FuelStation(Place(name, detail, point), Geo.distance(from, point).toInt(), kindOf(tags))
        }
        return mix(found.sortedBy { it.distanceM }.distinctBy { it.place.point.lat to it.place.point.lon }).take(LIMIT)
    }

    /**
     * With several kinds of stop on one list (car: fuel and charging, bicycle: repair, shop, water) a plentiful
     * kind such as drinking water must not push the rare ones off it: each kind keeps its [PER_KIND] nearest.
     */
    /** Two answers about the same area, merged: nearest first, one entry per spot, a plentiful kind kept in check. */
    fun combine(a: List<FuelStation>, b: List<FuelStation>): List<FuelStation> =
        mix((a + b).sortedBy { it.distanceM }.distinctBy { it.place.point.lat to it.place.point.lon }).take(LIMIT)

    fun mix(sorted: List<FuelStation>): List<FuelStation> {
        if (sorted.map { it.kind }.distinct().size < 2) return sorted
        val seen = HashMap<StopKind, Int>()
        return sorted.filter { seen.merge(it.kind, 1, Int::plus)!! <= PER_KIND }
    }

    /** South, west, north, east. */
    data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    fun boundingBox(points: List<GeoPoint>, marginDeg: Double): Box = Box(
        south = points.minOf { it.lat } - marginDeg,
        west = points.minOf { it.lon } - marginDeg,
        north = points.maxOf { it.lat } + marginDeg,
        east = points.maxOf { it.lon } + marginDeg,
    )

    /** Nominatim answers "fuel" inside a box in well under a second, which the Overpass mirrors often cannot. */
    fun nominatimUrl(box: Box, query: String = "fuel"): String =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&q=${URLEncoder.encode(query, "UTF-8")}&bounded=1&limit=40" +
            "&viewbox=${"%.5f,%.5f,%.5f,%.5f".format(Locale.US, box.west, box.north, box.east, box.south)}"

    fun parseNominatim(json: String, from: GeoPoint, sources: List<StopSource> = FUEL_ONLY): List<FuelStation> {
        val items = org.json.JSONArray(json)
        val found = ArrayList<FuelStation>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val source = sources.firstOrNull { it.nominatimType == item.optString("type") } ?: continue
            val point = GeoPoint(item.getDouble("lat"), item.getDouble("lon"))
            found += FuelStation(Place(item.optString("name"), "", point), Geo.distance(from, point).toInt(), source.kind)
        }
        return found.sortedBy { it.distanceM }.take(LIMIT)
    }

    /** Keeps the stations within [maxM] of the line through [route]. */
    fun alongRoute(stations: List<FuelStation>, route: List<GeoPoint>, maxM: Double = ROUTE_RADIUS_M * 1.4): List<FuelStation> {
        if (route.size < 2) return stations.filter { s -> route.any { Geo.distance(it, s.place.point) <= maxM } }
        return stations.filter { s ->
            (0 until route.lastIndex).any { Geo.project(s.place.point, route[it], route[it + 1]).distanceM <= maxM }
        }
    }
}


/**
 * Fuel stations from two free sources. The volunteer-run Overpass servers take turns being down or slow,
 * so a search asks all of them at once and takes the first good answer; Nominatim is the quick fallback
 * (and the first choice around the rider). Searches are rare, explicit taps.
 */
class FuelRepository : FuelFinder {
    private val mirrors = listOf(
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** Around the rider: Nominatim first (fast), the Overpass mirrors when it finds nothing or fails. */
    override suspend fun near(here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> = withContext(Dispatchers.IO) {
        safely("fuel") {
            val viaNominatim = try {
                nominatim(FuelMath.boundingBox(listOf(here), NEAR_BOX_DEG), here, sources)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RideDeck", "nominatim fuel failed (${e.javaClass.simpleName})")
                emptyList()
            }
            // Nominatim may know only some of the kinds (it found drinking water but no repair station): the
            // Overpass servers are asked for the kinds it left out and the two answers are merged.
            val found = viaNominatim.map { it.kind }.toSet()
            val missing = sources.filter { it.kind !in found }
            if (missing.isEmpty()) return@safely viaNominatim
            val extra = try {
                FuelMath.parse(firstAnswer(FuelMath.nearQuery(here, missing)), here)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (viaNominatim.isEmpty()) throw e
                Log.w("RideDeck", "overpass for missing fuel kinds failed (${e.javaClass.simpleName})")
                emptyList()
            }
            FuelMath.combine(viaNominatim, extra)
        }
    }

    /** One Nominatim search per kind of stop; the kinds are merged, nearest first. */
    private suspend fun nominatim(box: FuelMath.Box, here: GeoPoint, sources: List<StopSource>): List<FuelStation> =
        FuelMath.mix(
            sources.flatMap { FuelMath.parseNominatim(Http.get(FuelMath.nominatimUrl(box, it.nominatimQuery)), here, listOf(it)) }
                .sortedBy { it.distanceM },
        )

    /** Along the road ahead: the exact Overpass corridor first, a Nominatim box filtered to the corridor otherwise. */
    override suspend fun along(route: List<GeoPoint>, here: GeoPoint, sources: List<StopSource>): Result<List<FuelStation>> = withContext(Dispatchers.IO) {
        safely("fuel") {
            try {
                FuelMath.parse(firstAnswer(FuelMath.routeQuery(route, sources)), here)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // About the next 20 km: a box any wider returns whatever Nominatim ranks first, not what is ahead.
                val ahead = route.take(FALLBACK_POINTS)
                val box = FuelMath.boundingBox(ahead, ROUTE_BOX_MARGIN_DEG)
                FuelMath.alongRoute(nominatim(box, here, sources), ahead)
            }
        }
    }

    private suspend fun firstAnswer(body: String): String = firstAnswerOf(body, mirrors)

    private companion object {
        const val NEAR_BOX_DEG = 0.03
        const val ROUTE_BOX_MARGIN_DEG = 0.01
        const val FALLBACK_POINTS = 14
    }
}

/** Asks every server at once with the same Overpass query and returns the first good reply. */
private suspend fun firstAnswerOf(query: String, mirrors: List<String>): String = coroutineScope {
    val body = "data=" + URLEncoder.encode(query, "UTF-8")
    val results = Channel<Result<String>>(mirrors.size)
    val requests = mirrors.map { url ->
        launch(Dispatchers.IO) {
            val outcome = try {
                Result.success(Http.postForm(url, body, connectMs = 5_000, readMs = 10_000))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RideDeck", "overpass mirror failed (${e.javaClass.simpleName})")
                Result.failure(e)
            }
            results.send(outcome)
        }
    }
    var lastFailure: Throwable? = null
    repeat(mirrors.size) {
        val outcome = results.receive()
        if (outcome.isSuccess) {
            requests.forEach { it.cancel() }
            return@coroutineScope outcome.getOrThrow()
        }
        lastFailure = outcome.exceptionOrNull()
    }
    throw lastFailure ?: IllegalStateException("no Overpass mirror")
}

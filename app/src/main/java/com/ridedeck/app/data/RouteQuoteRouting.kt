package com.ridedeck.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Proposed central body builder; RouteRepository should expose one implementation, no copied JSON. */
internal fun interface RouteRequestBodyBuilder {
    fun build(
        points: List<GeoPoint>,
        options: RouteOptions,
        useMiles: Boolean,
        alternates: Int,
        headingDeg: Double?,
    ): JSONObject
}

/** Actual central body builder: reuses RouteRepository's existing internal builder, no copied JSON. */
internal fun routeRequestBodyBuilder(repo: RouteRepository = RouteRepository()): RouteRequestBodyBuilder =
    RouteRequestBodyBuilder { points, options, useMiles, alternates, headingDeg ->
        repo.requestBody(points, options, useMiles, alternates, headingDeg)
    }

/**
 * Additive, not wired yet: a Routing adapter for the selected-station quote that uses the
 * existing cancellable OkHttp path instead of RouteRepository's blocking call. The request body
 * comes from an injected central builder; this class does not reach into private repository state
 * or duplicate the route JSON.
 */
internal class RouteQuoteRouting(
    private val requestBody: RouteRequestBodyBuilder,
    private val delegate: Routing,
    private val miles: () -> Boolean,
) : Routing {
    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> =
        route(from, to, options, null)

    override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions, headingDeg: Double?): Result<Route> =
        routeVia(listOf(from, to), options, headingDeg)

    override suspend fun routeVia(points: List<GeoPoint>, options: RouteOptions): Result<Route> =
        routeVia(points, options, null)

    override suspend fun routeVia(
        points: List<GeoPoint>,
        options: RouteOptions,
        headingDeg: Double?,
    ): Result<Route> = withContext(Dispatchers.IO) {
        safely("route-quote") {
            val useMiles = miles()
            val body = requestBody.build(points, options, useMiles, alternates = 0, headingDeg)
            val reply = Http.postJsonCancellable(VALHALLA_ROUTE, body.toString())
            TripParser.parse(
                JSONObject(reply).getJSONObject("trip"),
                if (useMiles) METRES_PER_MILE else 1000.0,
            )
        }
    }

    override suspend fun search(query: String, near: GeoPoint?): Result<List<Place>> = delegate.search(query, near)

    override suspend fun reverse(point: GeoPoint): Result<Place?> = delegate.reverse(point)

    private companion object {
        const val VALHALLA_ROUTE = "https://valhalla1.openstreetmap.de/route"
        const val METRES_PER_MILE = 1609.344
    }
}

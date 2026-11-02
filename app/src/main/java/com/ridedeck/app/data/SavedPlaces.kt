package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject

enum class PlaceKind { HOME, WORK, FAVORITE }

data class SavedPlace(val kind: PlaceKind, val place: Place)

/** Pure list rules for the saved places, so they can be unit tested without Android. */
object SavedPlaces {
    const val MAX_FAVORITES = 20
    private const val SAME_SPOT_M = 30.0

    /**
     * Home and Work are single slots (a new one replaces the old one). Favorites are unlimited
     * up to [MAX_FAVORITES]; saving a spot that is already a favorite just keeps it once. Order: Home,
     * Work, then favorites in the order they were added.
     */
    fun put(list: List<SavedPlace>, new: SavedPlace): List<SavedPlace> {
        val rest = when (new.kind) {
            PlaceKind.HOME, PlaceKind.WORK -> list.filter { it.kind != new.kind }
            PlaceKind.FAVORITE -> list.filterNot {
                it.kind == PlaceKind.FAVORITE && Geo.distance(it.place.point, new.place.point) < SAME_SPOT_M
            }
        }
        val favorites = rest.filter { it.kind == PlaceKind.FAVORITE }
        val trimmed = if (new.kind == PlaceKind.FAVORITE && favorites.size >= MAX_FAVORITES) {
            rest - favorites.first()
        } else rest
        return sorted(trimmed + new)
    }

    fun remove(list: List<SavedPlace>, item: SavedPlace): List<SavedPlace> = list.filterNot { it == item }

    private fun sorted(list: List<SavedPlace>): List<SavedPlace> =
        list.withIndex().sortedWith(compareBy({ it.value.kind.ordinal }, { it.index })).map { it.value }

    fun toJson(list: List<SavedPlace>): String = JSONArray().also { arr ->
        list.forEach {
            arr.put(
                JSONObject()
                    .put("kind", it.kind.name)
                    .put("name", it.place.name)
                    .put("detail", it.place.detail)
                    .put("lat", it.place.point.lat)
                    .put("lon", it.place.point.lon)
            )
        }
    }.toString()

    /** Tolerant on purpose: a damaged file yields what could be read, never a crash at start-up. */
    fun fromJson(json: String?): List<SavedPlace> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { PlaceKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                SavedPlace(kind, Place(o.optString("name"), o.optString("detail"), GeoPoint(o.getDouble("lat"), o.getDouble("lon"))))
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}

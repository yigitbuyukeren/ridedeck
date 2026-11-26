package com.ridedeck.app.data

import org.json.JSONArray

/** The apps the driver pinned to the shelf, in order; pure list handling so it can be tested without Android. */
object PinnedApps {
    fun fromJson(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toJson(list: List<String>): String = JSONArray(list).toString()

    fun add(list: List<String>, pkg: String): List<String> = if (pkg in list) list else list + pkg

    fun remove(list: List<String>, pkg: String): List<String> = list - pkg

    /** Moves one step earlier (`delta` < 0) or later (`delta` > 0); the ends stay put. */
    fun move(list: List<String>, pkg: String, delta: Int): List<String> {
        val from = list.indexOf(pkg)
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }
}

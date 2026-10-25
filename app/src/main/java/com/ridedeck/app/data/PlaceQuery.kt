package com.ridedeck.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Typo tolerance for place searches, pure logic: Turkish letter variants, a fuzzy match against places the rider
 * already knows (saved, recent) and a short list of well-known names, and the candidate queries worth sending to the
 * search service when the typed text finds nothing. Nothing here talks to the network.
 */
object PlaceQuery {
    /** A name the rider (or the built-in list) knows and how close it is to the typed text, 0..1. */
    data class Match(val name: String, val score: Double)

    const val SUGGEST_THRESHOLD = 0.75
    const val STRONG_THRESHOLD = 0.9
    const val MAX_SHORT_CANDIDATES = 6
    const val MAX_LONG_CANDIDATES = 3
    /** Up to this many letters (after folding) a query counts as short and gets edit variants. */
    private const val SHORT_QUERY = 10

    private val TR = Locale("tr", "TR")

    /** Lower case with Turkish rules, Turkish letters folded to plain ones, one space between words. */
    fun normalize(text: String): String {
        val lower = text.replace('İ', 'i').replace('I', 'ı').lowercase(TR)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            sb.append(
                when (c) {
                    'ı' -> 'i'; 'ş' -> 's'; 'ğ' -> 'g'; 'ü' -> 'u'; 'ö' -> 'o'; 'ç' -> 'c'
                    'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                    '\'', '’', '`', '.', ',' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** "Taksim'e" -> "Taksim": the case ending after an apostrophe is not part of the name. */
    fun stripSuffix(text: String): String {
        val cut = text.indexOfFirst { it == '\'' || it == '’' }
        return if (cut > 0) text.substring(0, cut).trim() else text.trim()
    }

    /** Edit distance counting a swap of two neighbouring letters as one edit. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, d[i - 2][j - 2] + 1)
            d[i][j] = best
        }
        return d[a.length][b.length]
    }

    /** 1 for the same text after normalizing, falling with the number of edits. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        val longest = maxOf(x.length, y.length)
        if (longest == 0) return 1.0
        return 1.0 - distance(x, y).toDouble() / longest
    }

    /** The known names closest to [query], best first, at least [threshold] close; a name spelled exactly like the query is not a suggestion. */
    fun suggest(query: String, known: Collection<String>, threshold: Double = SUGGEST_THRESHOLD, limit: Int = 3): List<Match> {
        val q = stripSuffix(query)
        if (normalize(q).length < 3) return emptyList()
        return known.asSequence()
            .filter { it.isNotBlank() && it != q }
            .map { Match(it, similarity(q, it)) }
            .filter { it.score >= threshold }
            .distinctBy { normalize(it.name) }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** Saved or recent places that match very closely, for showing at once without any network call. */
    fun strongLocal(query: String, places: List<Place>): List<Place> {
        val q = stripSuffix(query)
        if (normalize(q).length < 3) return emptyList()
        return places.filter { similarity(q, it.name) >= STRONG_THRESHOLD }.distinctBy { normalize(it.name) + it.point }
    }

    /**
     * Other spellings worth searching when [query] found nothing, most promising first: without the case ending,
     * spelled like a known name, Turkish letters folded, a doubled letter reduced, two neighbouring letters swapped.
     * Short queries get up to [MAX_SHORT_CANDIDATES]; long ones only the ending and letter variants ([MAX_LONG_CANDIDATES]).
     */
    fun candidates(query: String, known: Collection<String>): List<String> {
        val q = query.trim()
        val stripped = stripSuffix(q)
        val folded = normalize(stripped)
        if (folded.length < 2) return emptyList()
        val short = folded.length <= SHORT_QUERY
        val out = LinkedHashSet<String>()
        if (stripped != q) out += stripped
        if (short) suggest(stripped, known).forEach { out += it.name }
        else known.firstOrNull { normalize(it) == folded && it != stripped }?.let { out += it }
        if (folded != stripped.lowercase(TR)) out += folded
        if (short) {
            Regex("(.)\\1").replace(folded) { it.groupValues[1] }.takeIf { it != folded }?.let { out += it }
            for (i in 0 until folded.length - 1) {
                if (folded[i] == folded[i + 1]) continue
                out += folded.substring(0, i) + folded[i + 1] + folded[i] + folded.substring(i + 2)
            }
        }
        out.remove(q)
        return out.take(if (short) MAX_SHORT_CANDIDATES else MAX_LONG_CANDIDATES)
    }

    /** Provinces, Istanbul districts and a few landmarks: the names people mistype most when talking or typing quickly. */
    val WELL_KNOWN: List<String> = listOf(
        "Adana", "Adıyaman", "Afyonkarahisar", "Ağrı", "Aksaray", "Amasya", "Ankara", "Antalya", "Ardahan", "Artvin", "Aydın",
        "Balıkesir", "Bartın", "Batman", "Bayburt", "Bilecik", "Bingöl", "Bitlis", "Bolu", "Burdur", "Bursa", "Çanakkale",
        "Çankırı", "Çorum", "Denizli", "Diyarbakır", "Düzce", "Edirne", "Elazığ", "Erzincan", "Erzurum", "Eskişehir",
        "Gaziantep", "Giresun", "Gümüşhane", "Hakkari", "Hatay", "Iğdır", "Isparta", "İstanbul", "İzmir", "Kahramanmaraş",
        "Karabük", "Karaman", "Kars", "Kastamonu", "Kayseri", "Kilis", "Kırıkkale", "Kırklareli", "Kırşehir", "Kocaeli",
        "Konya", "Kütahya", "Malatya", "Manisa", "Mardin", "Mersin", "Muğla", "Muş", "Nevşehir", "Niğde", "Ordu", "Osmaniye",
        "Rize", "Sakarya", "Samsun", "Siirt", "Sinop", "Sivas", "Şanlıurfa", "Şırnak", "Tekirdağ", "Tokat", "Trabzon",
        "Tunceli", "Uşak", "Van", "Yalova", "Yozgat", "Zonguldak",
        "Adalar", "Arnavutköy", "Ataşehir", "Avcılar", "Bağcılar", "Bahçelievler", "Bakırköy", "Başakşehir", "Bayrampaşa",
        "Beşiktaş", "Beykoz", "Beylikdüzü", "Beyoğlu", "Büyükçekmece", "Çatalca", "Çekmeköy", "Esenler", "Esenyurt", "Eyüpsultan",
        "Fatih", "Gaziosmanpaşa", "Güngören", "Kadıköy", "Kağıthane", "Kartal", "Küçükçekmece", "Maltepe", "Pendik",
        "Sancaktepe", "Sarıyer", "Silivri", "Sultanbeyli", "Sultangazi", "Şile", "Şişli", "Tuzla", "Ümraniye", "Üsküdar",
        "Zeytinburnu", "Taksim", "Sultanahmet", "Galata", "Levent", "Ortaköy", "Eminönü", "Karaköy", "Bebek", "Moda",
        "Alsancak", "Kızılay", "Bodrum", "Marmaris", "Fethiye", "Alanya", "Kuşadası", "Çeşme", "Cappadocia", "Kapadokya",
    )
}

/** The last places the rider searched for and chose, newest first, kept on the device only. */
object RecentPlaces {
    const val MAX = 10
    private const val SAME_SPOT_M = 30.0

    fun put(list: List<Place>, place: Place): List<Place> {
        val rest = list.filterNot {
            PlaceQuery.normalize(it.name) == PlaceQuery.normalize(place.name) || Geo.distance(it.point, place.point) < SAME_SPOT_M
        }
        return (listOf(place) + rest).take(MAX)
    }

    fun toJson(list: List<Place>): String = JSONArray().also { arr ->
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("detail", it.detail).put("lat", it.point.lat).put("lon", it.point.lon))
        }
    }.toString()

    /** Tolerant on purpose: a damaged file yields what could be read, never a crash at start-up. */
    fun fromJson(json: String?): List<Place> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Place(o.optString("name"), o.optString("detail"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")))
            }.filter { it.name.isNotBlank() }.take(MAX)
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}

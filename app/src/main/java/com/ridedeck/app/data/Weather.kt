package com.ridedeck.app.data

import android.os.SystemClock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the dashboard needs from one forecast: now, and whether rain is about to start. */
data class WeatherSnapshot(
    val tempC: Int,
    val code: Int,
    /** Minutes until rain starts within the next hour, 0 when it is already raining, null when dry. */
    val rainInMin: Int?,
    /** Strongest gust right now, km/h. */
    val windKmh: Int = 0,
) {
    val iceRisk: Boolean get() = WeatherMath.iceRisk(tempC)
}

enum class WeatherAlert { RAIN, ICE }

object WeatherMath {
    private const val SLOT_MIN = 15
    private const val HORIZON_MIN = 60

    /** One slot at least this wet counts as rain; light drizzle below it is ignored. */
    private const val SLOT_MM = 0.2
    private const val HORIZON_MM = 0.4
    const val ICE_C = 3

    fun iceRisk(tempC: Int): Boolean = tempC <= ICE_C

    /**
     * Open-Meteo reply for `current=temperature_2m,weather_code` and `minutely_15=precipitation`
     * (local times; `utc_offset_seconds` converts them). Null when the reply lacks the current block.
     */
    fun parse(json: String, nowMs: Long): WeatherSnapshot? {
        val root = JSONObject(json)
        val current = root.optJSONObject("current") ?: return null
        if (!current.has("temperature_2m")) return null
        val offset = ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
        val slots = root.optJSONObject("minutely_15")
        var rainIn: Int? = null
        if (slots != null) {
            val times = slots.getJSONArray("time")
            val mm = slots.getJSONArray("precipitation")
            val wet = ArrayList<Pair<Int, Double>>()
            for (i in 0 until minOf(times.length(), mm.length())) {
                val startMs = LocalDateTime.parse(times.getString(i)).toEpochSecond(offset) * 1000
                val minutesAway = ((startMs - nowMs) / 60_000).toInt()
                // Slots that already ended are history, slots past the horizon are too far to warn about.
                if (minutesAway + SLOT_MIN <= 0 || minutesAway > HORIZON_MIN) continue
                wet += minutesAway.coerceAtLeast(0) to mm.optDouble(i, 0.0)
            }
            val heavy = wet.firstOrNull { it.second >= SLOT_MM }
            val total = wet.sumOf { it.second }
            rainIn = when {
                heavy != null -> heavy.first
                total >= HORIZON_MM -> wet.first { it.second > 0.0 }.first
                else -> null
            }
        }
        return WeatherSnapshot(
            tempC = Math.round(current.getDouble("temperature_2m")).toInt(),
            code = current.optInt("weather_code", 0),
            rainInMin = rainIn,
            windKmh = Math.round(current.optDouble("wind_gusts_10m", 0.0)).toInt(),
        )
    }

    /** WMO weather code to an emoji the dashboard can draw anywhere. */
    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1 -> "🌤️"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun url(lat: Double, lon: Double): String =
        // Two decimals is roughly a kilometre: plenty for a forecast, and less precise than the real fix.
        "https://api.open-meteo.com/v1/forecast?latitude=${"%.2f".format(Locale.US, lat)}&longitude=${"%.2f".format(Locale.US, lon)}" +
            "&current=temperature_2m,weather_code,wind_gusts_10m&minutely_15=precipitation&forecast_minutely_15=8&timezone=auto"
}

/** The forecast lookup, injectable so freshness and reply ordering can be tested without a server. */
interface WeatherLookup {
    suspend fun fetch(at: GeoPoint, nowMs: Long = System.currentTimeMillis()): Result<WeatherSnapshot>
}

class WeatherRepository(private val http: ServiceHttp = CancellableServiceHttp) : WeatherLookup {
    override suspend fun fetch(at: GeoPoint, nowMs: Long): Result<WeatherSnapshot> =
        withContext(Dispatchers.IO) {
            safely("weather") {
                WeatherMath.parse(http.get(WeatherMath.url(at.lat, at.lon), connectMs = 8_000, readMs = 10_000), nowMs)
                    ?: error("no current weather in reply")
            }
        }
}

/**
 * Keeps [snapshot] fresh while [enabled]: a new forecast every [REFRESH_MS] or after moving
 * [REFRESH_MOVE_M]. [onAlert] fires once per rain spell and once per cold spell, not on every refresh.
 */
class WeatherController(
    private val scope: CoroutineScope,
    private val location: StateFlow<LocationState>,
    private val enabled: StateFlow<Boolean>,
    private val repo: WeatherLookup = WeatherRepository(),
    /** Monotonic milliseconds that also advance in deep sleep, so snapshot age survives wall-clock changes. */
    private val elapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onAlert: (WeatherAlert, WeatherSnapshot) -> Unit,
) {
    private val _snapshot = MutableStateFlow<WeatherSnapshot?>(null)
    val snapshot: StateFlow<WeatherSnapshot?> = _snapshot

    private var lastFetchAt = 0L
    private var lastFetchPoint: GeoPoint? = null
    private var rainAnnounced = false
    private var iceAnnounced = false
    private var snapshotAtMs = 0L
    /** Bumped when weather is disabled, so a reply from the old session can never be shown or announced. */
    private var generation = 0L
    private var inFlight = false
    private var started = false

    init {
        scope.launch { enabled.collect { if (!it) clear() } }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                tick()
                // Until the first forecast arrives, look again quickly so the chip shows up right after the first fix.
                delay(if (_snapshot.value == null) FIRST_TICK_MS else TICK_MS)
            }
        }
    }

    private fun tick() {
        if (!enabled.value) {
            clear()
            return
        }
        expireIfNeeded()
        val fix = location.value
        if (fix.fixAtMs == 0L) return
        val here = GeoPoint(fix.lat, fix.lon)
        val now = elapsedMs()
        val moved = lastFetchPoint?.let { Geo.distance(it, here) } ?: Double.MAX_VALUE
        if (now - lastFetchAt < REFRESH_MS && moved < REFRESH_MOVE_M) return
        refresh(here, now)
    }

    private fun refresh(here: GeoPoint, now: Long) {
        if (inFlight) return
        val requestGeneration = generation
        val requestedAt = now
        inFlight = true
        scope.launch {
            val result = repo.fetch(here)
            // Weather was turned off and on again while the request was on the wire.
            if (requestGeneration != generation) return@launch
            inFlight = false
            val completedAt = elapsedMs()
            // A backwards clock, a reply older than the snapshot lifetime, or a disabled switch is not current weather.
            val requestAge = completedAt - requestedAt
            if (requestAge < 0 || requestAge > SNAPSHOT_TTL_MS || !enabled.value) return@launch
            result.onSuccess { s ->
                _snapshot.value = s
                snapshotAtMs = requestedAt
                lastFetchAt = completedAt
                lastFetchPoint = here
                announce(s)
            }
            // A failed fetch keeps the old snapshot until its lifetime ends, then tries again on the next tick.
        }
    }

    private fun expireIfNeeded() {
        if (_snapshot.value == null) return
        val age = elapsedMs() - snapshotAtMs
        if (age < 0 || age > SNAPSHOT_TTL_MS) clearSnapshot()
    }

    /** Weather is off: forget the reading and invalidate any reply still in flight, without delaying the tick loop. */
    private fun clear() {
        generation++
        inFlight = false
        lastFetchAt = 0L
        lastFetchPoint = null
        clearSnapshot()
    }

    private fun clearSnapshot() {
        _snapshot.value = null
        snapshotAtMs = 0L
        rainAnnounced = false
        iceAnnounced = false
    }

    private fun announce(s: WeatherSnapshot) {
        if (s.rainInMin == null) rainAnnounced = false
        else if (!rainAnnounced) { rainAnnounced = true; onAlert(WeatherAlert.RAIN, s) }
        if (!s.iceRisk) iceAnnounced = false
        else if (!iceAnnounced) { iceAnnounced = true; onAlert(WeatherAlert.ICE, s) }
    }

    companion object {
        /** A forecast is no longer shown as current after this long, even if every refresh since has failed. */
        const val SNAPSHOT_TTL_MS = 30 * 60_000L
        private const val TICK_MS = 60_000L
        private const val FIRST_TICK_MS = 5_000L
        private const val REFRESH_MS = 15 * 60_000L
        private const val REFRESH_MOVE_M = 10_000.0
    }
}

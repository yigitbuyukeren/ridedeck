package com.ridedeck.app.data

import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

/** Admission shape for one public provider host: how many calls may overlap and how far apart they start. */
data class ProviderPolicy(val maxConcurrent: Int, val minSpacingMs: Long) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        require(minSpacingMs >= 0) { "minSpacingMs must not be negative" }
    }
}

/**
 * Per-installation load shaping for the keyless public services.
 *
 * These numbers are deliberately conservative starting points. They shape this app's own traffic;
 * they do NOT prove aggregate provider compliance (for example Nominatim's one-request-per-second
 * policy across every client of that server). The values must still be checked against each
 * provider's current terms before being treated as a guarantee.
 */
object ProviderPolicies {
    val NOMINATIM = ProviderPolicy(maxConcurrent = 1, minSpacingMs = 1_000L)
    val VALHALLA = ProviderPolicy(maxConcurrent = 2, minSpacingMs = 1_000L)
    val OVERPASS = ProviderPolicy(maxConcurrent = 2, minSpacingMs = 0L)
    val OPEN_METEO = ProviderPolicy(maxConcurrent = 1, minSpacingMs = 0L)
    val DEFAULT = ProviderPolicy(maxConcurrent = 1, minSpacingMs = 0L)

    fun forHost(host: String?): ProviderPolicy = when (host?.lowercase()) {
        "nominatim.openstreetmap.org" -> NOMINATIM
        "valhalla1.openstreetmap.de" -> VALHALLA
        "overpass-api.de", "overpass.kumi.systems", "overpass.private.coffee" -> OVERPASS
        "api.open-meteo.com" -> OPEN_METEO
        else -> DEFAULT
    }
}

/** Host extraction shared by the central transport; a malformed URL falls back to the conservative default. */
internal fun providerHost(url: String): String? =
    runCatching { URI(url).host }.getOrNull()?.lowercase()

/**
 * Central admission for provider calls. A [Semaphore] bounds concurrency and a [Mutex] serialises the
 * start of calls that need spacing, so two Valhalla calls may overlap while still starting a second
 * apart. Waiting is cancellable: no permit or mutex is leaked when the caller is cancelled, and a
 * cancelled spacing wait does not reserve a future slot.
 *
 * [nowMs] and [delayMs] are injectable so JVM tests can assert spacing without real time.
 */
class ProviderThrottle(
    private val policyForHost: (String?) -> ProviderPolicy = ProviderPolicies::forHost,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { if (it > 0L) delay(it) },
) {
    private class Gate(val policy: ProviderPolicy) {
        val semaphore = Semaphore(policy.maxConcurrent)
        val spacing = Mutex()
        var nextAllowedAtMs = 0L
    }

    private val gates = HashMap<String, Gate>()

    private fun gateFor(host: String?, policy: ProviderPolicy): Gate {
        val key = host ?: "default"
        synchronized(gates) {
            gates[key]?.let { return it }
            return Gate(policy).also { gates[key] = it }
        }
    }

    /** Runs [block] under the policy for [host]. Cancellation releases every acquired permit. */
    suspend fun <T> run(host: String?, block: suspend () -> T): T {
        val policy = policyForHost(host)
        val gate = gateFor(host, policy)
        gate.semaphore.acquire()
        try {
            if (policy.minSpacingMs > 0L) {
                gate.spacing.lock()
                try {
                    val waitMs = (gate.nextAllowedAtMs - nowMs()).coerceIn(0L, policy.minSpacingMs)
                    if (waitMs > 0L) delayMs(waitMs)
                    gate.nextAllowedAtMs = maxOf(nowMs(), gate.nextAllowedAtMs) + policy.minSpacingMs
                } finally {
                    gate.spacing.unlock()
                }
            }
            return block()
        } finally {
            gate.semaphore.release()
        }
    }
}

/**
 * The production [ServiceHttp]: every repository call goes through one shared [ProviderThrottle].
 * It delegates the actual request to [CancellableServiceHttp], so [Http.USER_AGENT], cancellation and
 * the shared connection pool stay exactly as before. Header support is forwarded unchanged.
 */
object ProviderHttp : ServiceHttp {
    private val throttle = ProviderThrottle()

    override suspend fun get(url: String, connectMs: Int, readMs: Int, headers: Map<String, String>): String =
        throttle.run(providerHost(url)) { CancellableServiceHttp.get(url, connectMs, readMs, headers) }

    override suspend fun postJson(
        url: String,
        body: String,
        connectMs: Int,
        readMs: Int,
        emptyOn400: Boolean,
        headers: Map<String, String>,
    ): String = throttle.run(providerHost(url)) {
        CancellableServiceHttp.postJson(url, body, connectMs, readMs, emptyOn400, headers)
    }

    override suspend fun postForm(url: String, body: String, connectMs: Int, readMs: Int, headers: Map<String, String>): String =
        throttle.run(providerHost(url)) { CancellableServiceHttp.postForm(url, body, connectMs, readMs, headers) }
}

package com.ridedeck.app.data

/**
 * App-local provider budgeting and single-flight admission.
 *
 * This is deliberately only per-installation load shaping. It does NOT prove Nominatim's aggregate
 * 1 request/second policy for all users, and it is not wired into any provider yet.
 */
data class ProviderBudgetPolicy(
    val maxConcurrent: Int,
    val maxQueue: Int,
    val minIntervalMs: Long,
) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        require(maxQueue >= 0) { "maxQueue must not be negative" }
        require(minIntervalMs >= 0) { "minIntervalMs must not be negative" }
    }
}

/** Conservative starting point only; provider-specific terms still require a separate decision. */
object ProviderBudgetDefaults {
    const val MAX_CONCURRENT = 1
    const val MAX_QUEUE = 2
    const val MIN_INTERVAL_MS = 1_000L
    val CONSERVATIVE = ProviderBudgetPolicy(MAX_CONCURRENT, MAX_QUEUE, MIN_INTERVAL_MS)
}

enum class ProviderBudgetStatus { RUNNING, QUEUED, SHARED, REJECTED }

data class ProviderBudgetTicket(val id: Long, val key: String)

data class ProviderBudgetDecision(
    val status: ProviderBudgetStatus,
    val ticket: ProviderBudgetTicket,
    val waiters: Int = 0,
)

class ProviderRequestBudget(public val policy: ProviderBudgetPolicy) {
    private data class Entry(
        val ticket: ProviderBudgetTicket,
        var running: Boolean,
        var waiters: Int = 0,
    )

    private var nextId = 1L
    private var running = 0
    private var nextAllowedAtMs = 0L
    private val entries = LinkedHashMap<Long, Entry>()
    private val queue = ArrayDeque<Long>()
    private val activeByKey = HashMap<String, Long>()

    /**
     * Same-key callers share one provider operation. A full running+queue allowance returns
     * REJECTED rather than growing without bound.
     */
    @Synchronized
    fun acquire(key: String, nowMs: Long): ProviderBudgetDecision {
        activeByKey[key]?.let { id ->
            entries[id]?.let { entry ->
                entry.waiters++
                return ProviderBudgetDecision(
                    ProviderBudgetStatus.SHARED, entry.ticket, waiters = entry.waiters,
                )
            }
        }

        val ticket = ProviderBudgetTicket(nextId++, key)
        if (running < policy.maxConcurrent && nowMs >= nextAllowedAtMs) {
            running++
            entries[ticket.id] = Entry(ticket, running = true)
            activeByKey[key] = ticket.id
            return ProviderBudgetDecision(ProviderBudgetStatus.RUNNING, ticket)
        }
        if (queue.size >= policy.maxQueue) {
            return ProviderBudgetDecision(ProviderBudgetStatus.REJECTED, ticket)
        }
        entries[ticket.id] = Entry(ticket, running = false)
        queue.addLast(ticket.id)
        activeByKey[key] = ticket.id
        return ProviderBudgetDecision(ProviderBudgetStatus.QUEUED, ticket)
    }

    /** Finish a running ticket and promote the oldest queued ticket when the interval allows it. */
    @Synchronized
    fun complete(ticketId: Long, nowMs: Long): ProviderBudgetDecision? {
        val entry = entries.remove(ticketId) ?: return null
        if (!entry.running) return null
        running--
        if (activeByKey[entry.ticket.key] == ticketId) activeByKey.remove(entry.ticket.key)
        nextAllowedAtMs = maxOf(nextAllowedAtMs, nowMs + policy.minIntervalMs)
        return promote(nowMs)
    }

    /** Cancel running or queued work; a running cancellation releases its concurrency slot. */
    @Synchronized
    fun cancel(ticketId: Long, nowMs: Long): Boolean {
        val entry = entries.remove(ticketId) ?: return false
        if (entry.running) {
            running--
            if (activeByKey[entry.ticket.key] == ticketId) activeByKey.remove(entry.ticket.key)
            nextAllowedAtMs = maxOf(nextAllowedAtMs, nowMs + policy.minIntervalMs)
            promote(nowMs)
        } else {
            queue.remove(ticketId)
            if (activeByKey[entry.ticket.key] == ticketId) activeByKey.remove(entry.ticket.key)
        }
        return true
    }

    @Synchronized
    fun runningCount(): Int = running
    @Synchronized
    fun queuedCount(): Int = queue.size

    /** Called by the scheduler clock after waiting; never starts before the interval. */
    @Synchronized
    fun promote(nowMs: Long): ProviderBudgetDecision? {
        if (running >= policy.maxConcurrent || nowMs < nextAllowedAtMs) return null
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val entry = entries[id] ?: continue
            entry.running = true
            running++
            activeByKey[entry.ticket.key] = id
            return ProviderBudgetDecision(ProviderBudgetStatus.RUNNING, entry.ticket)
        }
        return null
    }
}

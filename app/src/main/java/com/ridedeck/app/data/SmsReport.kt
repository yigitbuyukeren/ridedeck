package com.ridedeck.app.data

enum class SmsStatus { SENT, FAILED, UNKNOWN }
enum class SmsProblem { PERMISSION, NO_DEFAULT_SIM }
data class SmsReport(val recipients: Map<String, SmsStatus>, val problem: SmsProblem? = null)

object SmsSubscriptionPolicy {
    fun usable(id: Int): Boolean = id >= 0 && id != Int.MAX_VALUE
}

/** All parts must be confirmed. Missing callbacks are unknown, never success; no automatic retries. */
class SmsPartTracker(private val parts: Int) {
    private val results = mutableMapOf<Int, Boolean>()
    fun result(index: Int, success: Boolean) { if (index in 0 until parts) results.putIfAbsent(index, success) }
    val complete: Boolean get() = results.values.any { !it } || (parts > 0 && results.size == parts)
    fun status(): SmsStatus = when {
        results.values.any { !it } -> SmsStatus.FAILED
        parts > 0 && complete -> SmsStatus.SENT
        else -> SmsStatus.UNKNOWN
    }
}

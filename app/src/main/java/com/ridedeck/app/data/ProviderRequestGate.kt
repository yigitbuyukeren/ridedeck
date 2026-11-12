package com.ridedeck.app.data

import kotlinx.coroutines.CancellationException

internal class BudgetUnavailableException : Exception("provider budget unavailable")

/** Admission seam; provider key and request identity are deliberately separate. */
internal interface QuoteRequestGate {
    suspend fun <T> run(providerKey: String, requestKey: String, block: suspend () -> T): Result<T>
}

/** Direct gate for pure tests; it performs no budgeting and no provider traffic by itself. */
internal object DirectQuoteRequestGate : QuoteRequestGate {
    override suspend fun <T> run(
        providerKey: String,
        requestKey: String,
        block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * App-local admission for one selected quote at a time. The provider key is shared by Valhalla
 * requests, while request identity stays distinct for baseline, via and each station. Concurrent
 * same-provider quotes are truthfully rejected as BUDGET_UNAVAILABLE; this is not a finished
 * single-flight waiter implementation.
 */
internal class BudgetedQuoteRequestGate(
    private val budget: ProviderRequestBudget,
    private val nowMs: () -> Long,
    private val wait: suspend (Long) -> Unit,
) : QuoteRequestGate {
    override suspend fun <T> run(
        providerKey: String,
        requestKey: String,
        block: suspend () -> T,
    ): Result<T> {
        var retries = 0
        while (true) {
            val decision = budget.acquire(providerKey, nowMs())
            when (decision.status) {
                ProviderBudgetStatus.RUNNING -> {
                    try {
                        return Result.success(block())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        return Result.failure(e)
                    } finally {
                        budget.complete(decision.ticket.id, nowMs())
                    }
                }
                ProviderBudgetStatus.SHARED -> return Result.failure(BudgetUnavailableException())
                ProviderBudgetStatus.QUEUED -> {
                    budget.cancel(decision.ticket.id, nowMs())
                    if (retries++ >= 1) return Result.failure(BudgetUnavailableException())
                    wait(maxOf(1L, budget.policy.minIntervalMs))
                }
                ProviderBudgetStatus.REJECTED -> {
                    if (retries++ >= 1) return Result.failure(BudgetUnavailableException())
                    wait(maxOf(1L, budget.policy.minIntervalMs))
                }
            }
        }
    }
}

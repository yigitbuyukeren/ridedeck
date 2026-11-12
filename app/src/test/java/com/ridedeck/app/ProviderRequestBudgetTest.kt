package com.ridedeck.app

import com.ridedeck.app.data.ProviderBudgetPolicy
import com.ridedeck.app.data.ProviderBudgetStatus
import com.ridedeck.app.data.ProviderRequestBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure fake-clock admission: no sockets, scheduler, provider or public traffic. */
class ProviderRequestBudgetTest {
    private fun budget(maxConcurrent: Int = 1, maxQueue: Int = 2, minIntervalMs: Long = 1_000L) =
        ProviderRequestBudget(ProviderBudgetPolicy(maxConcurrent, maxQueue, minIntervalMs))

    @Test fun sameKeySharesOneOperationAndWaitersAreCounted() {
        val budget = budget()
        val owner = budget.acquire("nominatim:/search?q=fuel", nowMs = 0)
        val waiter = budget.acquire("nominatim:/search?q=fuel", nowMs = 0)

        assertEquals(ProviderBudgetStatus.RUNNING, owner.status)
        assertEquals(ProviderBudgetStatus.SHARED, waiter.status)
        assertEquals(owner.ticket, waiter.ticket)
        assertEquals(1, waiter.waiters)
        assertEquals(1, budget.runningCount())
        assertEquals(0, budget.queuedCount())
    }

    @Test fun queueIsBoundedAndRejectsRatherThanGrowing() {
        val budget = budget(maxConcurrent = 1, maxQueue = 1)
        assertTrue(budget.acquire("a", 0).status == ProviderBudgetStatus.RUNNING)
        assertTrue(budget.acquire("b", 0).status == ProviderBudgetStatus.QUEUED)
        assertEquals(ProviderBudgetStatus.REJECTED, budget.acquire("c", 0).status)
        assertEquals(1, budget.queuedCount())
    }

    @Test fun sameQueuedKeyIsSharedNotDuplicatedInTheQueue() {
        val budget = budget(maxConcurrent = 1, maxQueue = 2)
        budget.acquire("a", 0)
        val queued = budget.acquire("b", 0)
        val shared = budget.acquire("b", 0)

        assertEquals(ProviderBudgetStatus.QUEUED, queued.status)
        assertEquals(ProviderBudgetStatus.SHARED, shared.status)
        assertEquals(queued.ticket, shared.ticket)
        assertEquals(1, budget.queuedCount())
    }

    @Test fun minimumIntervalDelaysPromotionUntilTheFakeClockAdvances() {
        val budget = budget(minIntervalMs = 1_000L)
        val first = budget.acquire("a", 0)
        budget.acquire("b", 0)

        assertNull("must not start before the app-local interval", budget.complete(first.ticket.id, nowMs = 0))
        assertEquals(1, budget.queuedCount())
        val promoted = budget.promote(1_000L)
        assertEquals(ProviderBudgetStatus.RUNNING, promoted?.status)
        assertEquals(1, budget.runningCount())
        assertEquals(0, budget.queuedCount())
    }

    @Test fun queuedCancellationDoesNotConsumeAProviderSlot() {
        val budget = budget(maxConcurrent = 1, maxQueue = 1)
        val running = budget.acquire("a", 0)
        val queued = budget.acquire("b", 0)

        assertTrue(budget.cancel(queued.ticket.id, nowMs = 0))
        assertEquals(0, budget.queuedCount())
        assertTrue(budget.acquire("c", 0).status == ProviderBudgetStatus.QUEUED)
        assertTrue(budget.runningCount() == 1)
        budget.complete(running.ticket.id, nowMs = 1_000)
        assertTrue(budget.promote(2_000)?.status == ProviderBudgetStatus.RUNNING)
    }

    @Test fun runningCancellationReleasesTheSlotAndPromotesAtTheNextAllowedTime() {
        val budget = budget(maxConcurrent = 1, maxQueue = 1)
        val running = budget.acquire("a", 0)
        budget.acquire("b", 0)

        assertTrue(budget.cancel(running.ticket.id, nowMs = 0))
        assertNull(budget.promote(999))
        assertEquals(ProviderBudgetStatus.RUNNING, budget.promote(1_000)?.status)
        assertEquals(0, budget.queuedCount())
    }

    @Test fun duplicateCompletionAndCancellationCannotDriveCountersNegative() {
        val budget = budget(maxConcurrent = 1, maxQueue = 1)
        val running = budget.acquire("a", 0)

        assertNull(budget.complete(running.ticket.id, nowMs = 0))
        assertNull(budget.complete(running.ticket.id, nowMs = 1_000))
        assertFalse(budget.cancel(running.ticket.id, nowMs = 2_000))
        assertEquals(0, budget.runningCount())
        assertEquals(0, budget.queuedCount())
    }
}

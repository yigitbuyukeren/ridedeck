package com.ridedeck.app

import com.ridedeck.app.data.CancellableRetry
import com.ridedeck.app.data.RetryHandle
import com.ridedeck.app.data.RetryScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableRetryTest {
    private class FakeScheduler : RetryScheduler {
        data class Task(val delay: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): RetryHandle {
            val entry = Task(delayMs, task)
            tasks += entry
            return RetryHandle { entry.cancelled = true }
        }
        fun fire(task: Task) { if (!task.cancelled) task.run() }
        fun fireEvenIfCancelled(task: Task) = task.run()
    }

    @Test fun stopCancelsPendingRetry() {
        val scheduler = FakeScheduler()
        var starts = 0
        val retry = CancellableRetry(scheduler, { true }) { starts++ }
        retry.schedule(400)
        retry.cancel()
        scheduler.fire(scheduler.tasks.single())
        assertTrue(scheduler.tasks.single().cancelled)
        assertEquals(0, starts)
    }

    @Test fun rescheduleMakesOlderGenerationInert() {
        val scheduler = FakeScheduler()
        var starts = 0
        val retry = CancellableRetry(scheduler, { true }) { starts++ }
        retry.schedule(400)
        val old = scheduler.tasks.last()
        retry.schedule(800)
        scheduler.fireEvenIfCancelled(old)
        assertEquals(0, starts)
        scheduler.fire(scheduler.tasks.last())
        assertEquals(1, starts)
        assertEquals(800L, scheduler.tasks.last().delay)
    }

    @Test fun retryChecksCurrentPermissionAndPreferenceAtExecutionTime() {
        val scheduler = FakeScheduler()
        var permitted = true
        var starts = 0
        val retry = CancellableRetry(scheduler, { permitted }) { starts++ }
        retry.schedule(400)
        permitted = false
        scheduler.fire(scheduler.tasks.single())
        assertEquals(0, starts)
        assertFalse(scheduler.tasks.single().cancelled)
    }
}

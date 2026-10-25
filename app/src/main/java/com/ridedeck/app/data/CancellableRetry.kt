package com.ridedeck.app.data

/** A scheduled callback that can be removed before it runs. */
fun interface RetryHandle {
    fun cancel()
}

fun interface RetryScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): RetryHandle
}

/** Owns one delayed retry and invalidates callbacks from older wake-listener lifecycles. */
class CancellableRetry(
    private val scheduler: RetryScheduler,
    private val allowed: () -> Boolean,
    private val action: () -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var pending: RetryHandle? = null

    fun schedule(delayMs: Long) = synchronized(lock) {
        pending?.cancel()
        val token = ++generation
        pending = scheduler.schedule(delayMs) {
            synchronized(lock) {
                if (token != generation) return@synchronized
                pending = null
                if (allowed()) action()
            }
        }
    }

    fun cancel() = synchronized(lock) {
        generation++
        pending?.cancel()
        pending = null
    }
}

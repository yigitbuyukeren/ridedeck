package com.ridedeck.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

sealed interface RouteGeometryCommand {
    val revision: Long
    data class Save(val snapshot: RouteGeometrySnapshot, val activeTrip: ActiveTrip, override val revision: Long) : RouteGeometryCommand
    data class Clear(override val revision: Long) : RouteGeometryCommand
}

/**
 * Bounded, latest-command-coalescing geometry queue. Commands carry monotonically increasing revisions;
 * a cancelled runner closes/drains the channel and cannot replay pending commands in a later runner.
 */
class RouteGeometryCommandQueue(
    private val store: RouteGeometryStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val channel = Channel<RouteGeometryCommand>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var nextRevision = 0L

    fun enqueueSave(snapshot: RouteGeometrySnapshot, activeTrip: ActiveTrip): Boolean =
        channel.trySend(RouteGeometryCommand.Save(snapshot, activeTrip, ++nextRevision)).isSuccess

    fun enqueueClear(): Boolean = channel.trySend(RouteGeometryCommand.Clear(++nextRevision)).isSuccess

    suspend fun run() = withContext(dispatcher) {
        currentCoroutineContext()[Job]?.invokeOnCompletion { closeAndDrain() }
        var lastApplied = -1L
        try {
            for (command in channel) {
                if (command.revision <= lastApplied) continue
                when (command) {
                    is RouteGeometryCommand.Save -> store.save(command.snapshot, command.activeTrip)
                    is RouteGeometryCommand.Clear -> store.clear()
                }
                lastApplied = command.revision
            }
        } finally {
            closeAndDrain()
        }
    }

    private fun closeAndDrain() {
        channel.close()
        while (channel.tryReceive().isSuccess) Unit
    }

    fun close() {
        closeAndDrain()
    }
}

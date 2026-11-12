package com.ridedeck.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Test seam for the AtomicFile adapter; device probe happens later. */
interface RouteGeometryFileGateway {
    fun lengthBytes(): Long
    fun readText(maxBytes: Int): String?
    fun beginWrite(): RouteGeometryWriteSession
    fun delete()
}

interface RouteGeometryWriteSession {
    fun write(bytes: ByteArray)
    fun sync()
    fun commit()
    fun abort()
}

sealed interface RouteGeometryWriteResult {
    data object Saved : RouteGeometryWriteResult
    data object RejectedSize : RouteGeometryWriteResult
    data object RejectedIdentity : RouteGeometryWriteResult
    data object Failed : RouteGeometryWriteResult
}

enum class RouteGeometryReject {
    OVERSIZED,
    UNREADABLE,
    MALFORMED,
    STALE,
    IDENTITY_MISMATCH,
}

sealed interface RouteGeometryRestoreResult {
    data object Missing : RouteGeometryRestoreResult
    data class Restored(val snapshot: RouteGeometrySnapshot) : RouteGeometryRestoreResult
    data class Rejected(val reason: RouteGeometryReject) : RouteGeometryRestoreResult
}

/**
 * FEAT-004 candidate, not wired into navigation. The write path commits only after write+sync; any failure,
 * cancellation or interrupted replacement aborts the temp file and leaves the last good base/backup untouched.
 * Rejected reads are reported but not deleted, because deleting base+backup could destroy the last good record.
 */
class RouteGeometryStore(
    private val gateway: RouteGeometryFileGateway,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFileBytes: Int = MAX_FILE_BYTES,
    /** Test seam for cancellation exactly after fsync and before commit; production is a no-op. */
    private val beforeCommit: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun save(snapshot: RouteGeometrySnapshot, activeTrip: ActiveTrip): RouteGeometryWriteResult =
        withContext(dispatcher) {
            mutex.withLock {
                // Initial FEAT-004 has no consistent pump-leg preview UX; do not persist a misleading fuel geometry.
                if (activeTrip.finalDestination != null) return@withLock RouteGeometryWriteResult.RejectedIdentity
                if (!snapshot.matches(activeTrip)) return@withLock RouteGeometryWriteResult.RejectedIdentity
                val json = runCatching { snapshot.toJson() }.getOrElse { return@withLock RouteGeometryWriteResult.Failed }
                val bytes = json.toByteArray(Charsets.UTF_8)
                if (bytes.size > maxFileBytes) return@withLock RouteGeometryWriteResult.RejectedSize

                val session = runCatching { gateway.beginWrite() }.getOrElse { return@withLock RouteGeometryWriteResult.Failed }
                var committed = false
                try {
                    session.write(bytes)
                    session.sync()
                    beforeCommit()
                    currentCoroutineContext().ensureActive()
                    session.commit()
                    committed = true
                    RouteGeometryWriteResult.Saved
                } catch (cancelled: CancellationException) {
                    if (!committed) runCatching { session.abort() }
                    throw cancelled
                } catch (_: Throwable) {
                    if (!committed) runCatching { session.abort() }
                    RouteGeometryWriteResult.Failed
                }
            }
        }

    suspend fun restore(activeTrip: ActiveTrip, nowMs: Long): RouteGeometryRestoreResult =
        withContext(dispatcher) {
            mutex.withLock {
                // The current TECH-004 preview restores the post-fuel final leg; stored geometry is the pump leg.
                if (activeTrip.finalDestination != null) {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.IDENTITY_MISMATCH)
                }
                val length = runCatching { gateway.lengthBytes() }.getOrElse {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.UNREADABLE)
                }
                if (length <= 0) return@withLock RouteGeometryRestoreResult.Missing
                if (length > maxFileBytes) {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.OVERSIZED)
                }
                val json = runCatching { gateway.readText(maxFileBytes) }.getOrElse {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.UNREADABLE)
                } ?: return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.UNREADABLE)

                val snapshot = RouteGeometrySnapshot.fromJson(json)
                    ?: return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.MALFORMED)
                if (!snapshot.fresh(nowMs)) {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.STALE)
                }
                if (!snapshot.matches(activeTrip)) {
                    return@withLock RouteGeometryRestoreResult.Rejected(RouteGeometryReject.IDENTITY_MISMATCH)
                }
                RouteGeometryRestoreResult.Restored(snapshot)
            }
        }

    /** Explicit rider cancel / ActiveTrip clear only; never called by a rejected read. */
    suspend fun clear(): Boolean = withContext(dispatcher) {
        mutex.withLock { runCatching { gateway.delete(); true }.getOrDefault(false) }
    }

    companion object {
        const val FILE_NAME = "route_geometry_v1.json"
        const val MAX_FILE_BYTES = RouteGeometrySnapshot.MAX_PAYLOAD_BYTES
    }
}

/** Android adapter candidate; must be exercised by a device probe before integration. */
class AtomicFileRouteGeometryGateway(
    context: Context,
    fileName: String = RouteGeometryStore.FILE_NAME,
) : RouteGeometryFileGateway {
    private val base = File(context.noBackupFilesDir, fileName)
    private val backup = File(base.path + ".bak")
    private val atomic = AtomicFile(base)

    override fun lengthBytes(): Long = maxOf(base.length(), backup.length())

    override fun readText(maxBytes: Int): String? {
        val input: FileInputStream = runCatching { atomic.openRead() }.getOrNull() ?: return null
        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray().toString(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { input.close() }
        }
    }

    override fun beginWrite(): RouteGeometryWriteSession {
        val out: FileOutputStream = atomic.startWrite()
        return object : RouteGeometryWriteSession {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun sync() {
                out.flush()
                out.fd.sync()
            }
            override fun commit() = atomic.finishWrite(out)
            override fun abort() = atomic.failWrite(out)
        }
    }

    override fun delete() = atomic.delete()
}

package com.ridedeck.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.DefaultDatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

interface TripStorage {
    fun startTrip(startedAt: Long): Long
    fun addPoint(tripId: Long, p: TripPoint)
    fun finishTrip(tripId: Long, endedAt: Long, stats: TripStats)
    fun trips(): List<Trip>
    fun points(tripId: Long): List<TripPoint>
    fun unfinishedIds(): List<Long>
    fun lastPointTime(tripId: Long): Long
    fun delete(tripId: Long)
    fun deleteAll()
}

/** Trips and their GPS points in a small local SQLite database. Nothing leaves the device. */
class TripStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DB_VERSION, CorruptDatabaseHandler), TripStorage {
    init {
        // WAL keeps a killed process from losing the last accepted fixes and lets reads continue
        // while a point is written. SQLite silently falls back when the filesystem cannot do it.
        runCatching { setWriteAheadLoggingEnabled(true) }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Foreign keys are off by default on Android. The current schema enforces parent/child
        // integrity in its transactions; enabling the pragma keeps future FK columns safe too.
        runCatching { db.setForeignKeyConstraintsEnabled(true) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, " +
                "ended_at INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0, " +
                "moving_ms INTEGER NOT NULL DEFAULT 0, max_kmh INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE points (trip_id INTEGER NOT NULL, t INTEGER NOT NULL, lat REAL NOT NULL, " +
                "lon REAL NOT NULL, kmh INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX points_trip ON points (trip_id, t)")
        db.execSQL(CREATE_TRIPS_INDEX)
    }

    /**
     * Append-only migration path. Each step is idempotent (`IF NOT EXISTS`) and only adds schema,
     * so a database created by a newer build can safely be reopened by this build (see
     * [onDowngrade]). Future migrations must be added as a new `if (oldVersion < N)` block and
     * [DB_VERSION] bumped; do not edit an existing step after release.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: index for the list and unfinished-trip queries.
            db.execSQL(CREATE_TRIPS_INDEX)
        }
    }

    /**
     * A downgrade must not silently destroy ride history. Every migration so far is additive, so
     * the older code can keep reading the newer file. If a future migration ever removes or
     * renames columns, this override must be revisited.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Intentionally keep the existing rows and indexes.
    }

    /**
     * Preserve a forensic copy of a corrupt database, then let the default handler delete it so
     * the next open can create a fresh file. Never throws: a corrupt file must not crash the app.
     */
    private object CorruptDatabaseHandler : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            val path = runCatching { db.path }.getOrNull()
            if (path != null) {
                runCatching { TripDatabaseRecovery.preserveCorruptCopy(path, System.currentTimeMillis()) }
            }
            runCatching { DefaultDatabaseErrorHandler().onCorruption(db) }
            // Make sure recovery is not blocked by a file the default handler failed to remove.
            if (path != null) runCatching { SQLiteDatabase.deleteDatabase(File(path)) }
        }
    }

    override fun startTrip(startedAt: Long): Long =
        writableDatabase.insertOrThrow("trips", null, ContentValues().apply { put("started_at", startedAt) })

    override fun addPoint(tripId: Long, p: TripPoint) {
        writableDatabase.insertOrThrow("points", null, ContentValues().apply {
            put("trip_id", tripId); put("t", p.t); put("lat", p.lat); put("lon", p.lon); put("kmh", p.kmh)
        })
    }

    override fun finishTrip(tripId: Long, endedAt: Long, stats: TripStats) = transaction {
        writableDatabase.update("trips", ContentValues().apply {
            put("ended_at", endedAt)
            put("distance_m", stats.distanceM)
            put("moving_ms", stats.movingMs)
            put("max_kmh", stats.maxKmh)
        }, "id = ?", arrayOf(tripId.toString()))
        writableDatabase.delete("points", "trip_id = ? AND t > ?", arrayOf(tripId.toString(), endedAt.toString()))
    }

    /** Finished trips, newest first. */
    override fun trips(): List<Trip> = readableDatabase.query(
        "trips", null, "ended_at > 0", null, null, null, "started_at DESC"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Trip(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                        endedAt = c.getLong(c.getColumnIndexOrThrow("ended_at")),
                        stats = TripStats(
                            distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
                            movingMs = c.getLong(c.getColumnIndexOrThrow("moving_ms")),
                            maxKmh = c.getInt(c.getColumnIndexOrThrow("max_kmh")),
                        ),
                    )
                )
            }
        }
    }

    override fun points(tripId: Long): List<TripPoint> = readableDatabase.query(
        "points", null, "trip_id = ?", arrayOf(tripId.toString()), null, null, "t ASC"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    TripPoint(
                        t = c.getLong(c.getColumnIndexOrThrow("t")),
                        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
                        kmh = c.getInt(c.getColumnIndexOrThrow("kmh")),
                    )
                )
            }
        }
    }

    /** Trips that never got an end time, because the process died mid-ride. */
    override fun unfinishedIds(): List<Long> = readableDatabase.query(
        "trips", arrayOf("id"), "ended_at = 0", null, null, null, null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    override fun lastPointTime(tripId: Long): Long = readableDatabase.rawQuery(
        "SELECT MAX(t) FROM points WHERE trip_id = ?", arrayOf(tripId.toString())
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private inline fun transaction(block: () -> Unit) {
        writableDatabase.beginTransaction()
        try {
            block()
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun delete(tripId: Long) = transaction {
        writableDatabase.delete("points", "trip_id = ?", arrayOf(tripId.toString()))
        writableDatabase.delete("trips", "id = ?", arrayOf(tripId.toString()))
    }

    override fun deleteAll() = transaction {
        writableDatabase.delete("points", null, null)
        writableDatabase.delete("trips", null, null)
    }

    companion object {
        const val DATABASE_NAME = "trips.db"

        /**
         * v1: trips + points. v2: the `trips_ended_started` lookup index. A user database is
         * upgraded in place by [onUpgrade]; a fresh database gets the latest schema from [onCreate].
         */
        const val DB_VERSION = 2

        private const val CREATE_TRIPS_INDEX =
            "CREATE INDEX IF NOT EXISTS trips_ended_started ON trips (ended_at, started_at)"
    }
}

/**
 * Copies the SQLite files of a corrupt database next to the original before it is deleted. The
 * helper is separate from [TripStore] so the file naming/copy behaviour can be exercised without
 * making Android report a real corruption first.
 */
object TripDatabaseRecovery {
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    /**
     * A never-overwriting backup name: `trips.db.corrupt-<timestamp>`, with `-1`, `-2`… appended if
     * two corruptions happen in the same millisecond. Null when the source has no parent directory.
     */
    fun backupFile(databasePath: String, timestamp: Long): File? {
        val source = File(databasePath)
        val dir = source.parentFile ?: return null
        var candidate = File(dir, "${source.name}.corrupt-$timestamp")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${source.name}.corrupt-$timestamp-${counter++}")
        }
        return candidate
    }

    /**
     * Copies the main file and any WAL/SHM/journal sidecars. Returns the main backup file, or null
     * when there is no file to preserve or the copy failed. Never throws.
     */
    fun preserveCorruptCopy(databasePath: String, timestamp: Long): File? {
        if (!File(databasePath).isFile) return null
        val backup = backupFile(databasePath, timestamp) ?: return null
        return runCatching {
            File(databasePath).copyTo(backup, overwrite = false)
            for (suffix in SIDECARS) {
                val sidecar = File(databasePath + suffix)
                if (sidecar.isFile) {
                    runCatching { sidecar.copyTo(File(backup.path + suffix), overwrite = false) }
                }
            }
            backup
        }.getOrNull()
    }
}

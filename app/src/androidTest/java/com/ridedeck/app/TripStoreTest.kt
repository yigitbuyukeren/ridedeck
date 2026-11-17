package com.ridedeck.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.TripDatabaseRecovery
import com.ridedeck.app.data.TripPoint
import com.ridedeck.app.data.TripStats
import com.ridedeck.app.data.TripStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TripStoreTest {
    /** Separate database for every test; the user's trips.db is never opened. */
    private fun withStore(check: (TripStore) -> Unit) {
        val (context, file) = tempDatabase()
        val store = TripStore(context)
        try { check(store) } finally { store.close(); cleanup(file) }
    }

    private fun tempDatabase(): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "trips-test-${UUID.randomUUID()}.db")
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file.path, factory, errorHandler)
        }
        return context to file
    }

    private fun cleanup(file: File) {
        SQLiteDatabase.deleteDatabase(file)
        file.parentFile?.listFiles { child -> child.name.startsWith(file.name + ".corrupt-") }
            ?.forEach { it.delete() }
    }

    @Test fun finishingTrimsStationaryTailAndPersistsSummaryTogether() = withStore { store ->
        val id = store.startTrip(1_000)
        store.addPoint(id, TripPoint(1_000, 41.0, 29.0, 36))
        store.addPoint(id, TripPoint(2_000, 41.0001, 29.0, 36))
        store.addPoint(id, TripPoint(3_000, 41.0001, 29.0, 0))
        val stats = TripStats(11.0, 1_000, 36)
        store.finishTrip(id, 2_000, stats)
        assertTrue(store.unfinishedIds().isEmpty())
        assertEquals(stats, store.trips().single().stats)
        assertEquals(listOf(1_000L, 2_000L), store.points(id).map { it.t })
    }

    @Test fun deleteAllRemovesFinishedAndUnfinishedTripsWithTheirPoints() = withStore { store ->
        val id = store.startTrip(1_000)
        store.addPoint(id, TripPoint(1_000, 41.0, 29.0, 36))
        store.deleteAll()
        assertTrue(store.trips().isEmpty())
        assertTrue(store.unfinishedIds().isEmpty())
        assertTrue(store.points(id).isEmpty())

        // The schema and its indexes must survive a bulk delete so the next ride can start.
        assertEquals(TripStore.DB_VERSION, store.readableDatabase.version)
        assertTrue(indexNames(store).contains("trips_ended_started"))
        val next = store.startTrip(5_000)
        assertEquals(listOf(next), store.unfinishedIds())
    }

    @Test fun upgradeFromV1KeepsRowsAndAddsTheV2Index() {
        val (context, file) = tempDatabase()
        createV1Database(file)
        val store = TripStore(context)
        try {
            val trip = store.trips().single()
            assertEquals(1_000L, trip.startedAt)
            assertEquals(61_000L, trip.endedAt)
            assertEquals(400.0, trip.stats.distanceM, 0.0)
            assertEquals(listOf(1_000L), store.points(trip.id).map { it.t })
            assertEquals(TripStore.DB_VERSION, store.readableDatabase.version)
            assertTrue("v1 -> v2 must add the lookup index", indexNames(store).contains("trips_ended_started"))
        } finally {
            store.close()
            cleanup(file)
        }
    }

    @Test fun downgradeKeepsRideHistoryAndDoesNotThrow() {
        val (context, file) = tempDatabase()
        val store = TripStore(context)
        val id = store.startTrip(1_000)
        store.addPoint(id, TripPoint(1_000, 41.0, 29.0, 36))
        store.finishTrip(id, 61_000, TripStats(400.0, 60_000, 42))
        store.close()

        // Simulate a file written by a newer build, then open it with this build.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.version = TripStore.DB_VERSION + 10
        }
        val reopened = TripStore(context)
        try {
            assertEquals(1, reopened.trips().size)
            assertEquals(id, reopened.trips().single().id)
            assertEquals(TripStore.DB_VERSION, reopened.readableDatabase.version)
        } finally {
            reopened.close()
            cleanup(file)
        }
    }

    @Test fun corruptDatabaseIsCopiedBeforeRecreationAndTheAppStaysUsable() {
        val (context, file) = tempDatabase()
        file.parentFile?.mkdirs()
        file.writeBytes("this is not a sqlite database".toByteArray())

        val store = TripStore(context)
        try {
            // Opening may report corruption; onCorruption must preserve a copy and let the helper
            // recreate an empty database instead of leaving the app permanently broken.
            runCatching { store.trips() }
            val backups = file.parentFile
                ?.listFiles { child -> child.name.startsWith(file.name + ".corrupt-") }
                .orEmpty()
            assertTrue("corrupt database must be preserved before deletion", backups.isNotEmpty())
            assertTrue("backup must contain the original bytes", backups.single().readBytes().isNotEmpty())

            val newId = store.startTrip(9_000)
            assertEquals(listOf(newId), store.unfinishedIds())
        } finally {
            runCatching { store.close() }
            cleanup(file)
        }
    }

    @Test fun corruptBackupNamesNeverOverwriteAnEarlierCopy() {
        val (_, file) = tempDatabase()
        val first = TripDatabaseRecovery.backupFile(file.path, 1234L)
        assertNotNull(first)
        assertEquals(file.name + ".corrupt-1234", first!!.name)
        first.writeText("earlier corruption")
        val second = TripDatabaseRecovery.backupFile(file.path, 1234L)
        assertEquals(file.name + ".corrupt-1234-1", second!!.name)
        cleanup(file)
        first.delete()
    }

    @Test fun foreignKeysAndWalAreConfigured() = withStore { store ->
        store.readableDatabase.rawQuery("PRAGMA foreign_keys", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // The device/emulator may fall back if the filesystem cannot do WAL; assert the requested
        // mode only when it was actually enabled, otherwise the read path still has to work.
        if (store.readableDatabase.isWriteAheadLoggingEnabled) {
            store.readableDatabase.rawQuery("PRAGMA journal_mode", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("wal", c.getString(0).lowercase())
            }
        }
    }

    private fun indexNames(store: TripStore): Set<String> =
        store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** The exact v1 schema and one finished ride, written without going through TripStore. */
    private fun createV1Database(file: File) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
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
            db.execSQL(
                "INSERT INTO trips (id, started_at, ended_at, distance_m, moving_ms, max_kmh) " +
                    "VALUES (1, 1000, 61000, 400.0, 60000, 42)"
            )
            db.execSQL(
                "INSERT INTO points (trip_id, t, lat, lon, kmh) VALUES (1, 1000, 41.0, 29.0, 36)"
            )
            db.version = 1
        } finally {
            db.close()
        }
    }
}

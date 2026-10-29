package com.ridedeck.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import androidx.test.platform.app.InstrumentationRegistry
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
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "trips-test-${UUID.randomUUID()}.db")
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(file.path, factory, errorHandler)
        }
        val store = TripStore(context)
        try { check(store) } finally { store.close(); SQLiteDatabase.deleteDatabase(file) }
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
    }
}

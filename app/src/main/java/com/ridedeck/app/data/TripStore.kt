package com.ridedeck.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
class TripStore(context: Context) : SQLiteOpenHelper(context, "trips.db", null, 1), TripStorage {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
}

package com.koen.haremote.network.tcp

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * [LocationOutbox] backed by a small private SQLite database (`location_outbox.db` in the app's
 * internal storage). SQLite rather than a plain file because this has to survive the process
 * being killed at any instant - which is routine for this app, see `LocationLoggingService` -
 * without ever ending up half-written: every insert/delete is its own journaled transaction.
 *
 * Bounded: once more than [maxEntries] locations are waiting, the oldest ones are dropped to make
 * room (reported through [onDropped]). With the default of 10 000 that is over a day of
 * continuous 10-second fixes, and far longer in practice since a stationary client (geofence
 * active) only produces one fix per 30 minutes.
 *
 * Every public method is synchronized and swallows storage errors (returning a "nothing
 * stored"/"nothing there" value instead) - see [LocationOutbox]'s contract.
 */
class SqliteLocationOutbox(
    context: Context,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val onDropped: (count: Int) -> Unit = {}
) : LocationOutbox {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            // AUTOINCREMENT (not just INTEGER PRIMARY KEY) on purpose: ids must never be reused,
            // even after the newest row has been deleted - TcpLocationClient confirms delivery
            // with "everything up to id N", which would silently eat a *new* row if N could be
            // handed out a second time.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_PAYLOAD TEXT NOT NULL, " +
                    "$COL_CREATED_AT INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Only one schema version exists so far.
        }
    }

    /** Cached row count, so the size cap doesn't cost a COUNT(*) on every insert. -1 = not yet
     *  read from the database. Only touched inside synchronized methods. */
    private var cachedCount = -1

    @Synchronized
    override fun add(payload: String): Long {
        return try {
            val db = helper.writableDatabase
            val values = ContentValues().apply {
                put(COL_PAYLOAD, payload)
                put(COL_CREATED_AT, System.currentTimeMillis())
            }
            val id = db.insertOrThrow(TABLE, null, values)
            if (cachedCount >= 0) cachedCount++
            trimIfNeeded(db)
            id
        } catch (e: Exception) {
            Log.w(TAG, "Could not store location: ${e.message}")
            cachedCount = -1
            -1L
        }
    }

    @Synchronized
    override fun peek(limit: Int): List<LocationOutbox.Entry> {
        return try {
            val result = ArrayList<LocationOutbox.Entry>(limit)
            helper.readableDatabase.query(
                TABLE, arrayOf(COL_ID, COL_PAYLOAD), null, null, null, null, "$COL_ID ASC", limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += LocationOutbox.Entry(cursor.getLong(0), cursor.getString(1))
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Could not read stored locations: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    override fun deleteUpTo(id: Long) {
        try {
            val deleted = helper.writableDatabase.delete(TABLE, "$COL_ID <= ?", arrayOf(id.toString()))
            if (cachedCount >= 0) cachedCount = (cachedCount - deleted).coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove delivered locations: ${e.message}")
            cachedCount = -1
        }
    }

    @Synchronized
    override fun count(): Int {
        return try {
            currentCount(helper.readableDatabase)
        } catch (e: Exception) {
            Log.w(TAG, "Could not count stored locations: ${e.message}")
            0
        }
    }

    /** Releases the database handle. Safe to keep using the outbox afterwards - SQLiteOpenHelper
     *  simply reopens on the next call. */
    @Synchronized
    fun close() {
        runCatching { helper.close() }
    }

    private fun currentCount(db: SQLiteDatabase): Int {
        if (cachedCount < 0) cachedCount = DatabaseUtils.queryNumEntries(db, TABLE).toInt()
        return cachedCount
    }

    /** Drops the oldest rows once the cap is exceeded. Overshoots by [TRIM_SLACK] on purpose, so
     *  a full outbox doesn't run a DELETE on every single insert. */
    private fun trimIfNeeded(db: SQLiteDatabase) {
        val count = currentCount(db)
        if (count <= maxEntries) return
        val excess = count - maxEntries + minOf(TRIM_SLACK, maxEntries / 2)
        val deleted = db.delete(
            TABLE,
            // `excess` is an Int computed just above, so inlining it is safe - and avoids relying
            // on SQLite coercing a *string* bind argument into a LIMIT.
            "$COL_ID IN (SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID ASC LIMIT $excess)",
            null
        )
        cachedCount = (count - deleted).coerceAtLeast(0)
        if (deleted > 0) runCatching { onDropped(deleted) }
    }

    companion object {
        private const val TAG = "LocationOutbox"
        private const val DB_NAME = "location_outbox.db"
        private const val DB_VERSION = 1
        private const val TABLE = "pending_location"
        private const val COL_ID = "id"
        private const val COL_PAYLOAD = "payload"
        private const val COL_CREATED_AT = "created_at"

        const val DEFAULT_MAX_ENTRIES = 10_000
        private const val TRIM_SLACK = 100
    }
}

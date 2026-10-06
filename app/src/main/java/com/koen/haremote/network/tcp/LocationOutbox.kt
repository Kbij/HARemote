package com.koen.haremote.network.tcp

/**
 * Durable "to send" queue for [GpsLocationMessage]s (see [TcpLocationClient]).
 *
 * Every location the app wants to report is written here *first* and only removed once the
 * server has demonstrably received it (see [TcpLocationClient]'s class doc for how that is
 * established without a dedicated ACK message in the protocol). Anything still in here after a
 * connection drops, the server being unreachable for hours, the service being killed or the
 * phone rebooting simply gets sent, oldest first, the next time a connection is up.
 *
 * Entries are opaque JSON payloads (exactly what goes into the `OBJ_GPS_LOCATION` frame) keyed by
 * a strictly increasing id - "everything up to and including id N" is therefore always a
 * well-defined, contiguous prefix of the queue, which is what [deleteUpTo] relies on.
 *
 * An interface (rather than just the SQLite class) so [TcpLocationClient] stays free of any
 * `Context`/database dependency; the real implementation is [SqliteLocationOutbox].
 * Implementations must be thread-safe and must never throw - a storage failure is reported
 * through the return value instead, so a broken disk can degrade location reporting but never
 * crash it.
 */
interface LocationOutbox {
    data class Entry(val id: Long, val payload: String)

    /** Appends a payload. @return its id, or a negative value if it could not be stored. */
    fun add(payload: String): Long

    /** The oldest [limit] entries, oldest first. Empty if there are none (or on failure). */
    fun peek(limit: Int): List<Entry>

    /** Removes every entry with an id `<=` [id]. */
    fun deleteUpTo(id: Long)

    /** Number of entries currently waiting. 0 on failure. */
    fun count(): Int
}

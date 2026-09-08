package com.koen.haremote.network.tcp

/**
 * Wire protocol used by the legacy HomeControl TCP server (see the C++ `HomeControlServer` /
 * `ClientSocket`, and the old Xamarin `CloudSocket`/`AsyncCloudSocket` client). It is a tiny
 * length-prefixed framing on top of a single persistent TCP connection:
 *
 * ```
 * byte 0-2   "HCM"              fixed 3-byte header
 * byte 3-4   length             payload length, uint16 big-endian (MSB first)
 * byte 5     objectId           uint8
 * byte 6..   payload            usually a small ASCII/UTF-8 JSON document
 * ```
 *
 * Object ids are shared with the server implementation and the old Android client and must not
 * be renumbered.
 */
object HcmProtocol {
    const val HEADER = "HCM"

    /** Empty payload, server echoes it straight back; used as a connection heartbeat. */
    const val OBJ_KEEPALIVE = 0

    /** Client -> server, sent immediately after connecting. Payload: raw device name (not JSON). */
    const val OBJ_HCNAME = 1

    /** Server -> client, sent in reply to OBJ_HCNAME. Payload: raw server name (not JSON). Receiving
     *  this is what marks the connection as fully "connected". */
    const val OBJ_SERVERNAME = 2

    /** Client -> server. Payload: JSON {Latitude, Longitude, Accuracy, BatteryLevel, TimeStamp}. */
    const val OBJ_GPS_LOCATION = 10

    const val OBJ_MESSAGE = 11

    /** Server -> client. Payload: JSON {"interval": <seconds>}. 0 (or absent) means "no explicit
     *  interval requested, use the app's own default behaviour". */
    const val OBJ_LOCATION_INTERVAL = 30

    // --- Admin mode (see AdminTcpClient) ---------------------------------------------------
    // Capability-gated, not session-gated: OBJ_ADMIN_CAPABILITY only ever controls whether the
    // UI *offers* the swipe-left admin screen. Every actual privileged request
    // (OBJ_ADMIN_AUTH_REQUEST / OBJ_LOCATION_HISTORY_REQUEST) is independently re-checked by the
    // server against the database on every call - see the C++ server's AdminController.

    /** Server -> client. Payload: JSON {"isAdmin": <bool>}. Pushed whenever this client's admin
     *  capability changes (mirrors OBJ_LOCATION_INTERVAL's push pattern). */
    const val OBJ_ADMIN_CAPABILITY = 39

    /** Client -> server. Payload: JSON {"code": "<pin>"}. The PIN is compared server-side only -
     *  it is never sent back to any client. */
    const val OBJ_ADMIN_AUTH_REQUEST = 40

    /** Server -> client. Payload: JSON {"success": <bool>}. Reply to OBJ_ADMIN_AUTH_REQUEST, and
     *  also (re)sent with success=false if a later OBJ_LOCATION_HISTORY_REQUEST turns out not to
     *  be authorized after all. */
    const val OBJ_ADMIN_AUTH_RESULT = 41

    /** Server -> client. Payload: JSON {"clients": [<name>, ...]}. Sent right after a successful
     *  OBJ_ADMIN_AUTH_RESULT, to populate the admin screen's client picker. */
    const val OBJ_CLIENT_LIST = 42

    /** Client -> server. Payload: JSON {"client": "<name>", "minutes": <int>}. Sent whenever the
     *  selected client (or the requested window) changes on the admin screen. */
    const val OBJ_LOCATION_HISTORY_REQUEST = 43

    /** Server -> client. Payload: JSON {"client": "<name>", "points": [{"lat", "lon", "timestamp"}, ...],
     *  "geofence"?: {"lat", "lon", "radius", "createdAt", "updatedAt"}}, points oldest first. Reply
     *  to OBJ_LOCATION_HISTORY_REQUEST. The "geofence" field is only present when the requested
     *  client currently has an active geofence. */
    const val OBJ_LOCATION_HISTORY_RESPONSE = 44

    /** Client -> server. Payload: JSON {"active": <bool>, "lat", "lon", "radius"}. Sent when this
     *  client creates (active=true) or clears (active=false) its stationary geofence - see
     *  location/LocationLoggingService. Not admin-gated - ordinary telemetry, same trust level as
     *  OBJ_GPS_LOCATION, sent over the primary connection (not the admin one). */
    const val OBJ_GEOFENCE_STATUS = 45

    internal const val HEADER_TOTAL_LENGTH = 6 // "HCM" (3) + length (2) + objectId (1)

    val HEADER_BYTES: ByteArray = HEADER.toByteArray(Charsets.US_ASCII)

    fun encodeFrame(objectId: Int, payload: ByteArray): ByteArray {
        val length = payload.size
        require(length <= 0xFFFF) { "Payload too large for an HCM frame: $length bytes" }
        val out = ByteArray(HEADER_TOTAL_LENGTH + length)
        HEADER_BYTES.copyInto(out, 0)
        out[3] = ((length shr 8) and 0xFF).toByte()
        out[4] = (length and 0xFF).toByte()
        out[5] = (objectId and 0xFF).toByte()
        payload.copyInto(out, HEADER_TOTAL_LENGTH)
        return out
    }
}

data class HcmFrame(val objectId: Int, val payload: ByteArray)

/**
 * Incrementally reassembles [HcmFrame]s out of raw bytes read off the socket. Mirrors the
 * resynchronising buffer search used by the C++ server's `ClientSocket::processBuffer`: it
 * scans for the "HCM" header rather than assuming byte 0 of the buffer is always a frame start,
 * so a stray/corrupt byte on the wire can't wedge the connection - it just gets skipped.
 *
 * Not thread-safe; each socket owns exactly one decoder, fed from a single reader thread.
 */
class HcmFrameDecoder {
    private var buffer = ByteArray(0)

    /** Feed newly-read bytes in; returns any frames that are now complete (possibly more than one,
     *  possibly none). */
    fun feed(data: ByteArray, offset: Int, length: Int): List<HcmFrame> {
        buffer = if (buffer.isEmpty()) {
            data.copyOfRange(offset, offset + length)
        } else {
            buffer + data.copyOfRange(offset, offset + length)
        }

        val frames = mutableListOf<HcmFrame>()
        val headerLen = HcmProtocol.HEADER_BYTES.size
        while (true) {
            val headerStart = indexOfHeader(buffer)
            if (headerStart < 0) {
                // No header anywhere in the buffer. Keep the last (headerLen - 1) bytes in case
                // they're the start of a header split across two reads; drop the rest so a
                // garbage stream can't grow the buffer forever.
                buffer = if (buffer.size >= headerLen) buffer.copyOfRange(buffer.size - (headerLen - 1), buffer.size) else buffer
                return frames
            }
            if (headerStart > 0) {
                buffer = buffer.copyOfRange(headerStart, buffer.size)
            }

            if (buffer.size < HcmProtocol.HEADER_TOTAL_LENGTH) return frames

            val dataLength = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
            val objectId = buffer[5].toInt() and 0xFF
            val totalLength = HcmProtocol.HEADER_TOTAL_LENGTH + dataLength
            if (buffer.size < totalLength) return frames

            val payload = buffer.copyOfRange(HcmProtocol.HEADER_TOTAL_LENGTH, totalLength)
            frames += HcmFrame(objectId, payload)
            buffer = buffer.copyOfRange(totalLength, buffer.size)
        }
    }

    private fun indexOfHeader(data: ByteArray): Int {
        val header = HcmProtocol.HEADER_BYTES
        if (data.size < header.size) return -1
        outer@ for (i in 0..data.size - header.size) {
            for (j in header.indices) {
                if (data[i + j] != header[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

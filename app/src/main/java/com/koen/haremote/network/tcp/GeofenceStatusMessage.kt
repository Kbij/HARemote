package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_GEOFENCE_STATUS] payload. Client -> server, sent when the client
 * creates or clears its own stationary geofence (see location/LocationLoggingService). Plain
 * lowercase JSON keys, plain milliseconds-since-epoch - unlike GpsLocationMessage this is a
 * fresh message with no legacy ".NET" TimeStamp format to reproduce.
 */
data class GeofenceStatusMessage(
    val active: Boolean,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Double = 0.0
) {
    fun toJson(): String = JSONObject().apply {
        put("active", active)
        put("lat", latitude)
        put("lon", longitude)
        put("radius", radiusMeters)
    }.toString()
}

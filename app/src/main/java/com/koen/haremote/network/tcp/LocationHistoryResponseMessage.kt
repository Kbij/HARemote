package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_LOCATION_HISTORY_RESPONSE] payload. Server -> client, reply to
 * [LocationHistoryRequestMessage]. [timestampMillis] is plain milliseconds-since-epoch (unlike
 * [GpsLocationMessage]'s legacy ".NET" TimeStamp format) since this is a fresh response field
 * with no backward-compatibility constraint - see the C++ server's LocationHistoryResponse.
 */
data class LocationHistoryPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long
)

/** Present only when the requested client currently has an active stationary geofence - see the
 *  C++ server's DalNs::GeofenceInfo / LocationHistoryResponse::setGeofence. */
data class GeofenceInfo(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class LocationHistoryResponseMessage(
    val client: String,
    val points: List<LocationHistoryPoint>,
    val geofence: GeofenceInfo? = null,
    /** Client.lastMessage server-side - when the server last heard *anything* from this client
     *  (including keepalives), distinct from the last GPS fix's timestamp above. Always present
     *  (0 = unknown), see the C++ server's LocationHistoryResponse::setLastConnection. */
    val lastConnectionMillis: Long = 0L
) {
    companion object {
        fun fromJson(json: String): LocationHistoryResponseMessage {
            val obj = JSONObject(json)
            val client = obj.optString("client", "")
            val lastConnectionMillis = obj.optLong("lastConnection", 0L)
            val array = obj.optJSONArray("points")
            val points = mutableListOf<LocationHistoryPoint>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val point = array.getJSONObject(i)
                    points.add(
                        LocationHistoryPoint(
                            latitude = point.optDouble("lat", 0.0),
                            longitude = point.optDouble("lon", 0.0),
                            timestampMillis = point.optLong("timestamp", 0L)
                        )
                    )
                }
            }
            val geofenceObj = obj.optJSONObject("geofence")
            val geofence = geofenceObj?.let {
                GeofenceInfo(
                    latitude = it.optDouble("lat", 0.0),
                    longitude = it.optDouble("lon", 0.0),
                    radiusMeters = it.optDouble("radius", 0.0),
                    createdAtMillis = it.optLong("createdAt", 0L),
                    updatedAtMillis = it.optLong("updatedAt", 0L)
                )
            }
            return LocationHistoryResponseMessage(client, points, geofence, lastConnectionMillis)
        }
    }
}

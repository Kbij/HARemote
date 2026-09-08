package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_LOCATION_INTERVAL] payload, sent by the server whenever it wants this
 * client to report its location at a different cadence (see `Server::maintenanceThread` /
 * `HomeControlDal::locationInterval` server-side - the interval is configured per client name).
 *
 * `intervalSeconds <= 0` means "no explicit interval requested" - the app should fall back to its
 * own default adaptive behaviour. A positive value is how often (in seconds) the server wants a
 * location update, and also drives how accurate that fix needs to be: see
 * `LocationLoggingService.priorityForInterval`.
 */
data class LocationIntervalMessage(val intervalSeconds: Int) {
    companion object {
        fun fromJson(json: String): LocationIntervalMessage {
            val obj = JSONObject(json)
            return LocationIntervalMessage(obj.optInt("interval", 0))
        }
    }
}

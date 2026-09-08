package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_LOCATION_HISTORY_REQUEST] payload. Client -> server, sent whenever
 * the selected client (or the requested window) changes on the admin screen. Sending this does
 * *not* rely on an earlier successful [AdminAuthRequestMessage] on this same connection - the
 * server re-checks the requester's own admin capability live on every one of these.
 */
data class LocationHistoryRequestMessage(val client: String, val minutes: Int = DEFAULT_MINUTES) {
    fun toJson(): String = JSONObject().apply {
        put("client", client)
        put("minutes", minutes)
    }.toString()

    companion object {
        const val DEFAULT_MINUTES = 60
    }
}

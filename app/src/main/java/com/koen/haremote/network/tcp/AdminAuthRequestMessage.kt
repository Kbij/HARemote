package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_ADMIN_AUTH_REQUEST] payload. Client -> server, sent once the user
 * finishes typing the numeric PIN on the swipe-left admin screen. Kept as a string (not a
 * number) so a leading zero in the PIN survives the round trip.
 */
data class AdminAuthRequestMessage(val code: String) {
    fun toJson(): String = JSONObject().apply {
        put("code", code)
    }.toString()
}

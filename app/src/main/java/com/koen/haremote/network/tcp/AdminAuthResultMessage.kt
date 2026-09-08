package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_ADMIN_AUTH_RESULT] payload. Server -> client, reply to
 * [AdminAuthRequestMessage] (and also re-sent with success=false if a later
 * [LocationHistoryRequestMessage] turns out not to be authorized after all - see
 * AdminTcpClient.Listener.onAuthResult).
 */
data class AdminAuthResultMessage(val success: Boolean) {
    companion object {
        fun fromJson(json: String): AdminAuthResultMessage {
            val obj = JSONObject(json)
            return AdminAuthResultMessage(obj.optBoolean("success", false))
        }
    }
}

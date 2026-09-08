package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_ADMIN_CAPABILITY] payload. Server -> client, pushed whenever this
 * client's admin capability changes. Purely a UI affordance - see [HcmProtocol]'s note on the
 * admin objectIds for why this can't itself be trusted as a security boundary.
 */
data class AdminCapabilityMessage(val isAdmin: Boolean) {
    companion object {
        fun fromJson(json: String): AdminCapabilityMessage {
            val obj = JSONObject(json)
            return AdminCapabilityMessage(obj.optBoolean("isAdmin", false))
        }
    }
}

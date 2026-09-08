package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_CLIENT_LIST] payload. Server -> client, sent right after a
 * successful [AdminAuthResultMessage], to populate the admin screen's client picker. Lists
 * every client known to the server's database, not only the currently-connected ones.
 */
data class ClientListMessage(val clients: List<String>) {
    companion object {
        fun fromJson(json: String): ClientListMessage {
            val obj = JSONObject(json)
            val array = obj.optJSONArray("clients")
            val names = mutableListOf<String>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    names.add(array.getString(i))
                }
            }
            return ClientListMessage(names)
        }
    }
}

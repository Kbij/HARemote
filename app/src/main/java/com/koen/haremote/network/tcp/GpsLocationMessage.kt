package com.koen.haremote.network.tcp

import org.json.JSONObject

/**
 * objectId [HcmProtocol.OBJ_GPS_LOCATION] payload.
 *
 * The field names (PascalCase) and the `TimeStamp` format are dictated by the server: it was
 * originally written against .NET's `DataContractJsonSerializer`, which serialises `DateTime` as
 * `/Date(<epoch millis>+<tz offset>)/`. The C++ server (`GpsLocation::GpsLocation`, see
 * `CommObjects/GpsLocation.cpp`) parses that exact shape - it looks for `(` and `+` and reads the
 * milliseconds in between, ignoring everything after the `+` (timezone is ignored server-side) -
 * so we reproduce it verbatim rather than sending a "normal" ISO timestamp. The `+0000` suffix is
 * required syntactically (the parser searches for the `+`), but its value is never used.
 */
data class GpsLocationMessage(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val batteryLevelPercent: Int,
    val timestampMillis: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("Latitude", latitude)
        put("Longitude", longitude)
        put("Accuracy", accuracy)
        put("BatteryLevel", batteryLevelPercent)
        put("TimeStamp", "/Date($timestampMillis+0000)/")
    }.toString()
}

package com.koen.haremote.location

import kotlinx.serialization.Serializable

/** JSON body posted to the configurable REST URL on every location update. */
@Serializable
data class LocationPayload(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val provider: String,
    val time: Long
)

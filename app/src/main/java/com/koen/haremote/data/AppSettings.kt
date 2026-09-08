package com.koen.haremote.data

import kotlinx.serialization.Serializable

/**
 * The full app configuration: the REST host(s), the 6 Home Assistant buttons, and the
 * background GPS logging settings. This entire object is what gets exported/imported as JSON.
 *
 * Location updates go over a persistent TCP connection to the HomeControl server
 * (see `network.tcp.TcpLocationClient`), not a REST call — [tcpServerHost]/[tcpServerPort]
 * is where that server listens.
 */
@Serializable
data class AppSettings(
    val hosts: List<HostConfig> = defaultHosts(),
    val buttons: List<ButtonConfig> = defaultButtons(),
    val tcpServerHost: String = "",
    val tcpServerPort: Int = DEFAULT_TCP_PORT,
    val gpsLoggingEnabled: Boolean = false
) {
    companion object {
        const val BUTTON_COUNT = 6
        const val DEFAULT_TCP_PORT = 5678
    }
}

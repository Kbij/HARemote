package com.koen.haremote.network.tcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, last-known connection state of the primary [TcpLocationClient] - the one
 * `LocationLoggingService` owns and uses for GPS reporting (not [AdminTcpClient], which has its
 * own `adminConnected` StateFlow on the ViewModel already).
 *
 * The service and the UI (HomeScreen's connection LED, via HaRemoteViewModel) are different
 * Android components with no direct reference to each other - the service isn't bound, just
 * started - so a bind/Messenger round trip would be a lot of ceremony for one boolean. A plain
 * process-wide singleton is enough: there's exactly one `LocationLoggingService` instance (the
 * only writer) and the UI only ever reads the latest value, never needs to react to *how* it got
 * there.
 *
 * Defaults to `false` or, more importantly, stays `false` whenever the service isn't running at
 * all (GPS logging disabled) - which is the correct thing for the LED to show.
 */
object TcpConnectionStatus {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun update(isConnected: Boolean) {
        _connected.value = isConnected
    }
}

package com.koen.haremote

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.koen.haremote.data.AppSettings
import com.koen.haremote.data.ButtonConfig
import com.koen.haremote.data.HostConfig
import com.koen.haremote.data.SettingsRepository
import com.koen.haremote.location.DiagnosticLogger
import com.koen.haremote.location.LocationLoggingService
import com.koen.haremote.network.RestCaller
import com.koen.haremote.network.tcp.AdminTcpClient
import com.koen.haremote.network.tcp.LocationHistoryRequestMessage
import com.koen.haremote.network.tcp.LocationHistoryResponseMessage
import com.koen.haremote.network.tcp.TcpConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG_ADMIN = "AdminMode"

class HaRemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    /** Ids of buttons whose REST call is currently in flight - lets HomeScreen show a
     *  pulsing/disabled state on just that button instead of only finding out afterwards
     *  via [lastResult]. A Set rather than a single id in case two different buttons are
     *  pressed in quick succession (each call runs independently). */
    private val _pendingButtonIds = MutableStateFlow<Set<Int>>(emptySet())
    val pendingButtonIds: StateFlow<Set<Int>> = _pendingButtonIds.asStateFlow()

    /** The primary (GPS-logging) TCP connection's state, for HomeScreen's connection LED - see
     *  [TcpConnectionStatus] for why this is a re-exported singleton rather than something this
     *  ViewModel tracks itself (that connection lives in LocationLoggingService, a separate,
     *  unbound component). Distinct from [adminConnected], which is the *second*, admin-only
     *  connection this ViewModel does own directly. */
    val tcpConnected: StateFlow<Boolean> = TcpConnectionStatus.connected

    // Admin mode: a second, independent TCP connection from AdminTcpClient (see its class doc
    // for why it isn't shared with LocationLoggingService's TcpLocationClient), kept alive for
    // as long as this ViewModel is - i.e. for as long as MainActivity is - so isAdminCapable can
    // reflect a server push received at any point, not only while the admin screen is open.
    private val _isAdminCapable = MutableStateFlow(false)
    val isAdminCapable: StateFlow<Boolean> = _isAdminCapable.asStateFlow()

    /** null = no attempt yet (or the result was already consumed). */
    private val _adminAuthResult = MutableStateFlow<Boolean?>(null)
    val adminAuthResult: StateFlow<Boolean?> = _adminAuthResult.asStateFlow()

    private val _adminClients = MutableStateFlow<List<String>>(emptyList())
    val adminClients: StateFlow<List<String>> = _adminClients.asStateFlow()

    private val _adminLocationHistory = MutableStateFlow<LocationHistoryResponseMessage?>(null)
    val adminLocationHistory: StateFlow<LocationHistoryResponseMessage?> = _adminLocationHistory.asStateFlow()

    private val _adminConnected = MutableStateFlow(false)
    val adminConnected: StateFlow<Boolean> = _adminConnected.asStateFlow()

    private val adminTcpClient = AdminTcpClient(
        deviceName = Build.MODEL,
        listener = object : AdminTcpClient.Listener {
            override fun onAdminCapabilityChanged(isAdmin: Boolean) {
                DiagnosticLogger.log(getApplication(), TAG_ADMIN, "Admin-capability gewijzigd: $isAdmin")
                _isAdminCapable.value = isAdmin
            }

            override fun onAuthResult(success: Boolean) {
                DiagnosticLogger.log(getApplication(), TAG_ADMIN, "Auth-resultaat ontvangen: success=$success")
                _adminAuthResult.value = success
            }

            override fun onClientList(clients: List<String>) {
                _adminClients.value = clients
            }

            override fun onLocationHistory(response: LocationHistoryResponseMessage) {
                _adminLocationHistory.value = response
            }

            override fun onConnectionStateChanged(connected: Boolean) {
                DiagnosticLogger.log(getApplication(), TAG_ADMIN, "Verbinding gewijzigd: connected=$connected")
                _adminConnected.value = connected
            }
        }
    )

    init {
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { loaded ->
                _settings.value = loaded
                adminTcpClient.updateEndpoint(loaded.tcpServerHost, loaded.tcpServerPort)
            }
        }
        adminTcpClient.start(host = "", port = 0) // real host/port arrive via the collector above
    }

    fun pressButton(button: ButtonConfig) {
        if (button.id in _pendingButtonIds.value) return // already running - ignore a double-tap
        _pendingButtonIds.value = _pendingButtonIds.value + button.id
        viewModelScope.launch {
            try {
                val host = _settings.value.hosts.firstOrNull { it.id == button.hostId }
                when (val result = RestCaller.invoke(getApplication(), button, host)) {
                    is RestCaller.CallResult.Success -> _lastResult.value = "${button.label}: OK"
                    is RestCaller.CallResult.Failure -> _lastResult.value = "${button.label}: ${result.message}"
                }
            } finally {
                _pendingButtonIds.value = _pendingButtonIds.value - button.id
            }
        }
    }

    fun consumeLastResult() {
        _lastResult.value = null
    }

    fun updateButton(updated: ButtonConfig) {
        val newButtons = _settings.value.buttons.map { if (it.id == updated.id) updated else it }
        persist(_settings.value.copy(buttons = newButtons))
    }

    fun updateHost(updated: HostConfig) {
        val newHosts = _settings.value.hosts.map { if (it.id == updated.id) updated else it }
        persist(_settings.value.copy(hosts = newHosts))
    }

    /** Creates a new, mostly-empty host and returns it immediately (safe to navigate to its
     *  edit screen right away - [persist] updates [_settings] synchronously before handing
     *  the save itself off to the repository coroutine). */
    fun addHost(): HostConfig {
        val nextId = (_settings.value.hosts.maxOfOrNull { it.id } ?: 0) + 1
        val newHost = HostConfig(id = nextId, name = "Nieuwe host")
        persist(_settings.value.copy(hosts = _settings.value.hosts + newHost))
        return newHost
    }

    /** Un-links [id] from any button that was using it rather than deleting those buttons -
     *  a button without a host just shows "Geen host gekoppeld" until re-assigned. */
    fun deleteHost(id: Int) {
        val newHosts = _settings.value.hosts.filterNot { it.id == id }
        val newButtons = _settings.value.buttons.map {
            if (it.hostId == id) it.copy(hostId = null) else it
        }
        persist(_settings.value.copy(hosts = newHosts, buttons = newButtons))
    }

    fun setTcpServerHost(host: String) {
        persist(_settings.value.copy(tcpServerHost = host))
    }

    fun setTcpServerPort(portText: String) {
        val port = portText.toIntOrNull()
        if (portText.isNotEmpty() && (port == null || port !in 1..65535)) return
        persist(_settings.value.copy(tcpServerPort = port ?: 0))
    }

    fun setGpsLoggingEnabled(enabled: Boolean) {
        persist(_settings.value.copy(gpsLoggingEnabled = enabled))
        val context = getApplication<Application>()
        if (enabled) {
            LocationLoggingService.start(context)
        } else {
            LocationLoggingService.stop(context)
        }
    }

    fun exportJson(): String = repository.toJson(_settings.value)

    fun importJson(text: String): Boolean {
        val result = repository.fromJson(text)
        return result.fold(
            onSuccess = { imported ->
                persist(imported)
                val context = getApplication<Application>()
                if (imported.gpsLoggingEnabled) {
                    LocationLoggingService.start(context)
                } else {
                    LocationLoggingService.stop(context)
                }
                true
            },
            onFailure = { false }
        )
    }

    private fun persist(newSettings: AppSettings) {
        _settings.value = newSettings
        viewModelScope.launch { repository.save(newSettings) }
    }

    /** Called when the swipe gesture opens the admin PIN screen. [AdminTcpClient] normally waits
     *  out a 15s backoff before retrying a dropped connection - fine for a connection nobody is
     *  watching, but not for a screen the user just opened and is looking at right now, so this
     *  nudges an immediate reconnect attempt if it isn't already up. See
     *  [AdminTcpClient.reconnectNow]. */
    fun onAdminScreenOpened() {
        DiagnosticLogger.log(
            getApplication(), TAG_ADMIN,
            "Admin-scherm geopend, verbonden=${adminTcpClient.isConnected()}"
        )
        adminTcpClient.reconnectNow()
    }

    /** Sends the PIN typed on the admin gate screen. Result arrives via [adminAuthResult]. */
    fun submitAdminCode(code: String) {
        DiagnosticLogger.log(getApplication(), TAG_ADMIN, "Admin-code verzonden")
        _adminAuthResult.value = null
        adminTcpClient.sendAuthRequest(code)
    }

    fun consumeAdminAuthResult() {
        _adminAuthResult.value = null
    }

    /** Requests the last [minutes] of location history for [client]. Result arrives via
     *  [adminLocationHistory]. Re-sent by the admin screen whenever the selected client or
     *  window changes - the server re-checks admin capability live on every call, there's no
     *  "already authenticated" shortcut to rely on here. */
    fun requestLocationHistory(client: String, minutes: Int = LocationHistoryRequestMessage.DEFAULT_MINUTES) {
        adminTcpClient.sendLocationHistoryRequest(client, minutes)
    }

    /** Clears everything from a previous admin session so leaving and re-entering admin mode
     *  doesn't briefly show stale data (an old auth result, a stale client list/history) before
     *  fresh data arrives. */
    fun resetAdminSession() {
        _adminAuthResult.value = null
        _adminClients.value = emptyList()
        _adminLocationHistory.value = null
    }

    override fun onCleared() {
        super.onCleared()
        adminTcpClient.stop()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HaRemoteViewModel(application) as T
                }
            }
    }
}

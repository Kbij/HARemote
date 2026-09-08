package com.koen.haremote.network.tcp

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A second, independent persistent TCP connection to the HomeControl server, used only for
 * admin-mode traffic (see [HcmProtocol]'s OBJ_ADMIN_* / OBJ_CLIENT_LIST / OBJ_LOCATION_HISTORY_*
 * objectIds). Kept entirely separate from [TcpLocationClient] (which [LocationLoggingService]
 * owns) rather than multiplexed onto it, for two reasons:
 *
 * - [TcpLocationClient] only runs while GPS logging is enabled and the foreground service is
 *   alive; admin mode needs to work independently of that toggle, for as long as the app itself
 *   is in the foreground (owned by the ViewModel, started/stopped with it).
 * - The C++ server already supports a client name having more than one simultaneously-open
 *   socket (see `Server::maintenanceThread`'s "still has an active connection open" handling),
 *   so two connections under the same device name is an already-supported shape, not a hack.
 *
 * Unlike [TcpLocationClient], this doesn't need to guarantee delivery of any single message
 * across a reconnect: auth/history requests are interactive and cheap to just retry from the UI
 * if they land while disconnected, so there's no pending-frame queue here.
 */
class AdminTcpClient(
    private val deviceName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onAdminCapabilityChanged(isAdmin: Boolean)
        fun onAuthResult(success: Boolean)
        fun onClientList(clients: List<String>)
        fun onLocationHistory(response: LocationHistoryResponseMessage)

        /** Called when an OBJ_LOCATION_HISTORY_RESPONSE frame arrived but [LocationHistoryResponseMessage.fromJson]
         *  threw on it - unlike the other frame types handled in [connectAndPump], this one gets
         *  surfaced instead of silently dropped via `getOrNull()`: it's the only lead available
         *  for diagnosing "selected a client, nothing showed up" reports where the request
         *  clearly went out and something clearly came back, yet [onLocationHistory] never fires. */
        fun onLocationHistoryParseFailed(rawPayload: String, error: Throwable) {}

        /** Purely informational, e.g. for showing a "niet verbonden" hint on the admin screen. */
        fun onConnectionStateChanged(connected: Boolean) {}
    }

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var currentSocket: Socket? = null
    @Volatile private var reconnectImmediately = false
    private var thread: Thread? = null
    private val writeLock = Any()
    private val sleepLock = Object()

    // sendAuthRequest/sendLocationHistoryRequest are called straight from Compose click
    // handlers (see HaRemoteViewModel.submitAdminCode/requestLocationHistory) - i.e. on the
    // main thread. A blocking socket write there is a guaranteed NetworkOnMainThreadException
    // (confirmed by an actual crash log: AdminTcpClient.writeFrameLocked <- sendAuthRequest <-
    // HaRemoteViewModel.submitAdminCode <- the PIN screen's "Bevestigen" button, main thread
    // throughout). All outbound writes are funnelled through this single background executor
    // instead, so the caller's thread never touches the socket.
    private var writeExecutor: ExecutorService? = null
    private val writeExecutorLock = Any()

    @Synchronized
    fun start(host: String, port: Int) {
        this.host = host
        this.port = port
        if (running) return
        running = true
        synchronized(writeExecutorLock) {
            writeExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "AdminTcpClient-write").apply { isDaemon = true }
            }
        }
        thread = Thread(::runLoop, "AdminTcpClient").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        closeCurrentSocket()
        wakeSleeper()
        thread = null
        setConnected(false)
        synchronized(writeExecutorLock) {
            writeExecutor?.shutdownNow()
            writeExecutor = null
        }
    }

    @Synchronized
    fun updateEndpoint(newHost: String, newPort: Int) {
        if (newHost == host && newPort == port) return
        host = newHost
        port = newPort
        if (running) {
            reconnectImmediately = true
            closeCurrentSocket()
            wakeSleeper()
        }
    }

    fun isConnected(): Boolean = connected

    /** Nudges an immediate reconnect attempt instead of waiting out [RECONNECT_DELAY_MS] - call
     *  this when the user opens the admin PIN/map flow. That 15s passive backoff (copied from
     *  [TcpLocationClient], where it's fine because nothing is watching in real time behind a
     *  foreground service) is far too slow for a screen the user is actively looking at: if this
     *  connection happened to drop shortly before, the admin screen would otherwise sit on
     *  "Niet verbonden met server" for up to 15s with no way to speed that up. No-op if already
     *  connected or not running. */
    @Synchronized
    fun reconnectNow() {
        if (!running || connected) return
        reconnectImmediately = true
        closeCurrentSocket() // unblocks a pending connect attempt; runLoop reconnects right away
        wakeSleeper() // in case it's currently waiting out the backoff instead
    }

    /** @return true if the request was actually sent (i.e. the connection was up). */
    fun sendAuthRequest(code: String): Boolean =
        send(HcmProtocol.OBJ_ADMIN_AUTH_REQUEST, AdminAuthRequestMessage(code).toJson())

    /** @return true if the request was actually sent (i.e. the connection was up). */
    fun sendLocationHistoryRequest(client: String, minutes: Int = LocationHistoryRequestMessage.DEFAULT_MINUTES): Boolean =
        send(HcmProtocol.OBJ_LOCATION_HISTORY_REQUEST, LocationHistoryRequestMessage(client, minutes).toJson())

    /** @return true if the write was actually queued (i.e. the connection looked up at this
     *  moment) - NOT a guarantee it was written successfully, since the write itself now always
     *  happens asynchronously on [writeExecutor] (see its doc for why). Callers here have never
     *  used the return value for more than a same-thread "did this even have a chance" check
     *  (see [HaRemoteViewModel.submitAdminCode]), so that's unchanged in practice. */
    private fun send(objectId: Int, jsonPayload: String): Boolean {
        val socket = currentSocket
        if (!connected || socket == null || !socket.isConnected || socket.isClosed) return false
        val frame = HcmProtocol.encodeFrame(objectId, jsonPayload.toByteArray(Charsets.US_ASCII))
        val executor = synchronized(writeExecutorLock) { writeExecutor }
        if (executor == null || executor.isShutdown) return false
        executor.execute {
            writeFrame(socket, frame)
        }
        return true
    }

    private fun runLoop() {
        while (running) {
            try {
                connectAndPump()
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed: ${e.message}")
            }
            setConnected(false)
            if (!running) return
            if (reconnectImmediately) {
                reconnectImmediately = false
            } else {
                sleepBeforeReconnect()
            }
        }
    }

    private fun sleepBeforeReconnect() {
        synchronized(sleepLock) {
            try {
                sleepLock.wait(RECONNECT_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun wakeSleeper() {
        synchronized(sleepLock) { sleepLock.notifyAll() }
    }

    private fun connectAndPump() {
        val targetHost = host
        val targetPort = port
        if (targetHost.isBlank() || targetPort <= 0) {
            Log.w(TAG, "No TCP server host/port configured yet, not connecting")
            return
        }

        Log.i(TAG, "Connecting to $targetHost:$targetPort")
        val socket = Socket()
        currentSocket = socket
        try {
            socket.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = KEEPALIVE_INTERVAL_MS

            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // Must happen immediately: the server only allows ~1s in the "connecting" state
            // before it drops the socket (see `Client::isInactive` server-side).
            writeFrameLocked(output, HcmProtocol.encodeFrame(HcmProtocol.OBJ_HCNAME, deviceName.toByteArray(Charsets.US_ASCII)))

            val decoder = HcmFrameDecoder()
            val readBuffer = ByteArray(1024)
            var outstandingKeepAlive = false

            while (running && currentSocket === socket) {
                val bytesRead = try {
                    input.read(readBuffer)
                } catch (e: SocketTimeoutException) {
                    if (connected) {
                        if (outstandingKeepAlive) {
                            Log.w(TAG, "Keepalive not acknowledged in time, reconnecting")
                            return
                        }
                        writeFrameLocked(output, HcmProtocol.encodeFrame(HcmProtocol.OBJ_KEEPALIVE, ByteArray(0)))
                        outstandingKeepAlive = true
                    }
                    continue
                }

                if (bytesRead < 0) {
                    Log.i(TAG, "Server closed the connection")
                    return
                }
                if (bytesRead == 0) continue

                for (frame in decoder.feed(readBuffer, 0, bytesRead)) {
                    when (frame.objectId) {
                        HcmProtocol.OBJ_SERVERNAME -> {
                            Log.i(TAG, "Handshake complete")
                            setConnected(true)
                        }
                        HcmProtocol.OBJ_KEEPALIVE -> {
                            outstandingKeepAlive = false
                        }
                        HcmProtocol.OBJ_ADMIN_CAPABILITY -> {
                            runCatching {
                                AdminCapabilityMessage.fromJson(String(frame.payload, Charsets.US_ASCII))
                            }.getOrNull()?.let { listener.onAdminCapabilityChanged(it.isAdmin) }
                        }
                        HcmProtocol.OBJ_ADMIN_AUTH_RESULT -> {
                            runCatching {
                                AdminAuthResultMessage.fromJson(String(frame.payload, Charsets.US_ASCII))
                            }.getOrNull()?.let { listener.onAuthResult(it.success) }
                        }
                        HcmProtocol.OBJ_CLIENT_LIST -> {
                            runCatching {
                                ClientListMessage.fromJson(String(frame.payload, Charsets.US_ASCII))
                            }.getOrNull()?.let { listener.onClientList(it.clients) }
                        }
                        HcmProtocol.OBJ_LOCATION_HISTORY_RESPONSE -> {
                            val raw = String(frame.payload, Charsets.US_ASCII)
                            runCatching { LocationHistoryResponseMessage.fromJson(raw) }
                                .onSuccess { listener.onLocationHistory(it) }
                                .onFailure { e -> listener.onLocationHistoryParseFailed(raw, e) }
                        }
                        else -> Unit // Not relevant to admin mode.
                    }
                }
            }
        } finally {
            closeQuietly(socket)
            if (currentSocket === socket) currentSocket = null
        }
    }

    private fun writeFrameLocked(output: OutputStream, frame: ByteArray) {
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    private fun writeFrame(socket: Socket, frame: ByteArray): Boolean {
        return try {
            writeFrameLocked(socket.getOutputStream(), frame)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            listener.onConnectionStateChanged(value)
        }
    }

    private fun closeCurrentSocket() {
        currentSocket?.let { closeQuietly(it) }
        currentSocket = null
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    companion object {
        private const val TAG = "AdminTcpClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val KEEPALIVE_INTERVAL_MS = 30_000
        private const val RECONNECT_DELAY_MS = 15_000L
    }
}

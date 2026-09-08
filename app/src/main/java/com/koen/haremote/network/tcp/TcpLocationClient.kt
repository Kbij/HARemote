package com.koen.haremote.network.tcp

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent, unencrypted TCP connection to the HomeControl server, replacing the old
 * REST/webhook POST for location updates. Protocol details: [HcmProtocol].
 *
 * Deliberately modelled after the old Xamarin `CloudSocket` (see `HomeControl/Comm/CloudSocket.cs`
 * in the original app): a single dedicated background thread owns the socket and does
 * synchronous, blocking reads. There is no TLS yet - encryption is a planned follow-up, tracked
 * separately; for now this talks to the server exactly like the legacy client did.
 *
 * Lifecycle: call [start] once (e.g. from the foreground service's `onCreate`/`onStartCommand`),
 * [updateEndpoint] whenever the user changes the host/port in Settings, and [stop] when the
 * service is torn down. [sendLocation] is safe to call from any thread at any time.
 */
class TcpLocationClient(
    private val deviceName: String,
    private val listener: Listener
) {
    interface Listener {
        /** Called (from the connection's background thread) whenever the server pushes a new
         *  desired location-update interval. `intervalSeconds <= 0` means "no explicit interval,
         *  use the default behaviour". */
        fun onLocationIntervalReceived(intervalSeconds: Int)

        /** Purely informational, e.g. for the foreground-service notification text / logging. */
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
    private val pendingLocationFrame = AtomicReference<ByteArray?>(null)
    private val pendingGeofenceFrame = AtomicReference<ByteArray?>(null)

    // sendLocation() used to write straight to the socket on whatever thread called it - for
    // LocationLoggingService that's onLocationChanged, which (no Looper passed to
    // requestLocationUpdates) runs on the service's main thread. That's a guaranteed
    // NetworkOnMainThreadException; found the hard way via the exact same pattern in
    // AdminTcpClient (see its class doc). All outbound writes are funnelled through this single
    // background executor instead, so the caller's thread never touches the socket.
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
                Thread(r, "TcpLocationClient-write").apply { isDaemon = true }
            }
        }
        thread = Thread(::runLoop, "TcpLocationClient").apply {
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

    /** Call when the user changes the server host/port in Settings. Forces a reconnect to the
     *  new endpoint if it actually changed and the client is running. */
    @Synchronized
    fun updateEndpoint(newHost: String, newPort: Int) {
        if (newHost == host && newPort == port) return
        host = newHost
        port = newPort
        if (running) {
            Log.i(TAG, "Endpoint changed, reconnecting to $newHost:$newPort")
            reconnectImmediately = true
            closeCurrentSocket() // unblocks a pending read; runLoop reconnects right away
            wakeSleeper() // in case it's currently waiting out the reconnect backoff instead
        }
    }

    fun isConnected(): Boolean = connected

    /** Enqueue a location update. Sent immediately if connected; otherwise kept as the single
     *  latest pending update and flushed as soon as the connection is (re)established - older,
     *  superseded fixes are simply dropped, there is no point delivering stale locations late. */
    fun sendLocation(message: GpsLocationMessage) {
        val frame = HcmProtocol.encodeFrame(HcmProtocol.OBJ_GPS_LOCATION, message.toJson().toByteArray(Charsets.US_ASCII))
        val socket = currentSocket
        val executor = synchronized(writeExecutorLock) { writeExecutor }
        if (connected && socket != null && socket.isConnected && !socket.isClosed && executor != null && !executor.isShutdown) {
            executor.execute {
                if (!writeFrame(socket, frame)) {
                    pendingLocationFrame.set(frame)
                }
            }
        } else {
            pendingLocationFrame.set(frame)
        }
    }

    /** Enqueue a geofence status update (active=true to create/renew, active=false to clear) -
     *  see location/LocationLoggingService. Same queue-latest-only semantics as [sendLocation]:
     *  not admin-gated, ordinary telemetry over this primary connection. */
    fun sendGeofenceStatus(status: GeofenceStatusMessage) {
        val frame = HcmProtocol.encodeFrame(HcmProtocol.OBJ_GEOFENCE_STATUS, status.toJson().toByteArray(Charsets.US_ASCII))
        val socket = currentSocket
        val executor = synchronized(writeExecutorLock) { writeExecutor }
        if (connected && socket != null && socket.isConnected && !socket.isClosed && executor != null && !executor.isShutdown) {
            executor.execute {
                if (!writeFrame(socket, frame)) {
                    pendingGeofenceFrame.set(frame)
                }
            }
        } else {
            pendingGeofenceFrame.set(frame)
        }
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

    /** Waits out the reconnect backoff, unless [wakeSleeper] is called first (endpoint change or
     *  [stop]) - in which case it returns early and the caller re-checks [running]/[reconnectImmediately]. */
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
                    // Nothing received within the keepalive window.
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
                            Log.i(TAG, "Handshake complete: ${String(frame.payload, Charsets.US_ASCII)}")
                            setConnected(true)
                            pendingLocationFrame.getAndSet(null)?.let { writeFrameLocked(output, it) }
                            pendingGeofenceFrame.getAndSet(null)?.let { writeFrameLocked(output, it) }
                        }
                        HcmProtocol.OBJ_KEEPALIVE -> {
                            outstandingKeepAlive = false
                        }
                        HcmProtocol.OBJ_LOCATION_INTERVAL -> {
                            val interval = runCatching {
                                LocationIntervalMessage.fromJson(String(frame.payload, Charsets.US_ASCII))
                            }.getOrNull()
                            if (interval != null) {
                                listener.onLocationIntervalReceived(interval.intervalSeconds)
                            }
                        }
                        else -> Unit // Other object ids (RoomList, temperatures, ...) aren't relevant here.
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

    /** @return true if the frame was written, false if the socket wasn't actually usable. */
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
        private const val TAG = "TcpLocationClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val KEEPALIVE_INTERVAL_MS = 30_000
        private const val RECONNECT_DELAY_MS = 15_000L
    }
}

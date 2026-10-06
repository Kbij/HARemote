package com.koen.haremote.network.tcp

import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
 *
 * ## Reconnecting (reworked 6 okt 2026)
 *
 * The connection thread never gives up: whatever goes wrong (server down, no network, DNS
 * failure, a handshake that never completes, a half-open socket, an unexpected exception in a
 * callback) ends the current attempt and schedules the next one. The wait between attempts grows
 * from [RECONNECT_DELAYS_MS]`[0]` up to [MAX_RECONNECT_DELAY_MS] (5 minutes) and no further, so an
 * unreachable server is retried **at least every 5 minutes, indefinitely**, without hammering it
 * (or the battery) every 15 seconds for hours on end.
 *
 * Three things make that hold up in practice, not just on paper:
 * - the backoff is measured against wall time ([SystemClock.elapsedRealtime]), so time the CPU
 *   spent asleep counts - a thread that was frozen for 20 minutes retries the moment it runs
 *   again instead of sitting out the rest of its wait;
 * - [kick] lets the owner nudge the client from outside the thread - `LocationLoggingService`
 *   calls it from an [android.app.AlarmManager] alarm every 5 minutes while disconnected (which
 *   also wakes the CPU), from its watchdog, and (with `force = true`) the moment Android reports
 *   that a network became available;
 * - [ensureRunning] (part of every [kick]) is a supervisor: if the connection thread ever died
 *   or has made no progress for [STALL_THRESHOLD_MS], it is abandoned and replaced.
 *
 * ## Not losing locations (new 6 okt 2026)
 *
 * [sendLocation] used to keep only the single newest fix while disconnected, and treated any
 * fix as delivered the moment it was handed to the socket - which is not the same as the server
 * having received it (a half-open connection accepts writes for up to a minute before anyone
 * notices). Now every location goes into a persistent [LocationOutbox] first and is only removed
 * once the server has confirmed it.
 *
 * The protocol has no ACK for `OBJ_GPS_LOCATION`, but it doesn't need one: the server answers
 * every `OBJ_KEEPALIVE` by echoing it straight back, and processes frames strictly in the order
 * they arrive on a connection. So the client sends a batch of locations followed by one
 * keepalive; when *that* echo comes back, everything written before it has reached the server
 * and can be deleted. If the echo never comes (connection lost, server restarted, ...) nothing is
 * deleted and the same batch goes out again on the next connection - a location may therefore
 * occasionally arrive twice (harmless: same position, same timestamp), but is never silently
 * dropped. Locations carry their own timestamp ([GpsLocationMessage.timestampMillis]), which is
 * what the server stores, so a fix delivered hours late still lands at the right point in time.
 */
class TcpLocationClient(
    private val deviceName: String,
    private val listener: Listener,
    private val outbox: LocationOutbox
) {
    interface Listener {
        /** Called (from the connection's background thread) whenever the server pushes a new
         *  desired location-update interval. `intervalSeconds <= 0` means "no explicit interval,
         *  use the default behaviour". */
        fun onLocationIntervalReceived(intervalSeconds: Int)

        /** Purely informational, e.g. for the foreground-service notification text / logging. */
        fun onConnectionStateChanged(connected: Boolean) {}

        /** Human-readable (Dutch, like the rest of the diagnostics log) notes about why a
         *  connection attempt failed, when the next one is due, and what is happening to the
         *  buffered locations. May be called from any thread. */
        fun onDiagnostic(message: String) {}
    }

    /** Everything that belongs to one established socket. A fresh instance per connection, so
     *  nothing about a previous connection's unconfirmed writes can leak into the next one. */
    private class Connection(val socket: Socket, val output: OutputStream) {
        @Volatile var handshakeDone = false

        // --- connection thread only ---
        var connectedAtElapsedMs = 0L
        var announceReplayDone = false
        var deliveredCount = 0

        // --- guarded by synchronized(this) ---
        /** One entry per keepalive that is on the wire and not echoed back yet, in the order
         *  they were written: [HEARTBEAT_MARKER] for a plain idle heartbeat, otherwise the
         *  outbox id up to which a location batch is confirmed once that echo arrives. */
        val pendingAcks = ArrayDeque<Long>()
        var oldestUnackedUptimeMs = 0L
        var batchInFlight = false
        var batchSize = 0
    }

    private class SessionResult(
        val notConfigured: Boolean,
        /** The handshake completed, i.e. this was a real connection that then ended. */
        val established: Boolean,
        val durationMs: Long,
        val reason: String
    )

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var running = false
    @Volatile private var connected = false

    /** Guards [generation], [currentSocket] and [connection] (writes; reads are volatile). */
    private val stateLock = Any()

    /** Bumped every time a connection thread is started or abandoned. A thread only keeps going
     *  (and only touches shared state) while its own generation is still the current one - that
     *  is what makes it safe to simply walk away from a thread that is stuck somewhere. */
    @Volatile private var generation = 0
    @Volatile private var currentSocket: Socket? = null

    /** The connection that has completed its handshake, or null while (re)connecting. */
    @Volatile private var connection: Connection? = null

    private var thread: Thread? = null
    private val writeLock = Any()

    private val sleepLock = Object()
    private var skipNextBackoff = false // guarded by sleepLock
    @Volatile private var resetBackoffRequested = false

    /** [SystemClock.uptimeMillis] of the connection thread's last sign of life - see [ensureRunning]. */
    @Volatile private var lastProgressUptimeMs = 0L

    /** [SystemClock.elapsedRealtime] of the last connect attempt - see [kick]. */
    @Volatile private var lastAttemptElapsedMs = 0L

    @Volatile private var offlineBufferingAnnounced = false

    /** Only used if the outbox itself fails (see [sendLocation]): the old "remember just the
     *  newest fix" behaviour, so a storage problem degrades reporting instead of stopping it. */
    private val fallbackLocationFrame = AtomicReference<ByteArray?>(null)
    private val pendingGeofenceFrame = AtomicReference<ByteArray?>(null)

    // sendLocation() used to write straight to the socket on whatever thread called it - for
    // LocationLoggingService that's onLocationChanged, which (no Looper passed to
    // requestLocationUpdates) runs on the service's main thread. That's a guaranteed
    // NetworkOnMainThreadException; found the hard way via the exact same pattern in
    // AdminTcpClient (see its class doc). All outbound writes (and the outbox's database work)
    // are funnelled through this single background executor instead, so the caller's thread never
    // touches the socket.
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
        startThreadLocked()
    }

    @Synchronized
    fun stop() {
        running = false
        abandonCurrentThreadLocked()
        thread = null
        setConnected(false)
        synchronized(writeExecutorLock) {
            // shutdown(), not shutdownNow(): anything already queued is a location on its way
            // into the outbox, which must still get there. Nothing queued can block for long -
            // the socket was just closed, so a write in progress fails immediately.
            writeExecutor?.shutdown()
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
            resetBackoffRequested = true
            closeCurrentSocket() // unblocks a pending read; runLoop reconnects right away
            wakeSleeper() // in case it's currently waiting out the reconnect backoff instead
        }
    }

    fun isConnected(): Boolean = connected

    /** Number of locations stored locally that the server has not confirmed yet. */
    fun pendingLocationCount(): Int = outbox.count()

    /**
     * Supervisor: makes sure there is a live connection thread that is actually doing something.
     * Cheap and safe to call from anywhere, as often as you like.
     *
     * The thread is replaced if it no longer exists (it should never die - [runLoop] catches
     * everything - but "should never" is exactly what a supervisor is for), or if it has shown no
     * sign of life for [STALL_THRESHOLD_MS]. Every blocking call it makes is bounded well below
     * that (connect and read timeouts, and the reconnect wait checks in every [SLEEP_SLICE_MS]),
     * so a stall this long means it is stuck somewhere that has no timeout: a socket write to a
     * peer that silently vanished, or a DNS lookup that never returns. Measured in
     * [SystemClock.uptimeMillis], which does not advance while the CPU is asleep - a thread that
     * was merely frozen along with the rest of the device is not "stalled".
     *
     * @return true if the thread had to be (re)started.
     */
    @Synchronized
    fun ensureRunning(): Boolean {
        if (!running) return false
        val current = thread
        val silentForMs = SystemClock.uptimeMillis() - lastProgressUptimeMs
        val problem = when {
            current == null || !current.isAlive -> "verbindingsthread liep niet meer"
            silentForMs > STALL_THRESHOLD_MS -> "verbindingsthread reageerde al ${silentForMs / 1000}s niet meer"
            else -> return false
        }
        diag("Bewaking: $problem - opnieuw gestart")
        abandonCurrentThreadLocked()
        setConnected(false)
        startThreadLocked()
        return true
    }

    /**
     * Nudge from outside the connection thread - see the class doc for who calls this and when.
     * Always runs [ensureRunning]. If not connected, it additionally cuts the current reconnect
     * wait short when
     * - [force] is set (something changed that makes an immediate attempt worthwhile, e.g. a
     *   network just became available) - this also restarts the backoff from its shortest delay;
     * - or the last attempt is already [MAX_RECONNECT_DELAY_MS] or more in the past, i.e. the
     *   "at least every 5 minutes" promise is due. That can only really happen if the thread was
     *   frozen (CPU asleep) through its own deadline; the caller's alarm is what woke things up.
     */
    fun kick(force: Boolean = false) {
        ensureRunning()
        if (!running || connected) return
        val overdue = SystemClock.elapsedRealtime() - lastAttemptElapsedMs >= MAX_RECONNECT_DELAY_MS
        if (force) resetBackoffRequested = true
        if (force || overdue) wakeSleeper()
    }

    /**
     * Report a location. It is stored in the [LocationOutbox] first and sent from there - right
     * away if connected, otherwise as soon as a connection is (re)established, oldest first. See
     * the class doc ("Not losing locations") for when it is considered delivered.
     */
    fun sendLocation(message: GpsLocationMessage) {
        val payload = message.toJson()
        val queued = runOnWriter {
            if (outbox.add(payload) >= 0) {
                if (connection == null) announceOfflineBuffering()
                pumpOutbox()
            } else {
                // Storage failed (disk full, database unusable, ...). Don't let that stop
                // reporting altogether: fall back to the pre-outbox behaviour for this fix.
                val frame = HcmProtocol.encodeFrame(HcmProtocol.OBJ_GPS_LOCATION, payload.toByteArray(Charsets.US_ASCII))
                val conn = connection
                if (conn == null || !writeFrame(conn, frame)) fallbackLocationFrame.set(frame)
            }
        }
        if (!queued) {
            // No write executor (client not started, or already stopped): nothing can be sent
            // right now anyway, but the location must not be lost - store it on the caller's
            // thread. A single small insert, and no socket involved.
            outbox.add(payload)
        }
    }

    /** Enqueue a geofence status update (active=true to create/renew, active=false to clear) -
     *  see location/LocationLoggingService. Unlike locations this is *state*, not history: only
     *  the newest one matters, so while disconnected just the latest is remembered and flushed
     *  as soon as the connection is (re)established. Not admin-gated, ordinary telemetry over
     *  this primary connection. */
    fun sendGeofenceStatus(status: GeofenceStatusMessage) {
        val frame = HcmProtocol.encodeFrame(HcmProtocol.OBJ_GEOFENCE_STATUS, status.toJson().toByteArray(Charsets.US_ASCII))
        val conn = connection
        if (conn == null) {
            pendingGeofenceFrame.set(frame)
            return
        }
        val queued = runOnWriter {
            // compareAndSet: if a newer status was queued in the meantime, that one wins.
            if (!writeFrame(conn, frame)) pendingGeofenceFrame.compareAndSet(null, frame)
        }
        if (!queued) pendingGeofenceFrame.set(frame)
    }

    // ------------------------------------------------------------------------------------------
    // Connection thread
    // ------------------------------------------------------------------------------------------

    private fun startThreadLocked() {
        val myGeneration = synchronized(stateLock) { ++generation }
        synchronized(sleepLock) { skipNextBackoff = false }
        markProgress()
        thread = Thread({ runLoop(myGeneration) }, "TcpLocationClient-$myGeneration").apply {
            isDaemon = true
            start()
        }
    }

    /** Makes whatever connection thread currently exists exit at its next opportunity, and gives
     *  it that opportunity right now by closing its socket and ending its reconnect wait. */
    private fun abandonCurrentThreadLocked() {
        val socket = synchronized(stateLock) {
            generation++
            connection = null
            currentSocket.also { currentSocket = null }
        }
        socket?.let { closeQuietly(it) }
        wakeSleeper()
    }

    private fun isCurrent(myGeneration: Int): Boolean = running && generation == myGeneration

    private fun markProgress() {
        lastProgressUptimeMs = SystemClock.uptimeMillis()
    }

    /**
     * The one loop that must never end while the client is running. Each pass is one connection
     * attempt (which, if it succeeds, lasts for as long as the connection does), followed by a
     * wait. Nothing that happens inside an attempt can break out of the loop: [connectAndPump]
     * reports every failure as a [SessionResult], and anything it somehow lets through is caught
     * here - including non-[Exception] throwables, which the old `catch (e: Exception)` would
     * have let kill the thread for good.
     */
    private fun runLoop(myGeneration: Int) {
        var consecutiveFailures = 0
        var notConfiguredAnnounced = false
        while (isCurrent(myGeneration)) {
            val result = try {
                connectAndPump(myGeneration)
            } catch (t: Throwable) {
                SessionResult(notConfigured = false, established = false, durationMs = 0, reason = describe(t))
            }
            if (!isCurrent(myGeneration)) return
            setConnected(false)

            val delayMs: Long
            if (result.notConfigured) {
                // Nothing to connect to yet. updateEndpoint() ends this wait as soon as there is.
                if (!notConfiguredAnnounced) {
                    notConfiguredAnnounced = true
                    diag("Nog geen TCP-server (host/poort) ingesteld - er wordt niet verbonden")
                }
                consecutiveFailures = 0
                delayMs = MAX_RECONNECT_DELAY_MS
            } else {
                notConfiguredAnnounced = false
                // Only a connection that actually held for a while resets the backoff - a server
                // that accepts and then drops every connection within seconds is still "down".
                val wasStable = result.established && result.durationMs >= STABLE_CONNECTION_MS
                consecutiveFailures = if (wasStable) 0 else consecutiveFailures + 1
                delayMs = reconnectDelayMs(consecutiveFailures)
                if (result.established) {
                    diag(
                        "Verbinding verbroken na ${result.durationMs / 1000}s (${result.reason}) - " +
                            "opnieuw verbinden over ${delayMs / 1000}s"
                    )
                } else {
                    diag(
                        "Verbinden met $host:$port mislukt (${result.reason}) - poging " +
                            "$consecutiveFailures, volgende over ${delayMs / 1000}s"
                    )
                }
            }

            sleepBeforeReconnect(delayMs, myGeneration)
            if (resetBackoffRequested) {
                resetBackoffRequested = false
                consecutiveFailures = 0
            }
        }
    }

    private fun reconnectDelayMs(consecutiveFailures: Int): Long =
        RECONNECT_DELAYS_MS[consecutiveFailures.coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]

    /**
     * Waits out the reconnect backoff, unless [wakeSleeper] is called first (endpoint change,
     * [kick] or [stop]) - in which case it returns early and the caller re-checks its state.
     *
     * The deadline is in [SystemClock.elapsedRealtime], which keeps counting while the CPU is
     * asleep, and the wait itself is chopped into [SLEEP_SLICE_MS] pieces: `Object.wait(timeout)`
     * on its own only counts time the CPU was awake, so one long wait could stretch far beyond
     * what was asked for. This way the thread retries as soon as it gets to run again after the
     * deadline has passed, and each slice doubles as a sign of life for [ensureRunning].
     */
    private fun sleepBeforeReconnect(delayMs: Long, myGeneration: Int) {
        val deadline = SystemClock.elapsedRealtime() + delayMs
        synchronized(sleepLock) {
            while (isCurrent(myGeneration) && !skipNextBackoff) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                markProgress()
                try {
                    sleepLock.wait(minOf(remaining, SLEEP_SLICE_MS))
                } catch (e: InterruptedException) {
                    // Nobody interrupts this thread on purpose; treat it as "wake up early".
                    // Deliberately not re-setting the interrupt flag: the old code did, which
                    // made every later wait() throw immediately - i.e. a reconnect loop with no
                    // pause at all, forever.
                    break
                }
            }
            skipNextBackoff = false
        }
        markProgress()
    }

    private fun wakeSleeper() {
        synchronized(sleepLock) {
            // The flag (not just notifyAll) so a wake-up requested a moment *before* the thread
            // starts waiting isn't lost.
            skipNextBackoff = true
            sleepLock.notifyAll()
        }
    }

    /** One connection attempt, start to finish. Never throws for anything network-related;
     *  always closes its socket and clears its shared state before returning. */
    private fun connectAndPump(myGeneration: Int): SessionResult {
        val targetHost = host
        val targetPort = port
        if (targetHost.isBlank() || targetPort <= 0) {
            Log.w(TAG, "No TCP server host/port configured yet, not connecting")
            return SessionResult(notConfigured = true, established = false, durationMs = 0, reason = "")
        }

        lastAttemptElapsedMs = SystemClock.elapsedRealtime()
        markProgress()
        Log.i(TAG, "Connecting to $targetHost:$targetPort")
        val socket = Socket()
        var conn: Connection? = null
        val adopted = synchronized(stateLock) {
            if (isCurrent(myGeneration)) {
                currentSocket = socket
                true
            } else {
                false
            }
        }
        try {
            if (!adopted) return sessionEnded(null, "gestopt")

            socket.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            markProgress()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // Until the server has answered the handshake, a silent socket is a failed attempt,
            // not an idle connection: the old code kept waiting on it in 30s steps forever if
            // something accepted the TCP connection but never spoke the protocol.
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = socket.getInputStream()
            val newConn = Connection(socket, socket.getOutputStream())
            conn = newConn

            // Must happen immediately: the server only allows ~1s in the "connecting" state
            // before it drops the socket (see `Client::isInactive` server-side).
            writeFrameLocked(newConn.output, HcmProtocol.encodeFrame(HcmProtocol.OBJ_HCNAME, deviceName.toByteArray(Charsets.US_ASCII)))

            val decoder = HcmFrameDecoder()
            val readBuffer = ByteArray(1024)

            while (isCurrent(myGeneration) && currentSocket === socket) {
                markProgress()
                val bytesRead = try {
                    input.read(readBuffer)
                } catch (e: SocketTimeoutException) {
                    if (!newConn.handshakeDone) {
                        return sessionEnded(newConn, "geen antwoord van de server op de handshake")
                    }
                    // Nothing received within the keepalive window.
                    if (!onReadIdle(newConn)) {
                        return sessionEnded(newConn, "server antwoordt niet meer (keepalive niet bevestigd)")
                    }
                    continue
                }

                if (bytesRead < 0) {
                    Log.i(TAG, "Server closed the connection")
                    return sessionEnded(newConn, "server sloot de verbinding")
                }
                if (bytesRead == 0) continue

                for (frame in decoder.feed(readBuffer, 0, bytesRead)) {
                    handleFrame(newConn, frame, myGeneration)
                }
                // Also checked here, not only on a read timeout: a server that keeps pushing
                // other frames but never confirms ours would otherwise never hit that timeout.
                if (isAckOverdue(newConn)) {
                    return sessionEnded(newConn, "server bevestigt verzonden gegevens niet")
                }
            }
            return sessionEnded(newConn, "verbinding gesloten door de app")
        } catch (t: Throwable) {
            return sessionEnded(conn, describe(t))
        } finally {
            synchronized(stateLock) {
                if (conn != null && connection === conn) connection = null
                if (currentSocket === socket) currentSocket = null
            }
            closeQuietly(socket)
        }
    }

    private fun sessionEnded(conn: Connection?, reason: String): SessionResult {
        val established = conn != null && conn.handshakeDone
        val durationMs = if (established) SystemClock.elapsedRealtime() - conn!!.connectedAtElapsedMs else 0L
        return SessionResult(notConfigured = false, established = established, durationMs = durationMs, reason = reason)
    }

    private fun handleFrame(conn: Connection, frame: HcmFrame, myGeneration: Int) {
        when (frame.objectId) {
            HcmProtocol.OBJ_SERVERNAME -> {
                if (conn.handshakeDone) return
                Log.i(TAG, "Handshake complete: ${String(frame.payload, Charsets.US_ASCII)}")
                conn.connectedAtElapsedMs = SystemClock.elapsedRealtime()
                conn.handshakeDone = true
                conn.socket.soTimeout = KEEPALIVE_INTERVAL_MS
                val published = synchronized(stateLock) {
                    if (isCurrent(myGeneration)) {
                        connection = conn
                        true
                    } else {
                        false
                    }
                }
                if (!published) return
                setConnected(true)
                offlineBufferingAnnounced = false

                // Take-then-put-back rather than getAndSet(null) alone: if the write fails the
                // status must still be there for the next connection (unless a newer one has
                // been queued in the meantime).
                pendingGeofenceFrame.getAndSet(null)?.let { pending ->
                    if (!writeFrame(conn, pending)) pendingGeofenceFrame.compareAndSet(null, pending)
                }
                fallbackLocationFrame.getAndSet(null)?.let { pending ->
                    if (!writeFrame(conn, pending)) fallbackLocationFrame.compareAndSet(null, pending)
                }

                val backlog = outbox.count()
                if (backlog > 0) {
                    conn.announceReplayDone = true
                    diag("Verbonden - $backlog lokaal bewaarde locatie(s) worden nu nagestuurd")
                }
                schedulePump()
            }
            HcmProtocol.OBJ_KEEPALIVE -> onKeepAliveEcho(conn)
            HcmProtocol.OBJ_LOCATION_INTERVAL -> {
                val interval = runCatching {
                    LocationIntervalMessage.fromJson(String(frame.payload, Charsets.US_ASCII))
                }.getOrNull()
                if (interval != null) {
                    // A misbehaving listener must not take the connection down with it.
                    runCatching { listener.onLocationIntervalReceived(interval.intervalSeconds) }
                }
            }
            else -> Unit // Other object ids (RoomList, temperatures, ...) aren't relevant here.
        }
    }

    /** The server echoed one of our keepalives. Echoes come back in the order the keepalives
     *  were written, so this one belongs to the oldest entry in [Connection.pendingAcks]. */
    private fun onKeepAliveEcho(conn: Connection) {
        var confirmedUpTo = -1L
        var confirmedCount = 0
        synchronized(conn) {
            val marker = conn.pendingAcks.removeFirstOrNull()
            conn.oldestUnackedUptimeMs = if (conn.pendingAcks.isEmpty()) 0L else SystemClock.uptimeMillis()
            if (marker != null && marker != HEARTBEAT_MARKER) {
                confirmedUpTo = marker
                confirmedCount = conn.batchSize
                conn.batchInFlight = false
            }
        }
        if (confirmedUpTo < 0) return

        // The server answered a keepalive that was written *after* this batch, on the same
        // connection - so it has the batch. Only now is it safe to forget those locations.
        outbox.deleteUpTo(confirmedUpTo)
        conn.deliveredCount += confirmedCount
        if (conn.announceReplayDone && outbox.count() == 0) {
            conn.announceReplayDone = false
            diag("Buffer leeg - ${conn.deliveredCount} locatie(s) nagestuurd en door de server bevestigd")
        }
        schedulePump() // next batch, if anything is (still) waiting
    }

    /**
     * Called when nothing was received for [KEEPALIVE_INTERVAL_MS].
     * @return false if the connection has to be considered dead.
     */
    private fun onReadIdle(conn: Connection): Boolean {
        if (isAckOverdue(conn)) {
            Log.w(TAG, "Keepalive not acknowledged in time, reconnecting")
            return false
        }
        synchronized(writeLock) {
            // Only if nothing is outstanding already - an unanswered batch keepalive is itself
            // the heartbeat, no need to stack a second one on top.
            val sendHeartbeat = synchronized(conn) {
                if (conn.pendingAcks.isEmpty()) {
                    conn.pendingAcks.addLast(HEARTBEAT_MARKER)
                    conn.oldestUnackedUptimeMs = SystemClock.uptimeMillis()
                    true
                } else {
                    false
                }
            }
            if (sendHeartbeat) {
                conn.output.write(KEEPALIVE_FRAME)
                conn.output.flush()
            }
        }
        return true
    }

    private fun isAckOverdue(conn: Connection): Boolean = synchronized(conn) {
        conn.pendingAcks.isNotEmpty() &&
            SystemClock.uptimeMillis() - conn.oldestUnackedUptimeMs >= ACK_TIMEOUT_MS
    }

    // ------------------------------------------------------------------------------------------
    // Sending (write executor)
    // ------------------------------------------------------------------------------------------

    private fun runOnWriter(task: () -> Unit): Boolean {
        val executor = synchronized(writeExecutorLock) { writeExecutor } ?: return false
        return try {
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.w(TAG, "Write task failed: ${describe(t)}")
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            false
        }
    }

    private fun schedulePump() {
        runOnWriter { pumpOutbox() }
    }

    /**
     * Sends the next batch of stored locations, followed by the keepalive whose echo will confirm
     * it (see the class doc). Does nothing while not connected, while the outbox is empty, or
     * while the previous batch is still unconfirmed - that last one is what paces a large
     * backlog: the server gets [BATCH_SIZE] locations at a time and the next batch only follows
     * once it has worked through those, instead of thousands of frames being dumped on it at once.
     *
     * Nothing is removed from the outbox here. If the write fails, or the confirmation never
     * arrives, the same entries are simply picked up again on the next connection.
     */
    private fun pumpOutbox() {
        val conn = connection ?: return
        try {
            synchronized(writeLock) {
                if (synchronized(conn) { conn.batchInFlight }) return
                val batch = outbox.peek(BATCH_SIZE)
                if (batch.isEmpty()) return

                val buffer = ByteArrayOutputStream(batch.size * 160 + KEEPALIVE_FRAME.size)
                var frames = 0
                for (entry in batch) {
                    // An entry that can't be framed (should be impossible - payloads are tiny)
                    // is skipped rather than allowed to block everything queued behind it; it
                    // disappears together with the rest of this batch once that is confirmed.
                    val frame = runCatching {
                        HcmProtocol.encodeFrame(HcmProtocol.OBJ_GPS_LOCATION, entry.payload.toByteArray(Charsets.US_ASCII))
                    }.getOrNull() ?: continue
                    buffer.write(frame, 0, frame.size)
                    frames++
                }
                buffer.write(KEEPALIVE_FRAME, 0, KEEPALIVE_FRAME.size)

                // Registered *before* the bytes go out: the echo can't possibly be processed
                // before its marker exists. And inside writeLock, so markers are queued in
                // exactly the order their keepalives hit the wire.
                synchronized(conn) {
                    conn.pendingAcks.addLast(batch.last().id)
                    conn.batchInFlight = true
                    conn.batchSize = frames
                    if (conn.pendingAcks.size == 1) conn.oldestUnackedUptimeMs = SystemClock.uptimeMillis()
                }
                conn.output.write(buffer.toByteArray())
                conn.output.flush()
            }
        } catch (e: IOException) {
            // The connection is gone; make sure the connection thread notices right away
            // instead of at its next read timeout. Nothing is lost - see above.
            Log.w(TAG, "Sending stored locations failed: ${e.message}")
            closeQuietly(conn.socket)
        }
    }

    private fun announceOfflineBuffering() {
        if (offlineBufferingAnnounced) return
        offlineBufferingAnnounced = true
        diag("Geen verbinding met de server - locaties worden lokaal bewaard (${outbox.count()} in buffer)")
    }

    private fun writeFrameLocked(output: OutputStream, frame: ByteArray) {
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    /** @return true if the frame was written, false if the socket wasn't actually usable. */
    private fun writeFrame(conn: Connection, frame: ByteArray): Boolean {
        return try {
            writeFrameLocked(conn.output, frame)
            true
        } catch (e: IOException) {
            false
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun setConnected(value: Boolean) {
        val changed = synchronized(stateLock) {
            if (connected != value) {
                connected = value
                true
            } else {
                false
            }
        }
        // A listener that throws must never be able to kill the thread that called it.
        if (changed) runCatching { listener.onConnectionStateChanged(value) }
    }

    private fun diag(message: String) {
        Log.i(TAG, message)
        runCatching { listener.onDiagnostic(message) }
    }

    private fun describe(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }

    private fun closeCurrentSocket() {
        val socket = synchronized(stateLock) { currentSocket.also { currentSocket = null } }
        socket?.let { closeQuietly(it) }
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

        /** How long the server gets to answer the handshake before the attempt counts as failed. */
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_INTERVAL_MS = 30_000

        /** How long a keepalive may stay unanswered. Just under [KEEPALIVE_INTERVAL_MS], so an
         *  idle heartbeat that got no echo is caught at the very next read timeout (i.e. a dead
         *  connection is noticed after about a minute at most, same as before). The server only
         *  has to store one batch within this time. */
        private const val ACK_TIMEOUT_MS = 25_000L

        /** Wait before reconnecting, indexed by the number of consecutive failed attempts:
         *  5s after a healthy connection drops (index 0), then 15s, 30s, 1 min, 2 min, and from
         *  then on [MAX_RECONNECT_DELAY_MS] for as long as it takes. */
        private val RECONNECT_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        /** The longest the client ever waits between two attempts: 5 minutes. */
        val MAX_RECONNECT_DELAY_MS: Long = RECONNECT_DELAYS_MS.last()

        /** A connection has to have lasted this long before its loss restarts the backoff. */
        private const val STABLE_CONNECTION_MS = 60_000L

        private const val SLEEP_SLICE_MS = 30_000L

        /** See [ensureRunning]. Comfortably above every bounded wait the connection thread has. */
        private const val STALL_THRESHOLD_MS = 3 * 60_000L

        /** Locations per batch - see [pumpOutbox]. */
        private const val BATCH_SIZE = 20

        private const val HEARTBEAT_MARKER = -1L
        private val KEEPALIVE_FRAME: ByteArray = HcmProtocol.encodeFrame(HcmProtocol.OBJ_KEEPALIVE, ByteArray(0))
    }
}

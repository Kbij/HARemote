package com.koen.haremote.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.koen.haremote.MainActivity
import com.koen.haremote.R
import com.koen.haremote.data.SettingsRepository
import com.koen.haremote.network.tcp.GeofenceStatusMessage
import com.koen.haremote.network.tcp.GpsLocationMessage
import com.koen.haremote.network.tcp.TcpConnectionStatus
import com.koen.haremote.network.tcp.TcpLocationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps reporting the device's GPS location to the HomeControl server
 * over a persistent TCP connection, even while the app is in the background — mirroring how
 * GPSLogger keeps itself alive: a foreground notification, a partial wake lock, START_STICKY,
 * a boot receiver and a periodic watchdog alarm that restarts it if it gets killed.
 *
 * The server can push back a desired update interval (see [TcpLocationClient.Listener]); a short
 * interval means the server wants frequent, accurate fixes (GPS), a long one means it's fine with
 * an infrequent, coarser fix (network location) to save battery.
 *
 * Also owns the stationary-geofence feature: if the client hasn't moved for a while, a real
 * Play Services geofence (EXIT transition only, see [GeofenceBroadcastReceiver]) is registered
 * around it and reported to the server, with a 30-min renewal safety net
 * ([GeofenceRenewalAlarmScheduler]) on top in case the EXIT callback is ever missed. While that
 * geofence is active, regular GPS/network polling is paused entirely (see
 * [pauseLocationUpdatesForGeofence]) — the whole point of the feature is to save battery once the
 * client is confirmed stationary, so only the 30-min renewal check still touches location.
 */
class LocationLoggingService : Service(), LocationListener {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var locationManager: LocationManager
    private lateinit var tcpClient: TcpLocationClient
    private lateinit var geofencingClient: GeofencingClient
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Interval (seconds) currently requested by the server; 0 = no explicit request, use the
     *  app's own default cadence. Only touched on the main thread. */
    private var currentIntervalSeconds = 0

    /** Trailing window of recent fixes, used only by the stationary-geofence heuristic (see
     *  [maybeCreateGeofence]) — cleared whenever a geofence is active, created, or cleared. */
    private val recentFixes = ArrayDeque<Location>()

    /**
     * Renews the wake lock on a fixed timer, independent of whether GPS fixes actually
     * arrive. [onLocationChanged] also renews it, but relying on that alone is exactly the
     * bug being fixed here: if GPS stalls for a stretch (weak signal, chip-level power
     * saving while stationary, ...) the wake lock would lapse, the CPU would be allowed to
     * sleep, and recovering from that (reacquiring a GPS lock, waking the TCP thread) takes
     * far longer than the stall itself — turning a temporary gap into a much longer one.
     * Rescheduling comfortably inside WAKE_LOCK_TIMEOUT_MS's 10 minutes keeps the CPU awake
     * regardless of what GPS is doing.
     */
    private val wakeLockRenewalRunnable = object : Runnable {
        override fun run() {
            wakeLock?.takeIf { it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
            mainHandler.postDelayed(this, WAKE_LOCK_RENEWAL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A null intent in onStartCommand (below) tells us Android restarted the service
        // after killing it (START_STICKY); logging onCreate too lets us see a *new process*
        // starting up (e.g. after the watchdog alarm calls start() following a full app kill,
        // where onStartCommand's intent is non-null instead).
        log("Service", "onCreate")
        // Snapshot of the OS-level power state at every start-up, so the exported log shows
        // how it evolves over time (e.g. a burst of kills pushing the app into a stricter
        // standby bucket) without needing to check the Instellingen screen at the same moment.
        log("Battery", BatteryDiagnostics.current(applicationContext).summary())
        settingsRepository = SettingsRepository(applicationContext)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        geofencingClient = LocationServices.getGeofencingClient(this)
        tcpClient = TcpLocationClient(deviceName = Build.MODEL, listener = tcpListener)

        startForeground(NOTIFICATION_ID, buildNotification(connected = false))
        acquireWakeLock()
        mainHandler.postDelayed(wakeLockRenewalRunnable, WAKE_LOCK_RENEWAL_INTERVAL_MS)
        requestLocationUpdates(intervalSeconds = 0)
        tcpClient.start(host = "", port = 0) // real host/port arrive via observeSettings()
        observeSettings()

        // Make sure the watchdog is (re)armed as long as this service is alive.
        AlarmScheduler.scheduleWatchdog(applicationContext)
        // Play Services geofence registrations do NOT survive a device reboot (only process
        // death) - GeofenceState (SharedPreferences) does, so re-register with Play Services
        // whenever the service starts up and a geofence was left active.
        resumeGeofenceIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service", "onStartCommand, intent=${intent?.action ?: "null (system restart)"}, startId=$startId")
        when (intent?.action) {
            ACTION_STOP -> {
                clearGeofence("service gestopt")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_GEOFENCE_EXIT -> {
                log("Geofence", "Geofence-exit ontvangen van Play Services")
                clearGeofence("client verliet de geofence")
                return START_STICKY
            }
            ACTION_GEOFENCE_RENEWAL_CHECK -> {
                performGeofenceRenewalCheck()
                return START_STICKY
            }
        }
        // START_STICKY: if the system kills this process to reclaim memory, it will
        // try to recreate the service and call onStartCommand again with a null intent.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Fires when the user swipes the app away from Recents. A started (non-bound)
        // foreground service normally keeps running after this by default, but some OEMs
        // (Motorola included) treat a removed task as a stronger signal to kill the process
        // anyway — logging this tells us whether that's what's happening here.
        log("Service", "onTaskRemoved (app swiped from recents)")
        super.onTaskRemoved(rootIntent)
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                tcpClient.updateEndpoint(settings.tcpServerHost, settings.tcpServerPort)
                if (!settings.gpsLoggingEnabled) {
                    // Geofencing without location logging makes no sense - tear it down too.
                    mainHandler.post { clearGeofence("locatie-logging uitgeschakeld") }
                    stopSelf()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HARemote:LocationLogging").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    /**
     * (Re)requests location updates for the given server-requested interval:
     * - `<= 0` (no explicit request): both GPS and network provider, our own default cadence.
     * - short interval (`1..HIGH_ACCURACY_THRESHOLD_SECONDS`): both providers again, at that
     *   exact cadence — the server wants frequent *and* accurate fixes, but see
     *   [locationProvidersFor]'s doc comment for why GPS-only was dropped (8 sept 2026): it left
     *   the app with zero fixes for hours whenever GPS couldn't get a signal indoors.
     * - longer interval: network provider only (falls back to GPS if unavailable) — accuracy
     *   doesn't matter as much, so prefer the cheaper provider.
     *
     * **New (8 sept 2026):** a no-op while a stationary geofence is active — see
     * [pauseLocationUpdatesForGeofence]'s doc comment for why. `currentIntervalSeconds` is still
     * updated by the caller regardless (see [tcpListener]), so [resumeLocationUpdatesAfterGeofence]
     * picks up the latest server-requested interval once the geofence clears, even if the server
     * pushed a new one while it was active.
     */
    private fun requestLocationUpdates(intervalSeconds: Int) {
        if (GeofenceState.load(applicationContext) != null) {
            log("Location", "Locatie-updates niet aangevraagd (interval=${intervalSeconds}s onthouden): geofence is actief")
            return
        }
        try {
            locationManager.removeUpdates(this)
            val minTimeMs = if (intervalSeconds > 0) intervalSeconds * 1000L else MIN_TIME_MS
            val minDistanceM = if (intervalSeconds > 0) 0f else MIN_DISTANCE_M
            val providers = locationProvidersFor(intervalSeconds)
            log("Location", "Locatie-updates aangevraagd: providers=$providers, minTime=${minTimeMs}ms, minDistance=${minDistanceM}m")
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, this)
            }
        } catch (e: SecurityException) {
            // Location permission not granted yet; MainActivity is responsible for
            // asking for it. Once granted the service gets restarted.
        }
    }

    /**
     * **New (8 sept 2026):** koen's explicit goal for the stationary-geofence feature was battery
     * savings while stationary — "als geofence actief is, minder stroom verbruiken, enkel om de
     * 30 min eens controleren met gps, daarna terug minder verbruiken". Before this, an active
     * geofence didn't change anything about the regular [requestLocationUpdates] polling: the
     * confirmed-working test log from this morning showed continuous `[Location] fix ontvangen`
     * lines (gps + network, every few seconds) carrying on unchanged even after "Geofence
     * aangemaakt" - the Play Services geofence ran in *addition* to full-rate polling, not
     * *instead of* it, so none of the expected power saving actually happened.
     *
     * Called right after a geofence is successfully registered with Play Services (both when
     * freshly created in [registerGeofence] and when re-confirmed on service start in
     * [resumeGeofenceIfNeeded]): stops the regular GPS/network listener outright. From then on the
     * client relies entirely on Play Services' own low-power geofence hardware for EXIT detection
     * plus the existing 30-minute [GeofenceRenewalAlarmScheduler] safety net, which already did
     * exactly one GPS/network fix via [requestSingleFreshLocation] - that one check is untouched
     * by this change and keeps happening every 30 min as before.
     */
    private fun pauseLocationUpdatesForGeofence() {
        log("Geofence", "Reguliere GPS/network locatie-updates gepauzeerd (geofence actief) - enkel de 30-minuten-check gebruikt nog locatie")
        locationManager.removeUpdates(this)
    }

    /** Counterpart to [pauseLocationUpdatesForGeofence], called from [clearGeofence] once there is
     *  no longer an active geofence: resumes regular polling at whatever interval the server most
     *  recently requested (which may have changed while the geofence was active - see
     *  [requestLocationUpdates]'s doc comment). */
    private fun resumeLocationUpdatesAfterGeofence() {
        log("Geofence", "Geofence niet langer actief - reguliere locatie-updates hervat (interval=${currentIntervalSeconds}s)")
        requestLocationUpdates(currentIntervalSeconds)
    }

    /**
     * Bug fix (8 sept 2026): a short server-requested interval used to request **only** GPS
     * (`gps() ?: network()` - network was only used if GPS wasn't available on the device at
     * all, never as a fallback for "available but not producing fixes right now"). That's
     * exactly backwards for the stationary-geofence feature: the scenario it's built for
     * (client stopped moving, likely indoors - e.g. overnight at home) is also the scenario
     * where GPS is most likely to get zero sky view and simply never call back. Confirmed in
     * koen's diagnostics.log from the night of 7→8 sept: interval was pushed to 5s at 22:38, and
     * then literally zero `[Location] fix ontvangen` lines for the rest of the night (~8 hours) -
     * no location reported to the server, and [maybeCreateGeofence] never even got a chance to
     * run since it's only ever invoked from [onLocationChanged]. Now requests both providers
     * whenever GPS is available, same as the "no explicit interval" branch below - a coarser
     * network fix (typically ~100m accuracy, well under
     * [GeofenceState.MAX_FIX_ACCURACY_FOR_STATIONARY_M]) keeps fixes (and therefore stationary
     * detection and server reporting) flowing even when GPS is dead, at the cost of occasionally
     * receiving a less accurate fix than strictly necessary.
     */
    private fun locationProvidersFor(intervalSeconds: Int): List<String> {
        val available = locationManager.allProviders
        fun gps() = LocationManager.GPS_PROVIDER.takeIf { available.contains(it) }
        fun network() = LocationManager.NETWORK_PROVIDER.takeIf { available.contains(it) }

        return when {
            intervalSeconds in 1..HIGH_ACCURACY_THRESHOLD_SECONDS ->
                listOfNotNull(gps(), network())
            intervalSeconds > HIGH_ACCURACY_THRESHOLD_SECONDS ->
                listOfNotNull(network() ?: gps())
            else -> listOfNotNull(gps(), network())
        }
    }

    override fun onLocationChanged(location: Location) {
        // Refresh the wake lock so a long gap between fixes doesn't let the CPU sleep
        // before the update is sent. (Also renewed independently on a timer, see
        // wakeLockRenewalRunnable — this call alone can't be relied on if fixes stop
        // coming in, which is exactly the failure mode being investigated here.)
        wakeLock?.takeIf { it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)

        // Ground truth for "interval staat op 5s maar er zit >10 min tussen updates":
        // this fires only when the OS/GPS chip actually delivers a fix. A gap in these
        // lines (while Service/Tcp lines show the service alive and connected) means the
        // GPS chip itself isn't producing fixes at the requested cadence — not a kill,
        // not a TCP problem, but the location subsystem stalling on its own (weak signal,
        // no sky view, or the chip's own power-saving heuristics kicking in while stationary).
        log("Location", "fix ontvangen (provider=${location.provider}, accuracy=${location.accuracy}m)")

        tcpClient.sendLocation(
            GpsLocationMessage(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.toDouble(),
                batteryLevelPercent = currentBatteryLevelPercent(),
                timestampMillis = location.time
            )
        )

        maybeCreateGeofence(location)
    }

    /**
     * Stationary-geofence heuristic: if every fix received in the trailing
     * [GeofenceState.STATIONARY_WINDOW_MS] window is within [GeofenceState.RADIUS_METERS] of the
     * newest one, and that window is fully covered (the oldest tracked fix is at least that old),
     * the client is considered to have stopped moving and a geofence is created. Skipped entirely
     * while a geofence is already active — Play Services' EXIT callback (see
     * [GeofenceBroadcastReceiver]) and the 30-min renewal check own things from there.
     *
     * **Bug fix (7 sept 2026):** indoors, GPS fixes routinely report tens to a few hundred
     * meters of accuracy and jump around within that margin between fixes even while the phone
     * physically isn't moving. The original check compared raw [Location.distanceTo] against
     * [GeofenceState.RADIUS_METERS] against the *newest* fix each time — so a single noisy fix
     * more than 100m (in reported position, not real movement) from the rest reset the verdict to
     * "not stationary", and because the reference point keeps changing, a run of noisy indoor
     * fixes could keep failing this check indefinitely, so the geofence never got created at all.
     * Fixed by (1) ignoring fixes whose own accuracy is too poor to say anything useful
     * ([GeofenceState.MAX_FIX_ACCURACY_FOR_STATIONARY_M]) without disturbing the existing window,
     * and (2) subtracting each pair's combined reported accuracy from the raw distance before
     * comparing to the radius ([accuracyAdjustedDistance]), so two fixes whose accuracy circles
     * could plausibly overlap are no longer treated as "the client moved". Every branch below now
     * also logs, so a stuck window is diagnosable from the exported log instead of silently never
     * firing.
     */
    private fun maybeCreateGeofence(location: Location) {
        if (GeofenceState.load(applicationContext) != null) {
            recentFixes.clear()
            return
        }

        if (location.hasAccuracy() && location.accuracy > GeofenceState.MAX_FIX_ACCURACY_FOR_STATIONARY_M) {
            log(
                "Geofence",
                "Fix genegeerd voor stilstand-detectie (accuracy ${location.accuracy}m > " +
                    "${GeofenceState.MAX_FIX_ACCURACY_FOR_STATIONARY_M}m) - venster " +
                    "(${recentFixes.size} fixes) blijft ongewijzigd staan"
            )
            return
        }

        recentFixes.addLast(location)
        val cutoff = location.time - GeofenceState.STATIONARY_WINDOW_MS
        while (recentFixes.isNotEmpty() && recentFixes.first().time < cutoff) {
            recentFixes.removeFirst()
        }

        val oldest = recentFixes.first()
        if (oldest.time > cutoff) {
            log(
                "Geofence",
                "Stilstand-venster nog niet vol (${(location.time - oldest.time) / 1000}s van " +
                    "${GeofenceState.STATIONARY_WINDOW_MS / 1000}s, ${recentFixes.size} fixes)"
            )
            return // window not fully covered yet
        }

        val maxDistance = recentFixes.maxOf { accuracyAdjustedDistance(it, location) }
        if (maxDistance <= GeofenceState.RADIUS_METERS) {
            log(
                "Geofence",
                "Stilstand gedetecteerd (${recentFixes.size} fixes over " +
                    "${GeofenceState.STATIONARY_WINDOW_MS / 60_000}min, grootste accuracy-" +
                    "gecorrigeerde afwijking ${"%.1f".format(maxDistance)}m) - geofence wordt " +
                    "aangemaakt"
            )
            registerGeofence(location)
        } else {
            log(
                "Geofence",
                "Nog niet stationair: grootste accuracy-gecorrigeerde afwijking " +
                    "${"%.1f".format(maxDistance)}m > ${GeofenceState.RADIUS_METERS}m " +
                    "(huidige fix accuracy=${location.accuracy}m, ${recentFixes.size} fixes in venster)"
            )
        }
    }

    /**
     * Raw distance between two fixes, minus their combined reported accuracy (floored at 0) —
     * two fixes whose accuracy circles could plausibly overlap are treated as "no detected
     * movement" instead of penalizing normal GPS noise as if the client had physically moved.
     * See [maybeCreateGeofence]'s doc comment for why this replaced a plain [Location.distanceTo]
     * comparison.
     */
    private fun accuracyAdjustedDistance(a: Location, b: Location): Float {
        val raw = a.distanceTo(b)
        val allowance = (if (a.hasAccuracy()) a.accuracy else 0f) + (if (b.hasAccuracy()) b.accuracy else 0f)
        return (raw - allowance).coerceAtLeast(0f)
    }

    /** Called once [maybeCreateGeofence] decides the client has stopped moving: registers a real
     *  Play Services geofence (EXIT transition only), persists it (see [GeofenceState]) and
     *  reports it to the server. */
    private fun registerGeofence(location: Location) {
        val now = System.currentTimeMillis()
        addGeofenceToPlayServices(location.latitude, location.longitude, GeofenceState.RADIUS_METERS) { success ->
            if (!success) return@addGeofenceToPlayServices
            GeofenceState.save(
                applicationContext, location.latitude, location.longitude,
                GeofenceState.RADIUS_METERS, now, now
            )
            log("Geofence", "Geofence aangemaakt op (${location.latitude}, ${location.longitude}), radius ${GeofenceState.RADIUS_METERS}m")
            tcpClient.sendGeofenceStatus(
                GeofenceStatusMessage(
                    active = true,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    radiusMeters = GeofenceState.RADIUS_METERS
                )
            )
            GeofenceRenewalAlarmScheduler.scheduleRenewal(applicationContext)
            recentFixes.clear()
            pauseLocationUpdatesForGeofence()
        }
    }

    /** Re-registers an already-persisted geofence with Play Services on service (re)start —
     *  needed because Play Services geofence registrations do NOT survive a device reboot, only
     *  process death, while [GeofenceState] (SharedPreferences) survives both. Idempotent/safe
     *  to call even if Play Services still has it registered. */
    private fun resumeGeofenceIfNeeded() {
        val saved = GeofenceState.load(applicationContext) ?: return
        log("Geofence", "Actieve geofence hervat op (${saved.latitude}, ${saved.longitude}) na (her)start van de service")
        addGeofenceToPlayServices(saved.latitude, saved.longitude, saved.radiusMeters) { success ->
            if (success) {
                GeofenceRenewalAlarmScheduler.scheduleRenewal(applicationContext)
                pauseLocationUpdatesForGeofence()
            } else {
                log("Geofence", "Geofence hervatten bij Play Services mislukt, lokale status opgeruimd")
                GeofenceState.clear(applicationContext)
            }
        }
    }

    private fun addGeofenceToPlayServices(lat: Double, lon: Double, radiusMeters: Double, onResult: (Boolean) -> Unit) {
        val geofence = Geofence.Builder()
            .setRequestId(GeofenceState.GEOFENCE_ID)
            .setCircularRegion(lat, lon, radiusMeters.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .addGeofence(geofence)
            .build()
        try {
            geofencingClient.addGeofences(request, geofencePendingIntent())
                .addOnSuccessListener { onResult(true) }
                .addOnFailureListener { e ->
                    log("Geofence", "Geofence registreren bij Play Services mislukt: ${e.message}")
                    onResult(false)
                }
        } catch (e: SecurityException) {
            log("Geofence", "Geofence registreren mislukt (geen locatie-permissie): ${e.message}")
            onResult(false)
        }
    }

    /**
     * **Bug fix (8 sept 2026):** this used `FLAG_IMMUTABLE`, and every single
     * `addGeofenceToPlayServices` call failed as a result - confirmed in koen's
     * diagnostics.log: dozens of `"Geofence registreren bij Play Services mislukt: 10:
     * PendingIntent must be mutable"` lines, going back to the very first attempt. Play
     * Services needs to fill the triggering `GeofencingEvent` into this Intent's extras when it
     * broadcasts a transition, which Android does not allow on an immutable PendingIntent - the
     * geofencing API (unlike most other PendingIntent uses in this app, e.g. the notification's
     * `contentIntent` or the plain alarms in [GeofenceRenewalAlarmScheduler]/[AlarmScheduler],
     * which carry no extras and are correctly immutable) *requires* `FLAG_MUTABLE`. This means
     * **no stationary geofence has ever actually been registered with Play Services** -
     * yesterday's accuracy-tolerant stilstand-heuristic fix made `maybeCreateGeofence` decide
     * correctly, but `registerGeofence`'s call into Play Services silently failed every time
     * right after, so `GeofenceState` was never saved and nothing was ever reported to the
     * server. `FLAG_MUTABLE` only exists from API 31 (S) onward; below that, PendingIntents are
     * mutable by default, so no flag is needed there.
     */
    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            this,
            GEOFENCE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
        )
    }

    /** Common cleanup for every way a geofence stops being active: an actual EXIT from Play
     *  Services, the renewal check finding the client too far away, GPS logging being switched
     *  off, or the service being explicitly stopped. No-op if there was no active geofence. */
    private fun clearGeofence(reason: String) {
        if (GeofenceState.load(applicationContext) == null) return
        geofencingClient.removeGeofences(listOf(GeofenceState.GEOFENCE_ID))
        GeofenceState.clear(applicationContext)
        GeofenceRenewalAlarmScheduler.cancelRenewal(applicationContext)
        recentFixes.clear()
        log("Geofence", "Geofence opgeruimd ($reason)")
        tcpClient.sendGeofenceStatus(GeofenceStatusMessage(active = false))
        resumeLocationUpdatesAfterGeofence()
    }

    /**
     * Safety-net check fired every 30 minutes by [GeofenceRenewalAlarmScheduler] while a
     * geofence is active: gets one fresh GPS fix and either renews the geofence's timestamp (and
     * reports it), or — if the client turns out to already be outside the radius, meaning Play
     * Services' own EXIT callback was missed — clears it. Also feeds the fix into the normal
     * location-reporting path, satisfying "confirm the GPS location every 30 min regardless".
     */
    private fun performGeofenceRenewalCheck() {
        val saved = GeofenceState.load(applicationContext)
        if (saved == null) {
            log("Geofence", "Vernieuwingscheck genegeerd: geen actieve geofence (meer)")
            return
        }
        log("Geofence", "30-minuten vernieuwingscheck voor actieve geofence")
        requestSingleFreshLocation { location ->
            if (location == null) {
                log("Geofence", "Vernieuwingscheck: geen locatie ontvangen, geofence-tijdstip niet vernieuwd")
                GeofenceRenewalAlarmScheduler.scheduleRenewal(applicationContext)
                return@requestSingleFreshLocation
            }

            val reference = Location("geofence-reference").apply {
                latitude = saved.latitude
                longitude = saved.longitude
            }
            val distance = location.distanceTo(reference)
            if (distance <= saved.radiusMeters) {
                val now = System.currentTimeMillis()
                GeofenceState.touchUpdatedAt(applicationContext, now)
                log("Geofence", "Vernieuwingscheck: nog steeds binnen geofence (${distance}m), tijdstip vernieuwd")
                tcpClient.sendGeofenceStatus(
                    GeofenceStatusMessage(
                        active = true,
                        latitude = saved.latitude,
                        longitude = saved.longitude,
                        radiusMeters = saved.radiusMeters
                    )
                )
                GeofenceRenewalAlarmScheduler.scheduleRenewal(applicationContext)
            } else {
                log("Geofence", "Vernieuwingscheck: buiten geofence (${distance}m) - exit-event gemist, alsnog opgeruimd")
                clearGeofence("buiten geofence bij vernieuwingscheck")
            }

            // Also feed this fix into the normal reporting path - satisfies "confirm the GPS
            // location every 30 min regardless" independent of the geofence renewal above.
            tcpClient.sendLocation(
                GpsLocationMessage(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    batteryLevelPercent = currentBatteryLevelPercent(),
                    timestampMillis = location.time
                )
            )
        }
    }

    /**
     * One-shot fresh location fix, independent of the regular [requestLocationUpdates] stream —
     * used only by [performGeofenceRenewalCheck]. Times out after [SINGLE_LOCATION_TIMEOUT_MS]
     * if no fix arrives at all, calling back with null.
     *
     * **Bug fix (8 sept 2026):** this used to pick a single provider up front (GPS if the device
     * has it, network only as a fallback if it doesn't) — the same "available but not currently
     * producing fixes" blind spot as [locationProvidersFor] (see its doc comment): if GPS is
     * present but can't get a sky view (indoors, exactly when a stationary geofence is active),
     * this always waited out the full [SINGLE_LOCATION_TIMEOUT_MS] and reported "no location
     * received" every 30 minutes, even though a network fix was available the whole time. Now
     * requests every available provider concurrently and uses whichever responds first.
     */
    private fun requestSingleFreshLocation(onResult: (Location?) -> Unit) {
        val providers = locationManager.allProviders.filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }
        if (providers.isEmpty()) {
            onResult(null)
            return
        }

        var delivered = false
        val activeListeners = mutableListOf<LocationListener>()
        lateinit var timeoutRunnable: Runnable

        fun finish(location: Location?) {
            if (delivered) return
            delivered = true
            mainHandler.removeCallbacks(timeoutRunnable)
            activeListeners.forEach { runCatching { locationManager.removeUpdates(it) } }
            onResult(location)
        }

        timeoutRunnable = Runnable { finish(null) }

        var anyRegistered = false
        for (provider in providers) {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = finish(location)
                @Deprecated("Deprecated in Java, still called on older API levels")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                locationManager.requestSingleUpdate(provider, listener, mainHandler.looper)
                activeListeners.add(listener)
                anyRegistered = true
            } catch (e: SecurityException) {
                // Permission not granted for this specific provider - try the others.
            }
        }

        if (!anyRegistered) {
            onResult(null)
            return
        }
        mainHandler.postDelayed(timeoutRunnable, SINGLE_LOCATION_TIMEOUT_MS)
    }

    private fun currentBatteryLevelPercent(): Int {
        val status = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    @Deprecated("Deprecated in Java, still called on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        // NOTE: this only runs on a *graceful* stop (stopSelf()/stopService(), or the system
        // giving the service a chance to clean up). A hard kill (low-memory killer, or an OEM
        // battery manager force-stopping the process) skips onDestroy entirely — so a gap in
        // the log between this line and the next "Service onCreate" (with no onDestroy in
        // between) is itself evidence of a hard kill, not a graceful stop.
        //
        // Deliberately does NOT clear the geofence here: onDestroy also fires on an ordinary
        // watchdog-triggered restart, and clearing it on every such restart would defeat the
        // whole point of persisting it (see GeofenceState / resumeGeofenceIfNeeded). Explicit
        // teardown happens in onStartCommand (ACTION_STOP) and observeSettings() instead.
        log("Service", "onDestroy")
        runCatching { locationManager.removeUpdates(this) }
        tcpClient.stop()
        TcpConnectionStatus.update(false)
        mainHandler.removeCallbacks(wakeLockRenewalRunnable)
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceScope.cancel()
        // Arm the watchdog again so a system-initiated kill gets undone shortly after.
        AlarmScheduler.scheduleWatchdog(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private val tcpListener = object : TcpLocationClient.Listener {
        override fun onLocationIntervalReceived(intervalSeconds: Int) {
            mainHandler.post {
                if (intervalSeconds == currentIntervalSeconds) return@post
                log("Tcp", "Location interval changed: $currentIntervalSeconds -> $intervalSeconds")
                currentIntervalSeconds = intervalSeconds
                requestLocationUpdates(intervalSeconds)
            }
        }

        override fun onConnectionStateChanged(connected: Boolean) {
            mainHandler.post {
                log("Tcp", if (connected) "Connected" else "Disconnected")
                updateNotification(connected)
            }
            // Not posted to mainHandler like the lines above - TcpConnectionStatus is a plain
            // thread-safe StateFlow, and HomeScreen's LED should reflect a drop the instant it
            // happens rather than wait on the main-thread queue.
            TcpConnectionStatus.update(connected)
        }
    }

    private fun log(tag: String, message: String) = DiagnosticLogger.log(applicationContext, tag, message)

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(connected))
    }

    private fun buildNotification(connected: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (connected) {
            getString(R.string.notification_text)
        } else {
            getString(R.string.notification_text_disconnected)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gps_logging_channel"
        private const val NOTIFICATION_ID = 4201
        private const val MIN_TIME_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** How often wakeLockRenewalRunnable re-acquires the wake lock — comfortably inside
         *  WAKE_LOCK_TIMEOUT_MS so a GPS stall can never let it lapse. */
        private const val WAKE_LOCK_RENEWAL_INTERVAL_MS = 4 * 60 * 1000L

        /** Server-requested intervals at or below this many seconds require GPS accuracy;
         *  longer than this, the network provider is good enough (see class doc). */
        private const val HIGH_ACCURACY_THRESHOLD_SECONDS = 20

        private const val GEOFENCE_REQUEST_CODE = 4204

        /** Timeout for the one-shot fix requested by performGeofenceRenewalCheck(). */
        private const val SINGLE_LOCATION_TIMEOUT_MS = 20_000L

        const val ACTION_STOP = "com.koen.haremote.action.STOP"
        const val ACTION_GEOFENCE_EXIT = "com.koen.haremote.action.GEOFENCE_EXIT"
        const val ACTION_GEOFENCE_RENEWAL_CHECK = "com.koen.haremote.action.GEOFENCE_RENEWAL_CHECK"

        fun start(context: Context) {
            val intent = Intent(context, LocationLoggingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationLoggingService::class.java))
            AlarmScheduler.cancelWatchdog(context)
        }
    }
}

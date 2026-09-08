package com.koen.haremote.location

import android.content.Context

/**
 * Lightweight persistence for the client's current stationary geofence, backed by
 * SharedPreferences rather than SettingsRepository's DataStore (that's reserved for
 * user-facing settings). Needs to survive LocationLoggingService's frequent process restarts -
 * the underlying Play Services geofence registration does NOT survive a device reboot (Android
 * clears it), so this is also what LocationLoggingService.onCreate uses to know whether it needs
 * to re-register the geofence with Play Services on startup.
 */
object GeofenceState {
    private const val PREFS_NAME = "geofence_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_RADIUS = "radius"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_UPDATED_AT = "updatedAt"

    /** Play Services geofence request id - there is only ever at most one, per client. */
    const val GEOFENCE_ID = "stationary"

    const val RADIUS_METERS = 100.0

    /** How long a client must stay within [RADIUS_METERS] of the same spot before a geofence is
     *  created - see LocationLoggingService.maybeCreateGeofence. */
    const val STATIONARY_WINDOW_MS = 15 * 60 * 1000L

    /** Fixes whose own reported accuracy is worse than this (meters) are ignored entirely for
     *  stationary-detection purposes - not accurate enough to say anything useful either way,
     *  and including them would let a single bad fix (stale network location, no sky view) derail
     *  the whole window. Deliberately much larger than [RADIUS_METERS]: indoor GPS routinely
     *  reports 50-200m of accuracy, and that alone should not block detection - see
     *  LocationLoggingService.maybeCreateGeofence's accuracy-adjusted distance check for how
     *  fixes that DO pass this filter are still compared tolerantly. */
    const val MAX_FIX_ACCURACY_FOR_STATIONARY_M = 300f

    /** Safety-net interval: still confirm the GPS location every 30 minutes while a geofence is
     *  active, regardless of whether Play Services has reported an EXIT. */
    const val RENEWAL_INTERVAL_MS = 30 * 60 * 1000L

    data class Snapshot(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val createdAtMillis: Long,
        val updatedAtMillis: Long
    )

    fun save(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        createdAtMillis: Long,
        updatedAtMillis: Long
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_LAT, latitude.toString())
            .putString(KEY_LON, longitude.toString())
            .putString(KEY_RADIUS, radiusMeters.toString())
            .putLong(KEY_CREATED_AT, createdAtMillis)
            .putLong(KEY_UPDATED_AT, updatedAtMillis)
            .apply()
    }

    /** Updates only the renewal timestamp, keeping the rest (used by the 30-min renewal check
     *  when the client is confirmed still within the geofence). */
    fun touchUpdatedAt(context: Context, updatedAtMillis: Long) {
        if (!prefs(context).getBoolean(KEY_ACTIVE, false)) return
        prefs(context).edit().putLong(KEY_UPDATED_AT, updatedAtMillis).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Null when there's no active geofence. */
    fun load(context: Context): Snapshot? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null
        val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        val radius = p.getString(KEY_RADIUS, null)?.toDoubleOrNull() ?: RADIUS_METERS
        return Snapshot(
            latitude = lat,
            longitude = lon,
            radiusMeters = radius,
            createdAtMillis = p.getLong(KEY_CREATED_AT, 0L),
            updatedAtMillis = p.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

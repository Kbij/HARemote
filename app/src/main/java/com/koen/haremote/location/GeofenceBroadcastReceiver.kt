package com.koen.haremote.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Registered as the PendingIntent target for Play Services geofence transitions (see
 * LocationLoggingService.addGeofenceToPlayServices - only Geofence.GEOFENCE_TRANSITION_EXIT is
 * ever requested). Just forwards into the service, which owns the TCP connection needed to
 * actually report the exit to the server - see LocationLoggingService.ACTION_GEOFENCE_EXIT.
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) {
            DiagnosticLogger.log(
                appContext, "Geofence",
                "Ongeldige geofencing event ontvangen (errorCode=${geofencingEvent?.errorCode})"
            )
            return
        }
        if (geofencingEvent.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            val serviceIntent = Intent(appContext, LocationLoggingService::class.java).apply {
                action = LocationLoggingService.ACTION_GEOFENCE_EXIT
            }
            ContextCompat.startForegroundService(appContext, serviceIntent)
        }
    }
}

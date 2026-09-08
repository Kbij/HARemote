package com.koen.haremote.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Fired by the 30-min alarm in [GeofenceRenewalAlarmScheduler]. Just forwards into the service,
 * which owns the location manager and TCP connection needed to actually do anything - see
 * LocationLoggingService.ACTION_GEOFENCE_RENEWAL_CHECK / performGeofenceRenewalCheck().
 */
class GeofenceRenewalAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, LocationLoggingService::class.java).apply {
            action = LocationLoggingService.ACTION_GEOFENCE_RENEWAL_CHECK
        }
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }
}

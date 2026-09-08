package com.koen.haremote.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Renewal safety net for an active stationary geofence (see LocationLoggingService /
 * GeofenceState): every 30 minutes, request one fresh GPS fix to reconfirm the client is still
 * where the geofence says it is, and re-report it to the server - independent of (and in
 * addition to) Play Services' own EXIT-transition detection, which this exists to backstop.
 * Mirrors AlarmScheduler's self-rescheduling watchdog pattern, but is only ever armed while a
 * geofence is actually active: scheduleRenewal()/cancelRenewal() are called from
 * LocationLoggingService's registerGeofence()/clearGeofence(), not from onCreate/onDestroy.
 */
object GeofenceRenewalAlarmScheduler {
    private const val REQUEST_CODE = 4203

    fun scheduleRenewal(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = renewalPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + GeofenceState.RENEWAL_INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            // Ignore: worst case the next reschedule opportunity (service restart while the
            // geofence is still active) succeeds instead.
        }
    }

    fun cancelRenewal(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(renewalPendingIntent(context))
    }

    private fun renewalPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceRenewalAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

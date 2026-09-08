package com.koen.haremote.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Renewal safety net for an active stationary geofence (see LocationLoggingService /
 * GeofenceState): every 30 minutes, request one fresh GPS fix to reconfirm the client is still
 * where the geofence says it is, and re-report it to the server - independent of (and in
 * addition to) Play Services' own EXIT-transition detection, which this exists to backstop.
 * Mirrors AlarmScheduler's self-rescheduling watchdog pattern, but is only ever armed while a
 * geofence is actually active: scheduleRenewal()/cancelRenewal() are called from
 * LocationLoggingService's registerGeofence()/clearGeofence(), not from onCreate/onDestroy.
 *
 * **Bug fix (8 sept 2026):** this used to always arm the alarm 30 minutes from "now"
 * ([android.os.SystemClock.elapsedRealtime]), including from
 * `LocationLoggingService.resumeGeofenceIfNeeded` on every single service restart. On a device
 * that kills and restarts the service more often than every 30 minutes - confirmed in koen's
 * diagnostics.log: five `onCreate`s inside 45 minutes that evening - that meant the countdown
 * kept getting reset before it could ever reach zero, so the renewal check (and the location fix
 * it reports) silently stopped firing for as long as the geofence stayed active, well past the
 * "at least every 30 min" guarantee this class exists to provide. Now takes the target as an
 * explicit wall-clock instant ([System.currentTimeMillis]-based, via [AlarmManager.RTC_WAKEUP])
 * instead of an implicit "30 min from now" - callers persist that target in
 * [GeofenceState.saveNextRenewalAt] and re-read it with [GeofenceState.nextRenewalAt] so a
 * restart re-arms the *original* deadline rather than starting a fresh one. A target already in
 * the past (the process was dead through it) fires essentially immediately, which is exactly
 * what's wanted - an overdue check should run as soon as the service is back, not wait out
 * another 30 minutes on top.
 */
object GeofenceRenewalAlarmScheduler {
    private const val REQUEST_CODE = 4203

    fun scheduleRenewal(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = renewalPendingIntent(context)
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
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

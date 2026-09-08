package com.koen.haremote.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * A watchdog alarm, the same trick GPSLogger relies on: even if Android (or an
 * aggressive OEM battery manager) kills the foreground service, this inexact alarm
 * wakes the app up again a little later and restarts logging. It reschedules itself
 * every time it fires, and the service also (re)schedules it in onCreate/onDestroy.
 */
object AlarmScheduler {
    private const val REQUEST_CODE = 4202
    private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    fun scheduleWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = watchdogPendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (e: SecurityException) {
            // Ignore: worst case the service itself is still running and the next
            // reschedule attempt (e.g. after boot) will succeed.
        }
    }

    fun cancelWatchdog(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(watchdogPendingIntent(context))
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RestartAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

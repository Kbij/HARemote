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

    private const val RECONNECT_REQUEST_CODE = 4205
    private const val RECONNECT_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    /** Marks an alarm intent as the reconnect check (vs. the watchdog) - only used by
     *  [RestartAlarmReceiver] to say so in the log; both are handled identically. */
    const val ACTION_RECONNECT_CHECK = "com.koen.haremote.action.TCP_RECONNECT_CHECK"

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

    /**
     * "At least every 5 minutes" for the TCP connection (6 okt 2026): armed by
     * [LocationLoggingService] for as long as it has no connection to the server, cancelled as
     * soon as it has one. Goes through the same [RestartAlarmReceiver] as the watchdog (so it
     * also restarts the service if that got killed in the meantime), which starts the service,
     * which then nudges the TCP client and re-arms this alarm if still disconnected.
     *
     * An alarm rather than a timer inside the app because an alarm is the one thing that still
     * fires when the CPU has gone to sleep. Like the watchdog it is inexact and, in Doze, Android
     * itself decides how often an app may be woken (roughly once per 9-15 minutes if the app is
     * not exempt from battery optimisation) - nothing an app can do about that; when exempt, or
     * whenever the device is not dozing, it is 5 minutes.
     */
    fun scheduleReconnectCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + RECONNECT_INTERVAL_MS
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, reconnectPendingIntent(context))
        } catch (e: SecurityException) {
            // Ignore: the watchdog and the TCP client's own retry loop still cover this.
        }
    }

    fun cancelReconnectCheck(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(reconnectPendingIntent(context))
    }

    private fun reconnectPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RestartAlarmReceiver::class.java).setAction(ACTION_RECONNECT_CHECK)
        return PendingIntent.getBroadcast(
            context,
            RECONNECT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

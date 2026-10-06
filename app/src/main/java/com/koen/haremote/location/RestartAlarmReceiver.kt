package com.koen.haremote.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.koen.haremote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired by the watchdog alarm in [AlarmScheduler]. Starting an already-running
 * foreground service is a harmless no-op, so this simply makes sure the service is
 * (re)started whenever logging is supposed to be enabled, then reschedules itself.
 */
class RestartAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        // Logged unconditionally (even when logging is disabled) so a gap between this firing
        // and the service's own "onCreate" log line is visible: it tells us the watchdog fired
        // but the service didn't consider itself worth (re)starting, versus firing and the
        // service failing to start at all.
        //
        // Since 6 okt 2026 this receiver also serves the 5-minute reconnect alarm (see
        // AlarmScheduler.scheduleReconnectCheck). Both do exactly the same thing here - make
        // sure the service is running and poke it; the service's onStartCommand then checks
        // its TCP connection - so the only difference is the log line.
        if (intent.action == AlarmScheduler.ACTION_RECONNECT_CHECK) {
            DiagnosticLogger.log(appContext, "Tcp", "Reconnect-alarm fired (nog geen verbinding met de server)")
        } else {
            DiagnosticLogger.log(appContext, "Watchdog", "Alarm fired")
        }
        // Reschedule the *next* alarm right away, synchronously - before the async settings
        // read below. Each firing only re-arms the next one, so if the process gets
        // frozen/killed while that coroutine is still running (very plausible: this receiver
        // fires exactly when the OS considers the app worth suspending), the whole watchdog
        // chain would otherwise die silently right here, with no further alarms ever firing
        // again. Rescheduling first means that can't happen, at the cost of occasionally
        // arming one extra alarm that turns out to be a no-op (logging disabled by then).
        AlarmScheduler.scheduleWatchdog(appContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settingsFlow.first()
                if (settings.gpsLoggingEnabled) {
                    LocationLoggingService.start(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

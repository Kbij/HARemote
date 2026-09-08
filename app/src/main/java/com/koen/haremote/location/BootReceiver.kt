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
 * Restarts GPS logging after a reboot, after the app is updated, or (on some OEMs)
 * after a "quick boot". Without this, a rebooted phone would silently stop logging
 * until the user manually reopens the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val pendingResult = goAsync()
                val appContext = context.applicationContext
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
    }
}

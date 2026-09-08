package com.koen.haremote.location

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Snapshot of the OS-level power-management state that determines whether
 * [LocationLoggingService] can actually be trusted to run in the background:
 * - whether the app is on the classic Doze/battery-optimization exemption list
 *   (the "Batterijoptimalisatie negeren" button already requests this — this just
 *   reports whether it's actually granted right now, since the OS can silently
 *   revoke it after enough force-stops/kills).
 * - the app's current "App Standby Bucket" (Android 9+): this is the *separate*,
 *   usage-based mechanism that throttles background jobs/alarms — a "Beperkt"
 *   (Restricted) bucket is a strong, independently-verifiable signal that Android
 *   itself, not just an OEM battery manager, is choking this app off.
 * - whether the device is currently in system-wide power save mode.
 *
 * Surfaced in Settings (so this can be checked at a glance, no PC/logcat needed) and
 * logged on every service start via [DiagnosticLogger], so the exported log shows how
 * it evolves over time (e.g. a burst of OS kills pushing the app into a stricter bucket).
 */
object BatteryDiagnostics {

    /** Raw value of the hidden UsageStatsManager.STANDBY_BUCKET_EXEMPTED constant. */
    private const val EXEMPTED_BUCKET_VALUE = 5

    data class Status(
        val ignoringBatteryOptimizations: Boolean,
        val standbyBucketLabel: String,
        val powerSaveMode: Boolean
    ) {
        fun summary(): String =
            "ignoringOptimizations=$ignoringBatteryOptimizations bucket=$standbyBucketLabel powerSaveMode=$powerSaveMode"
    }

    fun current(context: Context): Status {
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(appContext.packageName)
        val powerSave = pm.isPowerSaveMode

        val bucketLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            when (usm.appStandbyBucket) {
                // STANDBY_BUCKET_EXEMPTED exists (value 5, one better than ACTIVE) but is
                // @hide/@SystemApi - not part of the public SDK, so it can't be referenced
                // by name here (this is what broke the build). Match its raw value instead.
                EXEMPTED_BUCKET_VALUE -> "Vrijgesteld"
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Actief"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "Zelden (rare)"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "BEPERKT (restricted)"
                else -> "Onbekend"
            }
        } else {
            "N/A (Android < 9)"
        }

        return Status(ignoring, bucketLabel, powerSave)
    }
}

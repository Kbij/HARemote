package com.koen.haremote

import android.app.Application
import android.util.Log
import com.koen.haremote.location.DiagnosticLogger

class HaRemoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogging()
    }

    /**
     * Logs the full stack trace of any uncaught exception to [DiagnosticLogger] before letting
     * it crash the app exactly as before (chains to whatever default handler was already
     * installed, so Android's own crash dialog/reporting is unaffected) - added after a report
     * of the app dying right after submitting the admin PIN, with nothing in the log beyond
     * "the process restarted" to go on. This makes the *next* diagnostics.log actually show
     * what threw, from which thread, and where - instead of having to guess again.
     */
    private fun installCrashLogging() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticLogger.log(
                    this,
                    "Crash",
                    "Onafgevangen exceptie op thread '${thread.name}': ${Log.getStackTraceString(throwable)}"
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

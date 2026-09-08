package com.koen.haremote.location

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny, append-only diagnostic log for [LocationLoggingService]'s lifecycle and itms TCP
 * connection state. Written to survive without a debugger attached: a plain text file under
 * the app's external files dir (`Android/data/com.koen.haremote/files/diagnostics.log` on the
 * device's own storage, falling back to internal storage if that's unavailable), so it can be
 * shared straight from the phone (see `SettingsScreen`'s "Deel logbestand" button /
 * `MainActivity.shareDiagnosticLog`) without needing USB debugging or a live logcat session.
 *
 * Deliberately simple — synchronous, unbuffered file append, called only from rare lifecycle
 * events (not per-location-fix) — so a write has the best chance of completing even in the
 * split second before the process gets killed.
 */
object DiagnosticLogger {
    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_SIZE_BYTES = 512 * 1024L // rotate before the file grows unbounded
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        runCatching {
            val file = logFile(context)
            if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                // Keep only the newest half instead of growing forever.
                val lines = file.readLines()
                file.writeText(lines.drop(lines.size / 2).joinToString(separator = "\n", postfix = "\n"))
            }
            val timestamp = timeFormat.format(Date())
            file.appendText("$timestamp [$tag] $message\n")
        }
    }

    /** Where the log lives. Also used by [MainActivity] to build the share `Intent`. */
    fun logFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /** Wipes the log so the next test window starts from a clean slate. */
    @Synchronized
    fun clear(context: Context) {
        runCatching { logFile(context).writeText("") }
    }
}

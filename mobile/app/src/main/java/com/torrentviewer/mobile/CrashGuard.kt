package com.torrentviewer.mobile

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps one failing background task from closing the whole app, and keeps what went wrong to read later.
 * Plugins are other people's code running on their own threads: an error there used to end the app. Now an
 * error off the main thread is written down and that task alone stops; one on the main thread is written
 * down and the app closes as before (it can't carry on). Settings → Last crash report shows the latest.
 */
object CrashGuard {
    private var file: File? = null

    fun install(context: Context) {
        if (file != null) return
        val log = File(context.filesDir, "last-crash.txt").also { file = it }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val main = thread === Looper.getMainLooper().thread
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                log.writeText(
                    "WatchHub ${BuildConfig.VERSION_NAME} · ${java.util.Date()} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                        (if (main) "The app closed.\n" else "A background task failed (thread ${thread.name}); the app carried on.\n") + trace.take(12_000),
                )
            }
            if (main) previous?.uncaughtException(thread, error)
            else Log.e("CrashGuard", "background task failed on ${thread.name}", error)
        }
    }

    /** The latest report, or null when nothing has gone wrong. */
    fun last(): String? = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() {
        runCatching { file?.delete() }
    }
}

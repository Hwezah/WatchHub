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
            when {
                !main -> Log.e("CrashGuard", "background task failed on ${thread.name}", error)
                // A plugin's own screen (its settings dialog) failed: the app's screens are sound, so the main thread
                // takes up its message queue again instead of closing the app. Anything else closes it as before.
                fromPlugin(error, context) -> {
                    Log.e("CrashGuard", "a plugin failed on the main thread; carrying on", error)
                    runCatching { log.writeText(log.readText().replaceFirst("The app closed.", "A plugin failed; the app carried on.")) }
                    runCatching { android.widget.Toast.makeText(context, "A plugin failed — see Settings → Last crash report", android.widget.Toast.LENGTH_LONG).show() }
                    while (true) {
                        try {
                            Looper.loop()
                            return@setDefaultUncaughtExceptionHandler
                        } catch (again: Throwable) {
                            if (!fromPlugin(again, context)) {
                                previous?.uncaughtException(thread, again)
                                return@setDefaultUncaughtExceptionHandler
                            }
                            Log.e("CrashGuard", "a plugin failed again on the main thread", again)
                        }
                    }
                }
                else -> previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Whether [error] was thrown inside a plugin: one of its nearest frames is a class the app itself can't load
     * (plugins live in their own class loaders), android and java frames aside.
     */
    private fun fromPlugin(error: Throwable, context: Context): Boolean = error.stackTrace.take(12).any { frame ->
        val name = frame.className
        SYSTEM.none { name.startsWith(it) } && runCatching { Class.forName(name, false, context.classLoader) }.isFailure
    }

    private val SYSTEM = listOf("android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "com.android.", "dalvik.", "libcore.", "sun.")

    /** The latest report, or null when nothing has gone wrong. */
    fun last(): String? = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() {
        runCatching { file?.delete() }
    }
}

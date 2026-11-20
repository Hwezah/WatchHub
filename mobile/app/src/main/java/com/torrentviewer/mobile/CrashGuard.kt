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
 * down and the app closes as before (it can't carry on). Settings → Last crash report shows the latest, with Android's
 * own record of the app's recent unusual ends ([exits]). Installed wherever the app can start: the activity, and the
 * download and Tube services, which Android may start on their own.
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

    /** When the viewer last cleared the report: Android's own record (it can't be wiped) is read from then on. */
    private fun clearedFile(context: Context) = File(context.filesDir, "crash-cleared-at.txt")

    fun clear(context: Context) {
        runCatching { file?.delete() }
        runCatching { clearedFile(context).writeText(System.currentTimeMillis().toString()) }
    }

    /**
     * Everything there is to read: the last app error ([last]) and Android's record of the app's recent unusual ends
     * ([exits]) — a native crash, a freeze or a memory kill never reaches the error handler. Null when there's neither.
     */
    fun report(context: Context): String? {
        val ends = exits(context)?.let { "Android's record of how the app last ended (WatchHub ${BuildConfig.VERSION_NAME} now):\n\n$it" }
        return listOfNotNull(last(), ends).joinToString("\n\n").ifBlank { null }
    }

    /**
     * Android's own record of the app's last few unusual ends since the report was cleared (Android 11+), newest first:
     * when, what, whether it was on screen, and for a native crash or a freeze the first lines of its trace. Null when
     * there are none or on older Android.
     */
    fun exits(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val parts = ends(context).map { e ->
            val label = com.torrentviewer.core.exitLabel(e.reason)
            val where = when {
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "open on screen"
                e.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "downloading or playing in the background"
                else -> "in the background"
            }
            val head = "${java.util.Date(e.timestamp)} · $label" + (if (e.reason == android.app.ApplicationExitInfo.REASON_SIGNALED) " (signal ${e.status})" else "") +
                " · $where" + (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            head + (trace(e)?.let { "\n$it" } ?: "")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Whether there's anything to read ([report]), without reading the traces: for Settings' list. */
    fun anything(context: Context): Boolean =
        last() != null || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && ends(context).isNotEmpty()

    /** Android's records of the app's unusual ends since the report was cleared, newest first, at most five. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun ends(context: Context): List<android.app.ApplicationExitInfo> {
        val since = runCatching { clearedFile(context).readText().trim().toLong() }.getOrDefault(0L)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val all = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 16) }.getOrNull().orEmpty()
        return all.filter { it.timestamp > since && com.torrentviewer.core.exitLabel(it.reason) != null }.take(5)
    }

    /** A native crash's or a freeze's trace, its telling lines only; at most 2 MB of it is read. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun trace(e: android.app.ApplicationExitInfo): String? = runCatching {
        val native = e.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
        if (!native && e.reason != android.app.ApplicationExitInfo.REASON_ANR) return null
        val bytes = e.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < 2_000_000) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: return null
        val lines = if (native) com.torrentviewer.core.readableRuns(bytes, limit = 40) else com.torrentviewer.core.anrMainThread(String(bytes))
        lines.joinToString("\n") { "  $it" }.takeIf { it.isNotBlank() }
    }.getOrNull()
}

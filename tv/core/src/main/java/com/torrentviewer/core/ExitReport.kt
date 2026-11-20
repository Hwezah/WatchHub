package com.torrentviewer.core

/*
 * Android's own record of how the app's last runs ended (ApplicationExitInfo, Android 11+), put into words for
 * Settings → Last crash report. The app's error handler sees only Kotlin/Java errors; this also shows a crash in
 * native code (the web page engine, the players, libtorrent), an app that stopped answering, and one closed for memory.
 */

/**
 * What an ApplicationExitInfo reason ([reason], its REASON_* number) means, in a few words; null for an ordinary end,
 * which isn't worth reporting: closed by the viewer, by the app itself, for an update or a permission change. The
 * numbers are Android's own (REASON_SIGNALED = 2 … REASON_FREEZER = 14), written out so this stays a plain function.
 */
fun exitLabel(reason: Int): String? = when (reason) {
    2 -> "Ended by a signal"
    3 -> "Closed by Android to free memory"
    4 -> "Crashed (an app error)"
    5 -> "Crashed in native code"
    6 -> "Stopped responding (ANR)"
    7 -> "Failed to start"
    9 -> "Closed by Android for using too much"
    12 -> "Something it depends on died"
    14 -> "Closed by Android while frozen"
    else -> null
}

/** Hex alone (a library's build id, an address): only noise in a crash's text. */
private val HEX_ONLY = Regex("^[0-9a-fA-F]+$")

/**
 * The readable text in a native crash's record, which Android hands over in a binary form (a tombstone): runs of at
 * least [min] printable characters, in order, each once, at most [limit] of them, hex-only runs (build ids,
 * addresses) left out. The abort message, the signal and the libraries and functions of the backtrace are among them,
 * which is what tells one crash from another.
 */
fun readableRuns(bytes: ByteArray, min: Int = 6, limit: Int = 60): List<String> {
    val out = LinkedHashSet<String>()
    val run = StringBuilder()
    fun end() {
        val s = run.toString().trim()
        if (s.length >= min && !HEX_ONLY.matches(s)) out += s
        run.setLength(0)
    }
    for (b in bytes) {
        if (out.size >= limit) break
        val c = b.toInt() and 0xFF
        if (c in 0x20..0x7E) run.append(c.toChar()) else end()
    }
    if (out.size < limit) end()
    return out.take(limit)
}

/**
 * The part of a freeze's (ANR's) thread dump that says what the app was stuck on: from the main thread's entry
 * ("main" prio=…) [lines] lines on; the dump's start when there is no such entry.
 */
fun anrMainThread(dump: String, lines: Int = 30): List<String> {
    val all = dump.lines().filter { it.isNotBlank() }
    val start = all.indexOfFirst { it.startsWith("\"main\"") }.coerceAtLeast(0)
    return all.drop(start).take(lines)
}

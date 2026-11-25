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

/**
 * A native crash's record (Android's tombstone, a protobuf) read properly: the signal and its cause, the abort message,
 * then the thread that crashed — named — with its frames, library and function, which is what says whose code it was.
 * [readableRuns] only pulled strings out, every thread's mixed in map order, and the crashing one could be cut off
 * past its limit (the owner's report showed two bystanders). Null when [bytes] isn't one; at most [frames] frames.
 */
fun readTombstone(bytes: ByteArray, frames: Int = 24): List<String>? = runCatching {
    val top = protoFields(bytes)
    val tid = top.firstOrNull { it.no == 6 }?.number ?: return null
    val out = ArrayList<String>()
    top.firstOrNull { it.no == 10 }?.bytes?.let { s ->
        val f = protoFields(s)
        val name = f.firstOrNull { it.no == 2 }?.text
        val code = f.firstOrNull { it.no == 4 }?.text
        val at = f.firstOrNull { it.no == 9 }?.number
        if (name != null) out += listOfNotNull(name, code, at?.let { "at 0x%x".format(it) }).joinToString(" · ")
    }
    top.filter { it.no == 15 }.mapNotNull { c -> protoFields(c.bytes ?: return@mapNotNull null).firstOrNull { it.no == 1 }?.text }.forEach { out += it }
    top.firstOrNull { it.no == 14 }?.text?.takeIf { it.isNotBlank() }?.let { out += "Abort: $it" }
    // The threads map: entries of key (1, the thread id) and value (2, the thread).
    val threads = top.filter { it.no == 16 }.mapNotNull { e ->
        val f = protoFields(e.bytes ?: return@mapNotNull null)
        val id = f.firstOrNull { it.no == 1 }?.number ?: return@mapNotNull null
        id to (f.firstOrNull { it.no == 2 }?.bytes ?: return@mapNotNull null)
    }
    val crashed = threads.firstOrNull { it.first == tid }?.second ?: return out.takeIf { it.isNotEmpty() }
    val t = protoFields(crashed)
    out += "Crashed in thread \"${t.firstOrNull { it.no == 2 }?.text.orEmpty()}\" ($tid):"
    t.filter { it.no == 4 }.take(frames).forEachIndexed { i, fr ->
        val f = protoFields(fr.bytes ?: return@forEachIndexed)
        val fn = f.firstOrNull { it.no == 4 }?.text.orEmpty()
        val off = f.firstOrNull { it.no == 5 }?.number ?: 0
        val file = f.firstOrNull { it.no == 6 }?.text.orEmpty().substringAfterLast('/')
        out += "#%02d %s%s".format(i, file.ifEmpty { "?" }, if (fn.isNotEmpty()) "  $fn+$off" else "")
    }
    // Who else was doing what, by name only: a crash in a shared thread can still point at the work beside it.
    val others = threads.filter { it.first != tid }.mapNotNull { protoFields(it.second).firstOrNull { f -> f.no == 2 }?.text }.distinct()
    if (others.isNotEmpty()) out += "Other threads: " + others.take(30).joinToString(", ")
    out
}.getOrNull()?.takeIf { it.isNotEmpty() }

/** One protobuf field: its number, and a varint ([number]) or length-delimited ([bytes]) value. */
private class ProtoField(val no: Int, val number: Long?, val bytes: ByteArray?) {
    val text: String? get() = bytes?.let { String(it, Charsets.UTF_8) }
}

/** [b]'s fields at one level, in order; fixed-width ones skipped. Throws on a malformed message. */
private fun protoFields(b: ByteArray): List<ProtoField> {
    val out = ArrayList<ProtoField>()
    var i = 0
    fun varint(): Long {
        var shift = 0
        var v = 0L
        while (true) {
            require(i < b.size && shift < 64)
            val c = b[i++].toInt() and 0xFF
            v = v or ((c and 0x7F).toLong() shl shift)
            if (c and 0x80 == 0) return v
            shift += 7
        }
    }
    while (i < b.size) {
        val key = varint()
        val no = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> out += ProtoField(no, varint(), null)
            1 -> { require(i + 8 <= b.size); i += 8 }
            2 -> {
                val n = varint().toInt()
                require(n >= 0 && i + n <= b.size)
                out += ProtoField(no, null, b.copyOfRange(i, i + n))
                i += n
            }
            5 -> { require(i + 4 <= b.size); i += 4 }
            else -> throw IllegalArgumentException("wire type")
        }
    }
    return out
}

package com.torrentviewer.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/** A download that can't be done, with the reason to show. */
open class LinkDownloadException(message: String) : IOException(message)

/** The link is dead — expired, refused, or its server gone — but a fresh one for the same video may work. */
class LinkExpiredException(message: String) : LinkDownloadException(message)

/**
 * The host said "too many requests" (429) or "busy" (503): this phone is being held back, not the link dead. Asking
 * again at once only keeps the limit on; [retryAfter] is the wait in seconds the host asked for, when it said.
 */
class BusyException(val code: Int, val retryAfter: Int?) : LinkDownloadException("The host is busy ($code)")

/** A host's busy answer as a [BusyException], its Retry-After (seconds) read when it gives one. */
private fun busy(conn: HttpURLConnection, code: Int) = BusyException(code, conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull())

/** No video is this small: under it, a "download" is an error or "click here" page the host sent instead. */
const val MIN_VIDEO_BYTES = 2L * 1024 * 1024

/**
 * Whether [file] can't be the video it was meant to be: smaller than [MIN_VIDEO_BYTES], or text — a web page
 * ("<!DOCTYPE html…", "<html") or a JSON error — rather than a video container.
 */
fun notVideo(file: java.io.File): Boolean {
    if (!file.exists() || file.length() < MIN_VIDEO_BYTES) return true
    val head = runCatching { file.inputStream().use { input -> ByteArray(64).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } } }.getOrNull() ?: return false
    return looksLikeText(head)
}

/** The first bytes of a file, read as a page or JSON: "<", "{" or "[" after any spaces. */
fun looksLikeText(head: ByteArray): Boolean {
    val start = head.indexOfFirst { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
    if (start < 0) return true
    return head[start] == '<'.code.toByte() || head[start] == '{'.code.toByte() || head[start] == '['.code.toByte()
}

/** What a server says to a link whose token ran out or that it no longer serves. */
private val EXPIRED = setOf(400, 401, 403, 404, 410)

/** What was kept belonged to another file: thrown away, and the download starts once more from zero. */
private class RestartException : Exception()

/**
 * Saves a direct link to a file: one file as it is (resumed with a Range request), or an HLS playlist
 * as its segments joined in order — MPEG-TS segments into a .ts, fMP4 ones behind their init section
 * into an .mp4. AES-128 segments are decrypted. The link's headers (a plugin's Referer, an addon's
 * proxyHeaders) go with every request. Progress is kept in [work], so a pause or a restart carries on
 * from the last whole segment.
 */
object LinkFetch {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    /** Segments fetched at once: enough to fill the line, few enough not to look like an attack to a CDN. */
    private const val PARALLEL = 4
    private const val TRIES = 4

    /** Playlist segments fetched at once, at most: each is held whole in memory (a few MB) until written. */
    const val HLS_MAX = 12

    /** What [download] made: the file in [work], and the extension it should be kept under. */
    data class Result(val file: File, val ext: String)

    /** [done] and [total] are bytes; [total] is an estimate for a playlist, from the segments so far. */
    fun interface Progress {
        fun report(done: Long, total: Long, speed: Int)
    }

    suspend fun download(url: String, headers: Map<String, String>, quality: String?, playlist: Boolean, work: File, connections: Int = 1, progress: Progress): Result {
        work.mkdirs()
        return if (playlist) // Whole segments are held in memory while they're fetched and decrypted: [HLS_MAX] at once keeps that to a few tens of MB.
        hls(url, headers, quality, work, connections.coerceIn(PARALLEL, HLS_MAX), progress)
        else multi(url, headers, work, connections, progress) ?: try {
            single(url, headers, work, progress)
        } catch (_: RestartException) {
            // What was kept belonged to another file: once more, from the start.
            single(url, headers, work, progress)
        }
    }

    /* ------------------------------------------------------ many connections */

    /** A file under this is fetched over one connection: splitting it buys nothing. */
    private const val MULTI_MIN = 16L * 1024 * 1024

    /**
     * One file over [connections] connections at once, as 1DM does: the file is cut into parts ([partsOf]),
     * each fetched with a Range request into its place in the file; workers take the next unfinished part
     * as they free up. What each part has is kept in parts.json, so a pause or a restart carries on part by
     * part. Null when this can't be done — one connection asked for, a server without ranges, a small or
     * unknown size — and the plain single download runs instead.
     */
    private suspend fun multi(url: String, headers: Map<String, String>, work: File, connections: Int, progress: Progress): Result? {
        if (connections <= 1) return null
        val out = File(work, "data.part")
        val stateFile = File(work, "parts.json")
        // Can this server do ranges, and how big is the file? One byte asked for says both — asked every start, so a
        // download resumed days later, or on a renewed link, carries on only when it is still the very same size.
        val probe = withContext(Dispatchers.IO) {
            val conn = open(url, headers, "bytes=0-0")
            try {
                val code = conn.responseCode
                if (code in EXPIRED) throw LinkExpiredException("The link has expired (the server answered $code)")
                // Held back: said at once, rather than asked again over one connection straight away.
                if (code == 429 || code == 503) throw busy(conn, code)
                val size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (code == 206 && size != null) size to extOf(url, conn.contentType) else null
            } finally {
                conn.disconnect()
            }
        }
        val kept = runCatching { JSONObject(stateFile.readText()) }.getOrNull()?.takeIf { probe != null && it.optLong("total") == probe.first && out.exists() }
        if (probe == null || probe.first < MULTI_MIN) {
            // The parts kept (if any) can't be carried on over one connection: the plain download starts afresh.
            if (stateFile.exists()) {
                out.delete()
                stateFile.delete()
            }
            return null
        }
        val plan: JSONObject = kept ?: run {
            val (size, kind) = probe
            // Another file's leftovers — a single download's, or a different size's — are of no use to this one.
            out.delete()
            File(work, SINGLE_STATE).delete()
            withContext(Dispatchers.IO) { RandomAccessFile(out, "rw").use { it.setLength(size) } }
            // Planned for the first few connections; the rest split stretches as they join ([Run.take]).
            val parts = partsOf(size, START_WITH)
            JSONObject().put("total", size).put("ext", kind).put("starts", org.json.JSONArray(parts.map { it.first }))
                .put("ends", org.json.JSONArray(parts.map { it.second })).put("done", org.json.JSONArray(parts.map { 0L }))
                .also { stateFile.writeText(it.toString()) }
        }
        val total = plan.getLong("total")
        val ext = plan.optString("ext", "mp4")
        val starts = plan.getJSONArray("starts").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val ends = plan.getJSONArray("ends").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val dones = plan.getJSONArray("done").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val run = Run(url, headers, out, total, starts.indices.map { Seg(starts[it], ends[it], dones[it]) }.toMutableList(), connections, progress) { segs ->
            plan.put("starts", org.json.JSONArray(segs.map { it.start })).put("ends", org.json.JSONArray(segs.map { it.end }))
                .put("done", org.json.JSONArray(segs.map { it.done }))
            stateFile.writeText(plan.toString())
        }
        run.go()
        stateFile.delete()
        progress.report(total, total, 0)
        return Result(out, ext)
    }

    /** One stretch of the file: from [start] to [end] (inclusive; shortened when another connection takes its back half), [done] bytes in. */
    private class Seg(val start: Long, var end: Long, var done: Long) {
        var busy = false
        val pos get() = start + done
        val remaining get() = end - pos + 1
        val finished get() = pos > end
    }

    /** A connection open now, for the stall watch: when it last got a byte, and how fast it's going. */
    private class Live(val conn: HttpURLConnection) {
        @Volatile var last = System.currentTimeMillis()
        @Volatile var bytes = 0L
        @Volatile var mark = 0L
        @Volatile var speed = 0L
        @Volatile var kicked = false
        val born = System.currentTimeMillis()
    }

    /** The server turned one connection away ([code]): retired while others work, else waited on and tried again. */
    private class RefusedException(val code: Int, val retryAfter: Int?) : IOException("refused ($code)")

    /** Dropped by the stall watch: straight back on a new connection, not counted as a failure. */
    private class StallException : IOException("Connection stalled")

    /**
     * One file over many range connections, as Grabber does it (the owner's own downloader, which got files WatchHub
     * failed on, and faster):
     * - ramp-up: [START_WITH] connections, more every few seconds while the total speed keeps rising, up to [max] —
     *   64 at once looked like an attack to hosts, which dropped them, and the download went looking for another source;
     * - a refused connection (429, 503, a picky CDN's 403) retires and the rest carry on; no more are added after one;
     * - work stealing: a connection with nothing left takes the back half of the biggest stretch still going;
     * - stall watch: a connection silent for [STALL_MS], or crawling far below the others, is dropped and reconnects.
     * Progress goes to [save] every couple of seconds, in parts.json's form, so a pause carries on stretch by stretch.
     */
    private class Run(
        val url: String, val headers: Map<String, String>, val out: File, val total: Long, val segs: MutableList<Seg>, val max: Int,
        val progress: Progress, val save: (List<Seg>) -> Unit,
    ) {
        val lock = Any()
        val lives = java.util.concurrent.ConcurrentHashMap.newKeySet<Live>()
        val alive = java.util.concurrent.atomic.AtomicInteger()
        val got = java.util.concurrent.atomic.AtomicLong(segs.sumOf { it.done })
        @Volatile var refused = false
        val meter = Meter(got.get())
        var lastSave = 0L

        fun keep(force: Boolean = false) = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSave < 2000) return@synchronized
            lastSave = now
            runCatching { save(segs.sortedBy { it.start }.map { Seg(it.start, it.end, it.done) }) }
        }

        suspend fun go() {
            synchronized(lock) { segs.forEach { it.busy = false } }
            try {
                coroutineScope {
                    val watch = launch { watchdog() }
                    try {
                        coroutineScope {
                            val first = minOf(max, START_WITH)
                            repeat(first) { spawn(this) }
                            launch { rampUp(this@coroutineScope, first) }
                        }
                    } finally {
                        watch.cancel()
                    }
                }
            } finally {
                keep(force = true)
            }
            if (synchronized(lock) { segs.any { !it.finished } }) throw LinkDownloadException("The download stopped early — try again")
        }

        fun spawn(scope: kotlinx.coroutines.CoroutineScope) {
            alive.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                try {
                    RandomAccessFile(out, "rw").use { raf -> worker(raf) }
                } finally {
                    alive.decrementAndGet()
                }
            }
        }

        /** More connections in steps while the total speed keeps climbing; it stops when it levels off or a host refuses. */
        suspend fun rampUp(scope: kotlinx.coroutines.CoroutineScope, first: Int) {
            var running = first
            var lastRate = 0L
            var flat = 0
            var before = got.get()
            while (running < max) {
                delay(RAMP_MS)
                if (refused || alive.get() == 0) return
                val nothingToSplit = synchronized(lock) { segs.all { it.finished || it.busy && it.remaining < MIN_SPLIT * 2 } }
                if (nothingToSplit) return
                val now = got.get()
                val rate = (now - before) * 1000 / RAMP_MS
                before = now
                if (lastRate > 0 && rate < lastRate * 11 / 10) { if (++flat >= 2) return } else flat = 0
                lastRate = rate
                val add = minOf(max - running, maxOf(2, running / 2))
                repeat(add) { spawn(scope) }
                running += add
            }
        }

        /** Every second: a connection silent too long, or crawling at a tenth of the typical speed, is dropped. */
        suspend fun watchdog() {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                lives.forEach { l -> l.speed = l.bytes - l.mark; l.mark = l.bytes }
                val speeds = lives.filter { now - it.born > 6_000 }.map { it.speed }.sorted()
                val typical = if (speeds.size >= 4) speeds[speeds.size / 2] else 0L
                lives.forEach { l ->
                    val silent = now - l.last > STALL_MS
                    val crawling = typical > 64 * 1024 && now - l.born > 10_000 && l.speed < typical / 10
                    if ((silent || crawling) && !l.kicked) {
                        l.kicked = true
                        runCatching { l.conn.disconnect() }
                    }
                }
            }
        }

        /** The next stretch for a free connection: one nobody is on, else the back half of the biggest still going. */
        fun take(): Seg? = synchronized(lock) {
            segs.firstOrNull { !it.busy && !it.finished }?.let { it.busy = true; return it }
            val big = segs.filter { it.busy && !it.finished }.maxByOrNull { it.remaining } ?: return null
            if (big.remaining < MIN_SPLIT * 2) return null
            val mid = big.pos + big.remaining / 2
            val part = Seg(mid, big.end, 0).also { it.busy = true }
            big.end = mid - 1
            segs += part
            part
        }

        suspend fun worker(raf: RandomAccessFile) {
            while (true) {
                val seg = take() ?: return
                var failures = 0
                var stalls = 0
                while (!synchronized(lock) { seg.finished }) {
                    coroutineContext.ensureActive()
                    try {
                        val before = synchronized(lock) { seg.done }
                        fetch(seg, raf)
                        if (synchronized(lock) { seg.done } > before) failures = 0
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: RefusedException) {
                        refused = true
                        // Others still working: this connection gives its stretch back and closes; they take it up.
                        val others = synchronized(lock) {
                            seg.busy = false
                            alive.get() > 1
                        }
                        if (others) return
                        synchronized(lock) { seg.busy = true }
                        if (++failures > MAX_RETRIES) throw when {
                            e.code in EXPIRED -> LinkExpiredException("The link has expired (the server answered ${e.code})")
                            e.code == 429 || e.code == 503 -> BusyException(e.code, e.retryAfter)
                            else -> LinkDownloadException("The server answered ${e.code}")
                        }
                        // The link itself gone (not just "too many"): no use waiting out ten tries alone.
                        if (e.code in EXPIRED && failures >= 3) throw LinkExpiredException("The link has expired (the server answered ${e.code})")
                        delay(e.retryAfter?.let { it * 1000L }?.coerceAtMost(60_000) ?: backoff(failures))
                    } catch (e: StallException) {
                        if (++stalls <= 30) continue
                        if (++failures > MAX_RETRIES) throw LinkDownloadException("A part kept stalling")
                        delay(backoff(failures))
                    } catch (e: LinkDownloadException) {
                        throw e
                    } catch (e: IOException) {
                        if (++failures > MAX_RETRIES) throw LinkDownloadException("A part kept failing: ${e.message}")
                        delay(backoff(failures))
                    }
                }
                synchronized(lock) { seg.busy = false }
            }
        }

        fun backoff(n: Int) = minOf(30_000L, 500L shl minOf(n, 6))

        /** [seg] from where it got to, over one connection, up to its end (which may move in while it runs). */
        suspend fun fetch(seg: Seg, raf: RandomAccessFile) {
            val (from, to) = synchronized(lock) { seg.pos to seg.end }
            if (from > to) return
            val conn = open(url, headers, "bytes=$from-$to")
            val live = Live(conn)
            lives += live
            try {
                val code = conn.responseCode
                if (code == 429 || code == 503 || code in 400..499) throw RefusedException(code, conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull())
                if (code != 206) {
                    if (code in 200..299) throw LinkDownloadException("The server stopped allowing parts (answered $code)")
                    throw IOException("The server answered $code")
                }
                val size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                if (size != null && size != total) throw LinkDownloadException("The file changed on the server")
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        // Paused or cancelled: stops between reads.
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        live.last = System.currentTimeMillis()
                        live.bytes += n
                        // Only up to the stretch's end: another connection may have taken its back half meanwhile.
                        val (at, take) = synchronized(lock) { seg.pos to minOf(n.toLong(), seg.remaining).toInt().coerceAtLeast(0) }
                        if (take > 0) {
                            raf.seek(at)
                            raf.write(buf, 0, take)
                            synchronized(lock) { seg.done += take }
                            got.addAndGet(take.toLong())
                            synchronized(meter) { meter.add(take.toLong()) }?.let { rate -> progress.report(got.get(), total, rate) }
                            keep()
                        }
                        if (take < n) break
                    }
                }
            } catch (e: IOException) {
                if (live.kicked && e !is RefusedException && e !is LinkDownloadException) throw StallException()
                throw e
            } finally {
                lives -= live
                runCatching { conn.disconnect() }
            }
        }
    }

    /** Connections a link starts with ([Run]); more join while they make it faster. */
    private const val START_WITH = 4

    /** How often the ramp-up looks at the speed and adds connections. */
    private const val RAMP_MS = 2500L

    /** A connection that has sent nothing for this long is dropped and reconnected. */
    private const val STALL_MS = 8_000L

    /** A stretch is never split into halves smaller than this. */
    private const val MIN_SPLIT = 1024L * 1024

    /** Tries per stretch before the link counts as failing. */
    private const val MAX_RETRIES = 10

    /**
     * Whether an old link still leads to the video, asked before a paused or waiting download carries on with
     * it: a playlist that still opens, or a file that answers with something other than a web page. Quick,
     * and false on any doubt, so a fresh link is looked for instead.
     */
    suspend fun answers(url: String, headers: Map<String, String>, playlist: Boolean): Boolean = check(url, headers, playlist) != LinkCheck.DEAD

    /**
     * A quick look at a link before it's used: one byte asked for. Dead (no answer, an error, or a web page), the whole
     * file only (the server ignores ranges, so a stop means starting over), or [LinkCheck.RESUMABLE]. A playlist that
     * reads as one counts as resumable: its segments are kept one by one.
     */
    suspend fun check(url: String, headers: Map<String, String>, playlist: Boolean): LinkCheck = withTimeoutOrNull(20_000) {
        runCatching {
            if (playlist) {
                if (fetchText(url, headers).trimStart('\uFEFF').trimStart().startsWith("#EXTM3U")) LinkCheck.RESUMABLE else LinkCheck.DEAD
            } else withContext(Dispatchers.IO) {
                val conn = open(url, headers, "bytes=0-0")
                try {
                    val code = conn.responseCode
                    when {
                        code !in 200..299 || conn.contentType?.startsWith("text/", true) == true -> LinkCheck.DEAD
                        code == 206 -> LinkCheck.RESUMABLE
                        else -> LinkCheck.WHOLE
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }.getOrDefault(LinkCheck.DEAD)
    } ?: LinkCheck.DEAD

    /* ---------------------------------------------------------------- one file */

    /** A single download's expected size, so a resume (perhaps days later, perhaps on a renewed link) appends only to the same file. */
    private const val SINGLE_STATE = "single.json"

    private suspend fun single(url: String, headers: Map<String, String>, work: File, progress: Progress): Result = withContext(Dispatchers.IO) {
        val out = File(work, "data.part")
        val meta = File(work, SINGLE_STATE)
        val was = runCatching { JSONObject(meta.readText()).optLong("total") }.getOrDefault(0L)
        // Bytes with no record of what file they belong to (another kind of download's) can't be appended to.
        if (out.exists() && was <= 0) out.delete()
        var have = if (out.exists()) out.length() else 0L
        fun again(): Nothing = throw RestartException()
        val conn = open(url, headers, if (have > 0) "bytes=$have-" else null)
        try {
            val code = conn.responseCode
            if (code == 416) {
                // Asked past the end: done, if this is the same file.
                val size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (size == null || size == have) return@withContext Result(out, extOf(url, conn.contentType))
                out.delete(); meta.delete(); again()
            }
            if (code in EXPIRED) throw LinkExpiredException("The link has expired (the server answered $code)")
            if (code == 429 || code == 503) throw busy(conn, code)
            if (code !in 200..299) throw LinkDownloadException("The server answered $code")
            // 200 to a Range request: the server starts over, so must we.
            if (code == 200 && have > 0) {
                out.delete()
                have = 0
            }
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: 0L
            // A different file behind the link now: what was kept would be spliced into it.
            if (have > 0 && total > 0 && total != was) { out.delete(); meta.delete(); again() }
            if (total > 0) meta.writeText(JSONObject().put("total", total).toString())
            val meter = Meter(have)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(out, true).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        meter.add(n.toLong())?.let { progress.report(meter.done, total, it) }
                    }
                }
            }
            if (total > 0 && out.length() < total) throw LinkDownloadException("The download stopped early — try again")
            progress.report(out.length(), out.length(), 0)
            meta.delete()
            Result(out, extOf(url, conn.contentType))
        } finally {
            conn.disconnect()
        }
    }

    /* -------------------------------------------------------------------- HLS */

    private suspend fun hls(url: String, headers: Map<String, String>, quality: String?, work: File, parallel: Int, progress: Progress): Result {
        var mediaUrl = url
        var text = fetchText(url, headers)
        if (!text.trimStart('﻿').trimStart().startsWith("#EXTM3U")) throw LinkExpiredException("The link no longer leads to the stream")
        if (isMasterPlaylist(text)) {
            val master = parseMaster(text, url)
            val pick = pickVariant(master.variants, heightOf(quality), leftOutHeights(com.torrentviewer.core.SettingsStore.value.value.leaveOut)) ?: throw LinkDownloadException("The playlist lists no streams")
            if (pick.audio != null) throw LinkDownloadException("This stream keeps its sound in a separate playlist — saving it isn't supported yet")
            mediaUrl = pick.uri
            text = fetchText(mediaUrl, headers)
        }
        val media = parseMedia(text, mediaUrl)
        if (!media.ended) throw LinkDownloadException("A live stream can't be saved")
        if (media.segments.isEmpty()) throw LinkDownloadException("The playlist has no video in it")
        media.segments.mapNotNull { it.key }.firstOrNull { it.method != "AES-128" }?.let {
            throw LinkDownloadException("This stream is protected (${it.method}) and can't be saved")
        }

        val out = File(work, "data.part")
        val state = File(work, "progress.json")
        // Where the last run stopped: segments written, and the file's length at that point.
        var next = 0
        var length = 0L
        // The same playlist — or, after the link was renewed (a pause of days, an expired token), one with the same
        // number of segments: the same video under a new address — carries on where it stopped.
        runCatching { JSONObject(state.readText()) }.getOrNull()?.takeIf { it.optString("media") == mediaUrl || it.optInt("count", -1) == media.segments.size }?.let {
            next = it.optInt("next")
            length = it.optLong("length")
        }
        val keys = HashMap<String, ByteArray>()
        RandomAccessFile(out, "rw").use { raf ->
            if (next == 0) {
                raf.setLength(0)
                media.init?.let { raf.write(fetchBytes(it, headers, null, null)) }
            } else {
                // Anything after the last whole segment was half a write.
                raf.setLength(length)
            }
            raf.seek(raf.length())
            val meter = Meter(raf.length())
            var i = next
            while (i < media.segments.size) {
                coroutineContext.ensureActive()
                val window = media.segments.subList(i, minOf(i + parallel, media.segments.size))
                val parts = coroutineScope {
                    window.map { seg -> async(Dispatchers.IO) { decrypt(seg, fetchBytes(seg.uri, headers, seg.length, seg.offset), headers, keys) } }.awaitAll()
                }
                withContext(Dispatchers.IO) { parts.forEach { raf.write(it) } }
                i += window.size
                state.writeText(JSONObject().put("media", mediaUrl).put("count", media.segments.size).put("next", i).put("length", raf.length()).toString())
                val estimate = raf.length() * media.segments.size / i
                meter.add(parts.sumOf { it.size.toLong() }, force = true)?.let { progress.report(raf.length(), estimate, it) }
            }
            progress.report(raf.length(), raf.length(), 0)
        }
        return Result(out, if (media.init != null) "mp4" else "ts")
    }

    private suspend fun decrypt(seg: HlsSegment, data: ByteArray, headers: Map<String, String>, keys: HashMap<String, ByteArray>): ByteArray {
        val key = seg.key ?: return data
        val uri = key.uri ?: throw LinkDownloadException("An encrypted segment names no key")
        val bytes = synchronized(keys) { keys[uri] } ?: fetchBytes(uri, headers, null, null).also { k -> synchronized(keys) { keys[uri] = k } }
        if (bytes.size != 16) throw LinkDownloadException("The stream's key isn't an AES-128 key")
        return Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(hlsIv(key, seg.sequence))) }
            .doFinal(data)
    }

    /* ------------------------------------------------------------------- HTTP */

    private fun open(url: String, headers: Map<String, String>, range: String?): HttpURLConnection {
        var target = url
        // HttpURLConnection won't follow a redirect from http to https or back; these hosts do that.
        repeat(5) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                // A part that stalls this long is dropped and asked for again from where it got to, not waited on.
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (headers.keys.none { it.equals("user-agent", true) }) setRequestProperty("User-Agent", UA)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                range?.let { setRequestProperty("Range", it) }
            }
            if (conn.responseCode in 300..399) {
                val to = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                target = URL(URL(target), to).toString()
            } else return conn
        }
        throw LinkDownloadException("Too many redirects")
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String = String(fetchBytes(url, headers, null, null), Charsets.UTF_8)

    /** The whole body, tried a few times: one dropped segment shouldn't end a two-hour film. */
    private suspend fun fetchBytes(url: String, headers: Map<String, String>, length: Long?, offset: Long?): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until TRIES) {
            coroutineContext.ensureActive()
            try {
                return withContext(Dispatchers.IO) {
                    val range = if (length != null) "bytes=${offset ?: 0}-${(offset ?: 0) + length - 1}" else null
                    val conn = open(url, headers, range)
                    try {
                        val code = conn.responseCode
                        if (code in EXPIRED) throw LinkExpiredException("The link has expired (${URL(url).host} answered $code)")
                        if (code !in 200..299) throw IOException("${URL(url).host} answered $code")
                        conn.inputStream.use { it.readBytes() }
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (e: LinkExpiredException) {
                // Asking again won't revive it.
                throw e
            } catch (e: IOException) {
                last = e
                delay(1000L * (attempt + 1))
            }
        }
        if (last is java.net.UnknownHostException || last is java.net.ConnectException) throw LinkExpiredException("Can't reach ${URL(url).host}")
        throw LinkDownloadException(last?.message ?: "A part of the stream wouldn't download")
    }

    private fun extOf(url: String, type: String?): String =
        Regex("\\.(mp4|mkv|webm|avi|mov|m4v|ts)(\\?|$)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
            ?: when {
                type?.contains("matroska") == true -> "mkv"
                type?.contains("webm") == true -> "webm"
                type?.contains("mp2t") == true -> "ts"
                else -> "mp4"
            }

    /** Bytes per second, told at most every second. */
    private class Meter(var done: Long) {
        private var since = System.currentTimeMillis()
        private var bytes = 0L

        fun add(n: Long, force: Boolean = false): Int? {
            done += n
            bytes += n
            val now = System.currentTimeMillis()
            val gone = now - since
            if (gone < 1000 && !force) return null
            val rate = if (gone > 0) (bytes * 1000 / gone).toInt() else 0
            if (gone >= 1000) {
                since = now
                bytes = 0
            }
            return rate
        }
    }
}

/**
 * A file of [total] bytes cut into parts for [connections] connections: about four parts a connection, so
 * the fast ones take more and nobody waits on one slow part at the end; none under 2 MB. Inclusive ranges.
 */
fun partsOf(total: Long, connections: Int): List<Pair<Long, Long>> {
    if (total <= 0) return emptyList()
    val size = maxOf(2L * 1024 * 1024, (total + connections * 4 - 1) / (connections * 4L))
    return (0 until total step size).map { it to minOf(it + size, total) - 1 }
}

package com.torrentviewer.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** What [download] made: the file in [work], and the extension it should be kept under. */
    data class Result(val file: File, val ext: String)

    /** [done] and [total] are bytes; [total] is an estimate for a playlist, from the segments so far. */
    fun interface Progress {
        fun report(done: Long, total: Long, speed: Int)
    }

    suspend fun download(url: String, headers: Map<String, String>, quality: String?, playlist: Boolean, work: File, connections: Int = 1, progress: Progress): Result {
        work.mkdirs()
        return if (playlist) // Whole segments are held in memory while they're fetched and decrypted: six at once keeps that to a few tens of MB.
        hls(url, headers, quality, work, connections.coerceIn(PARALLEL, 6), progress)
        else multi(url, headers, work, connections, progress) ?: single(url, headers, work, progress)
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
        val plan: JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: run {
            // Can this server do ranges, and how big is it? One byte asked for says both.
            val probe = withContext(Dispatchers.IO) {
                val conn = open(url, headers, "bytes=0-0")
                try {
                    val code = conn.responseCode
                    if (code in EXPIRED) throw LinkExpiredException("The link has expired (the server answered $code)")
                    val size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    if (code == 206 && size != null) size to extOf(url, conn.contentType) else null
                } finally {
                    conn.disconnect()
                }
            } ?: return null
            val (size, kind) = probe
            if (size < MULTI_MIN) return null
            // A single download's leftovers are of no use to this one.
            out.delete()
            withContext(Dispatchers.IO) { RandomAccessFile(out, "rw").use { it.setLength(size) } }
            val parts = partsOf(size, connections)
            JSONObject().put("total", size).put("ext", kind).put("starts", org.json.JSONArray(parts.map { it.first }))
                .put("ends", org.json.JSONArray(parts.map { it.second })).put("done", org.json.JSONArray(parts.map { 0L }))
                .also { stateFile.writeText(it.toString()) }
        }
        val total = plan.getLong("total")
        val ext = plan.optString("ext", "mp4")
        val starts = plan.getJSONArray("starts").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val ends = plan.getJSONArray("ends").let { a -> LongArray(a.length()) { a.getLong(it) } }
        val done = plan.getJSONArray("done").let { a -> java.util.concurrent.atomic.AtomicLongArray(LongArray(a.length()) { a.getLong(it) }) }
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val meter = Meter((0 until done.length()).sumOf { done.get(it) })
        var lastSave = 0L
        fun save(force: Boolean = false) = synchronized(stateFile) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSave < 2000) return@synchronized
            lastSave = now
            plan.put("done", org.json.JSONArray((0 until done.length()).map { done.get(it) }))
            stateFile.writeText(plan.toString())
        }
        fun take(): Int? {
            while (true) {
                val i = next.getAndIncrement()
                if (i >= starts.size) return null
                if (starts[i] + done.get(i) <= ends[i]) return i
            }
        }
        try {
            coroutineScope {
                repeat(connections.coerceAtMost(starts.size)) {
                    launch(Dispatchers.IO) {
                        RandomAccessFile(out, "rw").use { raf ->
                            while (true) {
                                val i = take() ?: break
                                fetchPart(url, headers, i, starts[i], ends[i], done, raf) { n ->
                                    synchronized(meter) { meter.add(n) }?.let { rate -> progress.report(synchronized(meter) { meter.done }, total, rate) }
                                    save()
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            save(force = true)
        }
        if ((0 until done.length()).any { starts[it] + done.get(it) <= ends[it] }) throw LinkDownloadException("The download stopped early — try again")
        stateFile.delete()
        progress.report(total, total, 0)
        return Result(out, ext)
    }

    /** One part, from where it got to, tried again on a dropped connection or a busy server. */
    private suspend fun fetchPart(url: String, headers: Map<String, String>, i: Int, start: Long, end: Long, done: java.util.concurrent.atomic.AtomicLongArray, raf: RandomAccessFile, onBytes: (Long) -> Unit) {
        var failures = 0
        while (start + done.get(i) <= end) {
            coroutineContext.ensureActive()
            val from = start + done.get(i)
            try {
                val conn = open(url, headers, "bytes=$from-$end")
                try {
                    val code = conn.responseCode
                    if (code in EXPIRED) throw LinkExpiredException("The link has expired (the server answered $code)")
                    // Too many connections for this host: wait, and the part is asked for again.
                    if (code == 429 || code == 503) throw IOException("busy ($code)")
                    if (code != 206) throw LinkDownloadException("The server stopped sending parts ($code)")
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var at = from
                        while (at <= end) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - at + 1).toInt())
                            if (n < 0) break
                            raf.seek(at)
                            raf.write(buf, 0, n)
                            at += n
                            done.addAndGet(i, n.toLong())
                            onBytes(n.toLong())
                        }
                    }
                    failures = 0
                } finally {
                    conn.disconnect()
                }
            } catch (e: LinkDownloadException) {
                throw e
            } catch (e: IOException) {
                if (++failures > TRIES * 3) throw LinkDownloadException("A part kept failing: ${e.message}")
                delay(1000L * failures.coerceAtMost(8))
            }
        }
    }

    /* ---------------------------------------------------------------- one file */

    private suspend fun single(url: String, headers: Map<String, String>, work: File, progress: Progress): Result = withContext(Dispatchers.IO) {
        val out = File(work, "data.part")
        var have = if (out.exists()) out.length() else 0L
        val conn = open(url, headers, if (have > 0) "bytes=$have-" else null)
        try {
            val code = conn.responseCode
            if (code == 416) return@withContext Result(out, extOf(url, conn.contentType))
            if (code in EXPIRED) throw LinkExpiredException("The link has expired (the server answered $code)")
            if (code !in 200..299) throw LinkDownloadException("The server answered $code")
            // 200 to a Range request: the server starts over, so must we.
            if (code == 200 && have > 0) {
                out.delete()
                have = 0
            }
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { it > 0 }?.plus(have) ?: 0L
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
        runCatching { JSONObject(state.readText()) }.getOrNull()?.takeIf { it.optString("media") == mediaUrl }?.let {
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
                state.writeText(JSONObject().put("media", mediaUrl).put("next", i).put("length", raf.length()).toString())
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
                readTimeout = 60_000
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

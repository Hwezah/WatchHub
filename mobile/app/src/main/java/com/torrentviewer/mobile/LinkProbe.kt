package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/*
 * Many plugin links say nothing of what they are — "Moviesmod Driveseed [326 MB]": no resolution, no format — so a
 * pick couldn't tell a light 720p MKV from anything else, and took a labelled 1080p of 11 GB over it. The file's own
 * name says it all ("Show.S01E02.720p.x265.mkv"), and the link's server gives that name when asked. So before a pick,
 * the unlabelled links are asked — one byte each, a few at a time — and read as their file names say.
 */

/** A link whose resolution or format the pick can't tell: worth asking its server for the file's name. */
fun unlabelled(hit: Hit): Boolean = hit.direct && !isPlaylist(hit) && !isDash(hit) && (hit.quality == null || traitsOf(hit).format == UNKNOWN)

private val DISPOSITION_STAR = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)")
private val DISPOSITION = Regex("filename\\s*=\\s*\"?([^\";]+)\"?")

/** The file's name: from Content-Disposition (either form), else the last part of the address it ended at. */
fun fileNameOf(disposition: String?, finalUrl: String): String? {
    val named = disposition?.let { d ->
        DISPOSITION_STAR.find(d)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?: DISPOSITION.find(d)?.groupValues?.get(1)
    }?.trim()?.takeIf { it.isNotEmpty() }
    return named ?: finalUrl.substringBefore('?').substringBefore('#').substringAfterLast('/')
        .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.takeIf { it.contains('.') && it.length > 4 }
}

/**
 * [hit] read again with its file's [fileName] and [contentType]: the resolution it lacked, and the name (with the
 * format the type gives, when the name has none) as its release, which the codec, format and subtitle traits read.
 * Its own name, id and link are untouched: it is the same row, better described.
 */
fun described(hit: Hit, fileName: String?, contentType: String?, length: Long = 0): Hit {
    val ext = when {
        contentType == null -> ""
        contentType.contains("matroska", true) -> ".mkv"
        contentType.contains("mp4", true) -> ".mp4"
        contentType.contains("webm", true) -> ".webm"
        else -> ""
    }
    val name = fileName.orEmpty()
    val hasFormat = Regex("(?i)\\.(mkv|mp4|avi|webm|m4v|mov|ts)$").containsMatchIn(name)
    val release = (name + if (hasFormat) "" else " $ext").trim()
    // A playlist after all: an HLS stream, never taken for a file.
    if (contentType?.contains("mpegurl", true) == true) return hit.copy(mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8)
    if (release.isEmpty() && length <= 0) return hit
    return hit.copy(
        quality = hit.quality ?: quality(name),
        release = if (release.isEmpty()) hit.release else "${hit.release} $release",
        size = if (hit.size > 0) hit.size else maxOf(0, length),
    )
}

private const val MB = 1024L * 1024

/**
 * A link still unlabelled after its server was asked (it gave no name), read by its size instead — the owner's
 * rule is the lightest 720p MKV, and these bare links from file hosts (Driveseed, HubCloud, Pixeldrain) are the
 * light MKVs it means: left unread, an 11 GB labelled 1080p was taken over a 326 MB file. A guess, by what
 * such encodes weigh: an episode of 200 MB–1 GB is 720p, under that 480p, up to 4 GB 1080p (a film: 2.5 times
 * each). A file that says nothing of its format is taken for MKV, which these hosts' files are. Above the top
 * size, or with no size, nothing is guessed.
 */
fun guessed(hit: Hit): Hit {
    if (!unlabelled(hit) || hit.size <= 0) return hit
    val scale = if (hit.kind == "movie") 2.5 else 1.0
    val quality = hit.quality ?: when {
        hit.size < 200 * MB * scale -> "480p"
        hit.size <= 1024 * MB * scale -> "720p"
        hit.size <= 4096 * MB * scale -> "1080p"
        else -> return hit
    }
    return hit.copy(quality = quality, release = if (traitsOf(hit).format == UNKNOWN) "${hit.release} .mkv" else hit.release)
}

object LinkProbe {
    /** Links asked at once, and how long one may take: a dead host mustn't hold a season up. */
    private const val EACH_MS = 10_000L
    /**
     * The most links asked for one episode. Every unlabelled one, in practice: an MKV whose name says nothing (a
     * Pixeldrain or Driveseed link) was left out when only the lightest few were asked, and a 1080p taken over it.
     */
    private const val MOST = 120
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** Links checked per episode with data saver on mobile data. */
    private const val SAVING_MOST = 40

    /**
     * Checks under way at once, across every episode being scanned: three episodes each asking a few dozen links at
     * once crowded the phone's connection, checks timed out, and a 720p MKV that only failed to answer in time was
     * taken for one that needs its headers — and passed over for a 1080p.
     */
    private val slots = Semaphore(8)

    /** Links being checked now: a second look at the same list (results arriving) doesn't ask them twice. */
    private val asking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Links that gave no answer, and how often: asked again, but not for ever (each try costs data). */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val TRIES = 3

    /** What each link was found to be, kept for the session: a rescan or a second pick doesn't ask again. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, Hit>()

    /**
     * [hits] with the unlabelled links among them described by their file names — the [MOST] lightest of them (unknown
     * sizes last), since a pick wants the lightest. Never fails: a link that doesn't answer stays as it was.
     */
    suspend fun describe(hits: List<Hit>): List<Hit> {
        // The preferred resolution's first, then the lightest: those are the ones a pick might take.
        val wanted = downloadPrefs().quality
        // Data saver on mobile data: only the preferred resolution's (and unlabelled) links, and fewer of them.
        val saving = Downloads.saving()
        val ask = hits.filter { (unlabelled(it) || withHeaders(it)) && !known.containsKey(it.uri) && it.uri !in asking && (failed[it.uri] ?: 0) < TRIES }
            .filter { !saving || it.quality == null || it.quality == wanted }
            .sortedWith(compareBy<Hit> { if (it.quality == null || it.quality == wanted) 0 else 1 }.thenBy { if (it.size > 0) 0 else 1 }.thenBy { it.size })
            .take(if (saving) SAVING_MOST else MOST)
        asking.addAll(ask.map { it.uri })
        if (ask.isNotEmpty()) try {
            coroutineScope {
                ask.map { h ->
                    async {
                        slots.withPermit {
                            // A link that comes with headers (a Referer) is first tried without them: most don't need
                            // them, and one that doesn't can be handed to another app, which can't send them. Each try
                            // has its own time.
                            val bare = if (withHeaders(h)) withTimeoutOrNull(EACH_MS) { runCatching { probe(h.copy(headers = emptyMap())) }.getOrNull() } else null
                            val found = bare ?: withTimeoutOrNull(EACH_MS) { runCatching { probe(h) }.getOrNull() }
                            // Only an answer is kept: a link that failed or was slow is asked again on the next look,
                            // not written off for the session.
                            if (found != null) known[h.uri] = found else failed.merge(h.uri, 1, Int::plus)
                        }
                    }
                }.awaitAll()
            }
        } finally {
            // Also when cancelled: an unfinished check is asked again next time, not skipped for ever.
            asking.removeAll(ask.map { it.uri }.toSet())
        }
        // What its server said; failing that, what its size says ([guessed]).
        return hits.map { guessed(known[it.uri]?.takeIf { k -> k.name == it.name } ?: it) }
    }

    /** A file link that comes with headers: worth checking whether it works without them. */
    private fun withHeaders(hit: Hit) = hit.direct && !isPlaylist(hit) && !isDash(hit) && hit.headers.isNotEmpty()

    /**
     * One byte asked for, redirects followed by hand (these hosts hop between http and https): the headers say the rest.
     * Null when no file came back (an error, a web page, no answer).
     */
    private suspend fun probe(hit: Hit): Hit? = withContext(Dispatchers.IO) {
        var target = hit.uri
        repeat(6) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                if (hit.headers.keys.none { it.equals("user-agent", true) }) setRequestProperty("User-Agent", UA)
                hit.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = URL(URL(target), conn.getHeaderField("Location") ?: return@withContext null).toString()
                } else {
                    if (code !in 200..299) return@withContext null
                    val type = conn.contentType
                    // A web page, not the file: nothing to learn.
                    if (type?.startsWith("text/", true) == true) return@withContext null
                    // The whole size: from the range answer, or a server that sent it all from the start.
                    val length = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: conn.contentLengthLong.takeIf { code == 200 && it > 1 } ?: 0L
                    return@withContext described(hit, fileNameOf(conn.getHeaderField("Content-Disposition"), target), type, length)
                }
            } finally {
                conn.disconnect()
            }
        }
        null
    }
}

package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a page offers: its title, picture, the video heights there are, and its length (seconds). */
data class MediaInfo(val title: String, val thumb: String?, val heights: List<Int>, val duration: Int)

/**
 * One way to save something, as Snaptube lists them: a video at a height ("v:720", "v:0" for the best), or the
 * sound in a format ("a:mp3", "a:m4a", "a:opus", "a:flac", "a:wav").
 */
data class SaveFormat(val code: String, val label: String, val hint: String) {
    val audio get() = code.startsWith("a:")
}

/** The sound formats offered everywhere a video can be saved; FFmpeg converts to each. */
val AUDIO_FORMATS = listOf(
    SaveFormat("a:mp3", "MP3", "Plays everywhere · best quality"),
    SaveFormat("a:m4a", "M4A (AAC)", "Small, no re-encoding where the site has it"),
    SaveFormat("a:opus", "Opus", "Smallest for the same sound"),
    SaveFormat("a:flac", "FLAC", "Lossless copy of the sound"),
    SaveFormat("a:wav", "WAV", "Uncompressed, large"),
)

/**
 * Video and audio from YouTube and ~1,800 other sites through yt-dlp (bundled with Python), and FFmpeg to join
 * picture and sound and to convert to MP3 / M4A / Opus / FLAC / WAV — ported from the Grabber app's Media (same
 * owner). The first use unpacks both (a few seconds).
 */
object Media {
    @Volatile private var ready = false

    /** Unpacks yt-dlp and FFmpeg the first time. Blocking: call off the main thread. */
    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
        // Sites change often: yt-dlp kept on its nightly build, checked once a day.
        val sp = context.getSharedPreferences("media", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("updated", 0) > 24L * 3600 * 1000) {
            thread(name = "yt-dlp-update") {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                    sp.edit().putLong("updated", System.currentTimeMillis()).apply()
                }
            }
        }
    }

    private val SITES = listOf(
        "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "x.com", "twitter.com",
        "vimeo.com", "dailymotion.com", "reddit.com", "redd.it", "soundcloud.com", "twitch.tv", "bilibili.com", "pinterest.", "pin.it",
        "threads.net", "snapchat.com", "linkedin.com", "tumblr.com", "vk.com", "likee.video", "kwai.com", "bsky.app", "rumble.com", "streamable.com",
    )

    /** Pages yt-dlp knows well: "Grab this page" is offered on them. */
    fun looksLikeSite(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return SITES.any { host == it || host.endsWith(".$it") || host.contains(it) }
    }

    /** What [url] offers, read by yt-dlp. Blocking. */
    fun info(context: Context, url: String, headers: Map<String, String> = emptyMap()): MediaInfo {
        ensure(context)
        val req = YoutubeDLRequest(url).addOption("--no-playlist").addOption("--no-update")
        headers.forEach { (k, v) -> req.addOption("--add-header", "$k:$v") }
        val v = try {
            YoutubeDL.getInstance().getInfo(req)
        } catch (e: Exception) {
            throw IllegalStateException(cleanError(e.message), e)
        }
        val heights = v.formats.orEmpty().filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }.map { it.height }.distinct().sortedDescending()
        return MediaInfo(v.title ?: v.fulltitle ?: "video", v.thumbnail, heights, v.duration)
    }

    /**
     * Saves [url] in [format] ([SaveFormat.code]) into [work] — which keeps partial files, so a pause resumes — and
     * returns the finished file there. [onProgress]: percent (0–100) and yt-dlp's line.
     */
    suspend fun run(context: Context, url: String, format: String, headers: Map<String, String>, name: String, work: File, onProgress: (Float, String) -> Unit): File {
        ensure(context)
        work.mkdirs()
        val base = name.replace(Regex("""[\\/:*?"<>|%]+"""), "_").trim().take(120).ifBlank { "video" }
        val req = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--no-update")
            .addOption("-N", 8)
            .addOption("-o", File(work, "$base.%(ext)s").absolutePath)
        headers.forEach { (k, v) -> req.addOption("--add-header", "$k:$v") }
        if (format.startsWith("a:")) {
            req.addOption("-f", "ba/b").addOption("-x").addOption("--audio-format", format.removePrefix("a:")).addOption("--audio-quality", "0")
        } else {
            val h = format.removePrefix("v:").toIntOrNull()?.takeIf { it > 0 }
            val sel = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
            // At the chosen size, H.264 + AAC first: the codecs every phone plays.
            req.addOption("-f", sel).addOption("--merge-output-format", "mp4")
                .addOption("-S", (if (h != null) "res:$h," else "") + "vcodec:h264,acodec:aac,ext:mp4:m4a")
        }
        val pid = "grab-" + Integer.toHexString((url + format).hashCode())
        suspendCancellableCoroutine<Unit> { cont ->
            val t = thread(name = pid) {
                try {
                    val r = YoutubeDL.getInstance().execute(req, pid) { pct, _, line -> onProgress(pct, line) }
                    if (r.exitCode != 0) throw IllegalStateException(r.err.lineSequence().lastOrNull { it.isNotBlank() } ?: "yt-dlp failed")
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(java.io.IOException(cleanError(e.message), e))
                }
            }
            cont.invokeOnCancellation {
                runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
                t.interrupt()
            }
        }
        return work.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !Regex("""\.f\d+\.""").containsMatchIn(it.name) }
            .maxByOrNull { it.length() } ?: error("yt-dlp finished but no file was made")
    }

    /** yt-dlp's last ERROR line, without warnings, its [extractor] tag or the Python cause. */
    fun cleanError(m: String?): String {
        val lines = m.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("WARNING") }.toList()
        val line = lines.lastOrNull { it.contains("ERROR") } ?: lines.lastOrNull() ?: "Download failed"
        return line.substringAfter("ERROR: ").replace(Regex("""^\[[^\]]+\]\s*"""), "").replace(Regex("""\s*\(caused by.*$"""), "").take(160)
    }

    /* ------------------------------------------------------------ the Grabber app */

    /** The owner's Grabber app, when it's on this phone. */
    const val GRABBER = "com.hwezah.grabber"

    fun hasGrabber(context: Context): Boolean = runCatching { context.packageManager.getLaunchIntentForPackage(GRABBER) != null }.getOrDefault(false)

    /** [url] handed to Grabber's download sheet (its share target), which asks for the format itself. */
    fun sendToGrabber(context: Context, url: String, title: String?): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(GRABBER).putExtra(Intent.EXTRA_TEXT, url)
                .apply { title?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}

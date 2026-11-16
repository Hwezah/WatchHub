package com.torrentviewer.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * One way to play a trailer, best first: YouTube's own HLS stream (picture and sound together), a picture-only stream
 * with its sound beside it ([audio]), or a single file with both (YouTube's 360p).
 */
data class TrailerStream(val video: String, val audio: String? = null, val hls: Boolean = false)

/**
 * Trailers played in the app's own player, as Nuvio does, rather than YouTube's embed page (which YouTube often
 * stalls or refuses inside an app): NewPipeExtractor — the library NewPipe and CloudStream use — reads the video's
 * streams from YouTube on the phone, no account and no server of ours.
 */
object Trailers {
    /** The tallest picture asked for: a phone's screen doesn't need more, and a trailer at 1080p is twice the data. */
    private const val MOST_HEIGHT = 720

    /**
     * NewPipe set up once, with an HTTP client of its own. CloudStream's app does the same at launch; plugins' YouTube
     * links (CloudStream's YoutubeExtractor) need it too.
     */
    fun init() {
        runCatching { if (NewPipe.getDownloader() == null) NewPipe.init(NewPipeHttp) }
    }

    /** [youtubeId]'s streams, best first; empty when YouTube gave none (or refused). Off the main thread. */
    suspend fun streams(youtubeId: String): List<TrailerStream> = withContext(Dispatchers.IO) {
        init()
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$youtubeId")
        val out = ArrayList<TrailerStream>()
        info.hlsUrl?.takeIf { it.isNotBlank() }?.let { out += TrailerStream(it, hls = true) }
        val pictures = info.videoOnlyStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..MOST_HEIGHT }
        // H.264 (MP4) plays on every phone's hardware; VP9 (WebM) on most.
        val picture = pictures.filter { it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height } ?: pictures.maxByOrNull { it.height }
        val sounds = info.audioStreams.orEmpty().filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // The film's own soundtrack, not a dubbed one YouTube added.
        val original = sounds.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { sounds }
        val sound = original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate } ?: original.maxByOrNull { it.averageBitrate }
        if (picture != null && sound != null) out += TrailerStream(picture.content, sound.content)
        info.videoStreams.orEmpty().filter { it.isUrl && !it.isVideoOnly && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.height }?.let { out += TrailerStream(it.content) }
        out
    }

    /**
     * The User-Agent a stream's link expects: YouTube hands out links for the client NewPipe asked as ("c=ANDROID",
     * "c=IOS" in the link), and serves them only to that client.
     */
    fun userAgent(url: String): String = when {
        "c=IOS" in url || "/c/IOS/" in url -> "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
        "c=ANDROID" in url || "/c/ANDROID/" in url -> "com.google.android.youtube/20.03.36 (Linux; U; Android 14) gzip"
        else -> BROWSER
    }

    private const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** NewPipe's HTTP, through OkHttp as CloudStream's app does it (ported from its DownloaderTestImpl, GPL-3.0). */
    private object NewPipeHttp : Downloader() {
        private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.let { it.toRequestBody(null, 0, it.size) }
            val builder = okhttp3.Request.Builder().method(request.httpMethod(), body).url(request.url()).addHeader("User-Agent", BROWSER)
            for ((name, values) in request.headers()) {
                if (values.size > 1) {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                } else if (values.size == 1) builder.header(name, values[0])
            }
            val response = client.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val text = response.body?.string().orEmpty()
            return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
        }
    }
}

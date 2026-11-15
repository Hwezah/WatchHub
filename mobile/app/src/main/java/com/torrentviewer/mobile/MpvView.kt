package com.torrentviewer.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.torrentviewer.core.Scale
import com.torrentviewer.core.Stats
import com.torrentviewer.core.SubtitleStyle
import com.torrentviewer.core.Track
import com.torrentviewer.core.Tracks
import com.torrentviewer.core.Video
import dev.jdtech.mpv.MPVLib
import java.io.File

private const val TAG = "MpvView"

/**
 * A video surface played by mpv (libmpv, as in mpvKt and Findroid): FFmpeg's decoders for nearly every format —
 * HEVC 10-bit and HDR, AV1, VP9, MPEG-2, DivX, WMV; DTS, TrueHD, E-AC3 — the phone's hardware decoder where it has
 * one, software where it hasn't, and libass for styled (ASS) subtitles, PGS and VobSub. The phone's default engine.
 *
 * mpv runs on its own thread and reports by events: they are handed to the main thread here, where the player
 * screen expects its callbacks. One mpv per view, made when the view is and destroyed when it leaves the window.
 */
class MpvView(context: Context) : Video(context), MPVLib.EventObserver, SurfaceHolder.Callback {
    private val surface = SurfaceView(context)
    private val main = Handler(Looper.getMainLooper())
    private var mpv: MPVLib? = null

    private var url: String? = null
    private var startMs = 0L
    private var cacheMs = 3000
    private var subtitleFiles: List<String> = emptyList()
    private val attachedSubs = mutableSetOf<String>()

    /** The last of these, read by [time] and [isPaused] without asking mpv each time. */
    @Volatile private var positionMs = 0L
    @Volatile private var paused = false
    @Volatile private var aheadSecs = 0.0
    /** The file has loaded: its first [onLoaded] has been said (again after each [reload]). */
    private var loaded = false
    /** mpv said an error just before a file ended: the reason to give if it never loaded. */
    @Volatile private var lastError: String? = null
    /** The file played through to its end (rather than failing): the reason for [onEnded]. */
    @Volatile private var eof = false
    /** [open] asked for a file and mpv hasn't started it yet: an end now is the previous file's. */
    @Volatile private var startPending = false
    private var surfaceReady = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val onAudioFocus = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) setPaused(true)
    }

    override var subtitleStyle: SubtitleStyle? = null
        set(value) {
            field = value
            applyStyle()
        }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        keepScreenOn = true
        surface.holder.addCallback(this)
        mpv = runCatching { create(context) }.onFailure { Log.e(TAG, "mpv didn't start", it) }.getOrNull()
    }

    private fun create(context: Context): MPVLib {
        val m = MPVLib.create(context)
        val dir = File(context.filesDir, "mpv").apply { mkdirs() }
        m.setOptionString("config", "no")
        m.setOptionString("config-dir", dir.path)
        // Drawn with the GPU on the view's surface; decoded by the phone's hardware where it can (MediaCodec, copied back
        // so mpv's own scaling and subtitles apply), by FFmpeg in software where it can't.
        m.setOptionString("vo", "gpu")
        m.setOptionString("gpu-context", "android")
        m.setOptionString("opengl-es", "yes")
        m.setOptionString("hwdec", "mediacodec-copy")
        m.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        m.setOptionString("ao", "audiotrack,opensles")
        // Stays on the last frame at the end, so the end is an event (eof-reached) rather than an empty player.
        m.setOptionString("keep-open", "always")
        m.setOptionString("force-window", "no")
        m.setOptionString("idle", "yes")
        m.setOptionString("input-default-bindings", "no")
        m.setOptionString("save-position-on-quit", "no")
        m.setOptionString("cache", "yes")
        m.setOptionString("demuxer-max-bytes", "${96 * 1024 * 1024}")
        m.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        m.setOptionString("network-timeout", "30")
        // Subtitles: a font of the phone's own (libass has none), drawn inside the picture's frame at any scale.
        fontsDir(context)?.let { m.setOptionString("sub-fonts-dir", it.path); m.setOptionString("osd-fonts-dir", it.path) }
        m.setOptionString("sub-font", "Roboto")
        m.setOptionString("sub-scale-with-window", "yes")
        m.setOptionString("sub-use-margins", "no")
        m.setOptionString("sub-auto", "no")
        m.init()
        m.addObserver(this)
        m.addLogObserver(object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) {
                // Errors and fatal ones only: the reason a file wouldn't open, said if it never does.
                if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
                    val line = "$prefix: ${text.trim()}"
                    lastError = line
                    Log.w(TAG, line)
                }
            }
        })
        listOf(
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "demuxer-cache-duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPVLib.MpvFormat.MPV_FORMAT_NONE,
            "sid" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
        ).forEach { (name, format) -> m.observeProperty(name, format) }
        return m
    }

    /**
     * A folder holding one font of the phone's, for libass: given the whole of /system/fonts it would read every
     * file (hundreds of MB, the CJK ones) into memory. Copied once.
     */
    private fun fontsDir(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "mpv/fonts").apply { mkdirs() }
        if (dir.listFiles().isNullOrEmpty()) {
            val system = File("/system/fonts")
            val pick = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "Roboto.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf")
                .map { File(system, it) }.firstOrNull { it.exists() }
                ?: system.listFiles()?.firstOrNull { it.name.endsWith(".ttf", true) && it.length() < 2_000_000 }
            pick?.copyTo(File(dir, pick.name), overwrite = true)
        }
        dir
    }.getOrNull()

    /* ------------------------------------------------------------ surface */

    override fun surfaceCreated(holder: SurfaceHolder) {
        val m = mpv ?: return
        m.attachSurface(holder.surface)
        m.setOptionString("force-window", "yes")
        m.setPropertyString("vo", "gpu")
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv?.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val m = mpv ?: return
        // The picture goes (picture-in-picture, the screen off); the sound and position carry on.
        m.setPropertyString("vo", "null")
        m.setOptionString("force-window", "no")
        m.detachSurface()
        surfaceReady = false
    }

    /* ------------------------------------------------------------ Video */

    override fun open(url: String, startAt: Long, cacheMs: Int, subtitles: List<String>) {
        val m = mpv
        if (m == null) {
            main.post { onFailed("This phone can't start the mpv player") }
            return
        }
        this.url = url
        this.startMs = startAt
        this.cacheMs = cacheMs
        subtitleFiles = subtitles
        attachedSubs.clear()
        loaded = false
        eof = false
        lastError = null
        // The file being replaced ends first, and its end isn't this one's failure.
        startPending = true
        applyHeaders(m)
        applyStyle()
        m.setOptionString("cache-secs", "${maxOf(10, cacheMs / 1000 * 5)}")
        m.setOptionString("start", if (startAt > 0) "${startAt / 1000.0}" else "none")
        // The type when the link doesn't say it (an HLS playlist without .m3u8).
        m.setOptionString("demuxer-lavf-format", if (mime?.contains("mpegurl", true) == true) "hls" else "")
        m.command(arrayOf("loadfile", url, "replace"))
        m.setPropertyBoolean("pause", false)
        takeAudioFocus()
    }

    override fun reload(ms: Long) {
        val u = url ?: return
        open(u, ms, cacheMs, subtitleFiles)
    }

    override val time: Long get() = positionMs
    override val aheadSeconds: Double get() = aheadSecs

    override fun seekTo(ms: Long) {
        mpv?.command(arrayOf("seek", "${ms / 1000.0}", "absolute"))
        positionMs = ms
    }

    override fun setPaused(value: Boolean) {
        mpv?.setPropertyBoolean("pause", value)
        paused = value
        if (!value) takeAudioFocus()
    }

    override val isPaused: Boolean get() = paused

    override fun setRate(value: Float) {
        mpv?.setPropertyDouble("speed", value.toDouble())
    }

    override fun setSubtitleTrack(id: Int?) {
        mpv?.setPropertyString("sid", id?.toString() ?: "no")
    }

    override fun setAudioTrack(id: Int) {
        mpv?.setPropertyString("aid", id.toString())
    }

    override fun setScale(type: Scale) {
        val m = mpv ?: return
        // Each from the plain picture: the others' settings undone first.
        m.setPropertyString("keepaspect", if (type == Scale.FILL) "no" else "yes")
        m.setPropertyDouble("panscan", if (type == Scale.FIT_SCREEN) 1.0 else 0.0)
        m.setPropertyString("video-unscaled", if (type == Scale.ORIGINAL) "yes" else "no")
        m.setPropertyString(
            "video-aspect-override",
            when (type) {
                Scale.RATIO_16_9 -> "16:9"
                Scale.RATIO_4_3 -> "4:3"
                else -> "no"
            },
        )
    }

    override fun addSubtitleFiles(paths: List<String>, select: Boolean) {
        val m = mpv ?: return
        subtitleFiles = (subtitleFiles + paths).distinct()
        if (!loaded) return
        paths.filter { it !in attachedSubs }.forEachIndexed { i, path ->
            attachedSubs += path
            val last = select && i == paths.lastIndex
            m.command(arrayOf("sub-add", path, if (last) "select" else "auto"))
        }
    }

    override fun setSubtitleDelay(ms: Long): Boolean {
        mpv?.setPropertyDouble("sub-delay", ms / 1000.0)
        return true
    }

    override fun stats(): Stats? {
        val m = mpv ?: return null
        return runCatching {
            val hw = m.getPropertyString("hwdec-current")?.takeIf { it.isNotEmpty() && it != "no" }
            Stats(
                videoCodec = m.getPropertyString("video-codec")?.substringBefore(' ')?.uppercase(),
                width = m.getPropertyInt("video-params/w") ?: 0,
                height = m.getPropertyInt("video-params/h") ?: 0,
                fps = m.getPropertyDouble("container-fps") ?: 0.0,
                audioCodec = m.getPropertyString("audio-codec-name")?.uppercase(),
                channels = m.getPropertyInt("audio-params/channel-count") ?: 0,
                sampleRate = m.getPropertyInt("audio-params/samplerate") ?: 0,
                language = m.getPropertyString("current-tracks/audio/lang"),
                inputKbps = (m.getPropertyDouble("cache-speed") ?: 0.0) * 8 / 1000,
                contentKbps = ((m.getPropertyInt("video-bitrate") ?: 0) + (m.getPropertyInt("audio-bitrate") ?: 0)) / 1000.0,
                displayed = m.getPropertyInt("estimated-frame-number") ?: 0,
                lostFrames = (m.getPropertyInt("frame-drop-count") ?: 0) + (m.getPropertyInt("decoder-frame-drop-count") ?: 0),
                lostBuffers = 0,
                cacheMs = ((m.getPropertyDouble("demuxer-cache-duration") ?: 0.0) * 1000).toInt(),
                engine = "mpv · " + if (hw != null) "hardware ($hw)" else "software",
            )
        }.getOrNull()
    }

    /* ------------------------------------------------------------ events (mpv's thread) */

    override fun eventProperty(property: String) {
        if (property == "track-list") main.post { tracksChanged() }
    }

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                positionMs = (value * 1000).toLong()
                main.post { onTime(positionMs) }
            }
            "duration" -> if (value > 0) main.post { announce((value * 1000).toLong()) }
            "demuxer-cache-duration" -> aheadSecs = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                paused = value
                main.post { onPlaying(!value) }
            }
            "paused-for-cache", "seeking" -> main.post { onBuffering(value) }
            "eof-reached" -> if (value) {
                eof = true
                main.post { onEnded() }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sid") main.post { tracksChanged() }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> startPending = false
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> main.post {
                // Its length, from the file itself (0 for a stream that doesn't say): it has loaded.
                announce(((mpv?.getPropertyDouble("duration") ?: 0.0) * 1000).toLong())
                // Subtitles found before the file was open go on now: mpv takes them only once it has one.
                val pending = subtitleFiles.filter { it !in attachedSubs }
                if (pending.isNotEmpty()) addSubtitleFiles(pending, select = false)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post { onBuffering(false) }
            // Ended without ever loading: it couldn't be opened. Said once, with mpv's own reason when it gave one.
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> if (!startPending && !loaded && !eof) main.post {
                if (!loaded) onFailed(lastError?.let { "The video wouldn't open ($it)" } ?: "The video wouldn't open")
            }
        }
    }

    /** The length is known: the file has loaded. Said once per [open]. */
    private fun announce(durationMs: Long) {
        if (loaded) return
        loaded = true
        onLoaded(durationMs)
        onBuffering(false)
        tracksChanged()
    }

    /** mpv's tracks as the player screen's: audio and subtitles, each named by language, title and codec. */
    private fun tracksChanged() {
        val m = mpv ?: return
        val count = m.getPropertyInt("track-list/count") ?: return
        val audio = mutableListOf<Track>()
        val subs = mutableListOf<Track>()
        var audioId = -1
        var defaultAudio = -1
        var spuId = -1
        for (i in 0 until count) {
            val type = m.getPropertyString("track-list/$i/type") ?: continue
            val id = m.getPropertyInt("track-list/$i/id") ?: continue
            val lang = m.getPropertyString("track-list/$i/lang")
            val title = m.getPropertyString("track-list/$i/title")
            val codec = m.getPropertyString("track-list/$i/codec")
            val selected = m.getPropertyBoolean("track-list/$i/selected") == true
            val external = m.getPropertyBoolean("track-list/$i/external") == true
            val name = listOfNotNull(
                lang?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it },
                title?.takeIf { it.isNotBlank() && it != lang },
                (m.getPropertyInt("track-list/$i/demux-channel-count"))?.takeIf { type == "audio" && it > 0 }?.let { ch -> if (ch == 6) "5.1" else if (ch == 8) "7.1" else "${ch}ch" },
                codec?.uppercase(),
                "file".takeIf { external },
            ).joinToString(" · ").ifEmpty { "Track $id" }
            when (type) {
                "audio" -> {
                    audio += Track(id, name)
                    if (selected) audioId = id
                    if (defaultAudio < 0 && m.getPropertyBoolean("track-list/$i/default") == true) defaultAudio = id
                }
                "sub" -> {
                    subs += Track(id, name)
                    if (selected) spuId = id
                }
            }
        }
        if (defaultAudio < 0) defaultAudio = audioId
        onTracks(Tracks(subs, audio, audioId, defaultAudio, spuId))
    }

    /* ------------------------------------------------------------ settings */

    /** A link's headers (Referer, User-Agent, a cookie) on every request mpv makes for it. */
    private fun applyHeaders(m: MPVLib) {
        val agent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        m.setOptionString("user-agent", agent ?: "Mozilla/5.0 (Linux; Android) WatchHub")
        m.setPropertyString("http-header-fields", "")
        headers.filterKeys { !it.equals("user-agent", true) }.forEach { (k, v) ->
            m.command(arrayOf("change-list", "http-header-fields", "append", "$k: $v"))
        }
    }

    /** The app's subtitle look (Settings → Subtitles) on plain subtitles; styled (ASS) ones keep their own. */
    private fun applyStyle() = runCatching {
        val m = mpv ?: return@runCatching
        val s = subtitleStyle ?: SubtitleStyle()
        m.setPropertyDouble("sub-scale", when (s.size) { "small" -> 0.8; "large" -> 1.25; "huge" -> 1.6; else -> 1.0 })
        m.setPropertyString("sub-color", if (s.yellow) "#FFFFEB3B" else "#FFFFFFFF")
        if (s.box) {
            m.setPropertyString("sub-border-style", "background-box")
            m.setPropertyString("sub-back-color", "#B3000000")
        } else {
            m.setPropertyString("sub-border-style", "outline-and-shadow")
            m.setPropertyDouble("sub-border-size", 3.0)
            m.setPropertyString("sub-border-color", "#FF000000")
        }
    }

    private fun takeAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(onAudioFocus).build().also { focusRequest = it }
            audioManager.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(onAudioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun dropAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audioManager.abandonAudioFocus(onAudioFocus)
    }

    override fun onDetachedFromWindow() {
        dropAudioFocus()
        main.removeCallbacksAndMessages(null)
        mpv?.let { m ->
            runCatching { m.removeObserver(this) }
            runCatching { m.command(arrayOf("stop")) }
            if (surfaceReady) runCatching { m.detachSurface() }
            runCatching { m.destroy() }
        }
        mpv = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** libmpv runs on Android 8 and later; older phones play with Media3. */
        val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}

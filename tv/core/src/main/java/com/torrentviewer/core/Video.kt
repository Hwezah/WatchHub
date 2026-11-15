package com.torrentviewer.core

import android.content.Context
import android.widget.FrameLayout

/** One selectable stream inside the media. `id` is libVLC's, not an index. */
data class Track(val id: Int, val name: String)

data class Tracks(val subtitles: List<Track>, val audio: List<Track>, val audioId: Int, val defaultAudio: Int, /** What libVLC is showing now; -1 is off. */ val spuId: Int = -1)

data class Stats(
    val videoCodec: String?,
    val width: Int,
    val height: Int,
    val fps: Double,
    val audioCodec: String?,
    val channels: Int,
    val sampleRate: Int,
    val language: String?,
    val inputKbps: Double,
    val contentKbps: Double,
    val displayed: Int,
    val lostFrames: Int,
    val lostBuffers: Int,
    val cacheMs: Int,
    /** Which backend, and for Media3 the decoders it picked. */
    val engine: String = "VLC",
)

/**
 * How the picture fills the screen: the app's own, so Media3 needn't name libVLC's; [VlcView] maps it to libVLC's.
 */
enum class Scale { BEST_FIT, FIT_SCREEN, FILL, RATIO_16_9, RATIO_4_3, ORIGINAL }

/** What the player screen drives: Media3 ([ExoView]) or libVLC ([VlcView]). */
abstract class Video(context: Context) : FrameLayout(context) {
    var onLoaded: (durationMs: Long) -> Unit = {}
    var onTime: (ms: Long) -> Unit = {}
    var onBuffering: (Boolean) -> Unit = {}
    var onEnded: () -> Unit = {}
    var onFailed: (String) -> Unit = {}
    /** This backend cannot play the file, though another might. */
    var onUnsupported: (String) -> Unit = { onFailed(it) }
    var onTracks: (Tracks) -> Unit = {}
    var onPlaying: (Boolean) -> Unit = {}
    /** Subtitle text; null is the player's own default. Set before [open] — libVLC reads it only when it starts. */
    open var subtitleStyle: SubtitleStyle? = null
    /** What a link's server wants with each request (an addon's `proxyHeaders`). Set before [open]. */
    var headers: Map<String, String> = emptyMap()
    /** The stream's type when its link doesn't say (an HLS playlist with no .m3u8): a MIME type, or null to guess. Set before [open]. */
    var mime: String? = null

    abstract fun open(url: String, startAt: Long, cacheMs: Int, subtitles: List<String>)
    abstract fun reload(ms: Long)
    abstract val time: Long
    /** How far past the picture this has read. */
    open val aheadSeconds: Double get() = 0.0
    abstract fun seekTo(ms: Long)
    abstract fun setPaused(value: Boolean)
    abstract val isPaused: Boolean
    abstract fun setRate(value: Float)
    abstract fun setSubtitleTrack(id: Int?)
    abstract fun setAudioTrack(id: Int)
    abstract fun setScale(type: Scale)
    /**
     * [select]: turn the last of [paths] on once it is attached. Done here, where the new track
     * is known, and as one switch — picking "the last track" afterwards switched twice, off and
     * on, and could land on one of the film's own; each switch has the player resync the stream.
     */
    abstract fun addSubtitleFiles(paths: List<String>, select: Boolean = false)
    /** Positive shows subtitles later. False when this backend cannot shift them that way. */
    open fun setSubtitleDelay(ms: Long): Boolean = ms == 0L
    abstract fun stats(): Stats?
}


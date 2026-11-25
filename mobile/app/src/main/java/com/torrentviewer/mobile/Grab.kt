package com.torrentviewer.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.torrentviewer.core.Ink
import com.torrentviewer.core.TubeVideo
import com.torrentviewer.core.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

/**
 * How to save a video, as Snaptube lists it: each video height, then the sound alone in MP3, M4A, Opus, FLAC or WAV,
 * and — when it's on the phone — "Send to Grabber" for the owner's Grabber app to do it instead.
 *
 * A Tube video ([tube]) lists the heights NewPipe reads (fast, many connections, joined by Android's muxer) and its
 * M4A the same way; every other format, and any other page, goes through yt-dlp ([Media]), which converts with FFmpeg.
 */
@Composable
fun SaveSheet(page: String, title: String, poster: String?, headers: Map<String, String> = emptyMap(), tube: TubeVideo? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    var heights by remember(page) { mutableStateOf<List<Int>?>(null) }
    var length by remember(page) { mutableStateOf(0) }
    var failed by remember(page) { mutableStateOf<String?>(null) }
    LaunchedEffect(page) {
        heights = if (tube != null) runCatching { Tube.downloadHeights(tube.id) }.getOrDefault(emptyList())
        else withContext(Dispatchers.IO) {
            runCatching { Media.info(context, page, headers) }.onSuccess { length = it.duration }.onFailure { failed = it.message }.getOrNull()?.heights.orEmpty()
        }
    }
    val list = heights
    val grabber = remember { Media.hasGrabber(context) }
    val options = buildList {
        if (list != null) {
            if (tube == null) add(Option("v:0", "Best Quality", "Video · MP4", icon = L.drawable.lucide_ic_film))
            list.forEach { h -> add(Option(if (tube != null) "tube:$h" else "v:$h", "${h}p", "Video · MP4" + if (tube == null) "" else " · to YouTube", icon = L.drawable.lucide_ic_film)) }
            AUDIO_FORMATS.forEach { f ->
                // M4A for a Tube video: YouTube's own AAC track, saved as it is.
                val code = if (tube != null && f.code == "a:m4a") "tube:audio" else f.code
                add(Option(code, f.label, "Audio · " + f.hint, icon = L.drawable.lucide_ic_music, iconTint = Ink.download))
            }
        }
        if (grabber) add(Option("grabber", "Send to Grabber", "The Grabber app picks the format", icon = L.drawable.lucide_ic_external_link))
    }
    Sheet(
        title = "Download ${title.take(60)}" + (if (length > 0) " · " + clockText(length.toLong()) else ""),
        options = options,
        selected = null,
        onPick = { code ->
            when {
                code == "grabber" -> Media.sendToGrabber(context, page, title)
                code == "tube:audio" && tube != null -> Downloads.addTube(tube, null, audio = true)
                code.startsWith("tube:") && tube != null -> Downloads.addTube(tube, code.removePrefix("tube:").toIntOrNull(), audio = false)
                else -> Downloads.addMedia(page, code, title, poster, headers)
            }
            onDone()
        },
        onDismiss = onDone,
        busy = list == null,
        empty = failed ?: "Nothing to save on this page.",
    )
}

/**
 * A page to grab, from outside the Browser: another app Shared a link to WatchHub, or one was copied and WatchHub was
 * opened. Shown as the format sheet over everything ([App]); yt-dlp reads what the page offers. [headers] is empty here
 * (no app's login crosses to another app on Android) — public posts need none; a login-walled one is opened in the
 * Browser, signed in there, instead.
 */
object PageGrab {
    var page by mutableStateOf<String?>(null)
    var name by mutableStateOf("")

    fun grab(url: String, title: String = url) {
        name = title
        page = url
    }

    fun clear() {
        page = null
    }
}

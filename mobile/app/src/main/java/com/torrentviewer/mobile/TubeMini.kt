package com.torrentviewer.mobile

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.TubeVideo
import com.torrentviewer.core.Type
import com.torrentviewer.core.grain
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

/**
 * What Tube is showing, kept outside the screen so a turn of the phone or another tab doesn't lose it: the video pages
 * open (newest last), and the video dropped down into the mini player, which keeps playing (Settings → YouTube).
 */
object TubeSession {
    /** The video pages open, the newest on top: Back from a related video returns to the one before. */
    val playing = mutableStateListOf<TubeVideo>()

    /** The video playing in the mini player; null when there's none. */
    var mini by mutableStateOf<TubeVideo?>(null)
        private set

    /** Set while a video page closes into the mini player: its player is kept, not stopped. */
    var keepPlaying = false

    private const val MINI = "tubeMiniPlayer"

    /** Whether dropping a video down keeps it playing small (the owner's choice: on unless turned off). */
    var miniEnabled by mutableStateOf(Kv.global(MINI) != "0")
        private set

    fun useMiniPlayer(on: Boolean) {
        miniEnabled = on
        Kv.setGlobal(MINI, if (on) "1" else "0")
    }

    /** The video page dropped down: into the mini player, still playing. */
    fun minimize(v: TubeVideo) {
        keepPlaying = true
        mini = v
        playing.clear()
    }

    /** The mini player tapped: its video's page again, carrying on where it is. */
    fun expand() {
        val v = mini ?: return
        mini = null
        playing.clear()
        playing.add(v)
    }

    /** Another video opened: the mini player gives way (one player plays at a time). */
    fun open(v: TubeVideo) {
        mini = null
        playing.add(v)
    }

    fun closeMini(context: Context) {
        mini = null
        TubeEngine.stop(context)
    }
}

/**
 * The mini player (Settings → YouTube), as YouTube's but a notch bigger: just the video — no strip, no box around it —
 * as wide as the tab bar and as tall as the video's own shape (no black bars), resting just above the bar. A tap
 * shows the controls on the picture (open, play/pause, close) for a few seconds; dragged, it goes anywhere.
 */
@Composable
fun TubeMiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val v = TubeSession.mini ?: return
    val context = LocalContext.current
    val player = remember { TubeEngine.player(context) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var ratio by remember { mutableStateOf(player.videoSize.let { if (it.width > 0 && it.height > 0) it.width * it.pixelWidthHeightRatio / it.height else 16f / 9f }) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Finished: it goes.
                if (state == androidx.media3.common.Player.STATE_ENDED) TubeSession.closeMini(context)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // The controls: shown for a few seconds after a tap (and at first), then the bare picture.
    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var controls by remember { mutableStateOf(true) }
    // The seek bar: where it is, read twice a second while the controls show; a drag holds them up.
    var at by remember { mutableStateOf(player.currentPosition / 1000.0) }
    var duration by remember { mutableStateOf(0.0) }
    var scrub by remember { mutableStateOf<Double?>(null) }
    androidx.compose.runtime.LaunchedEffect(shownAt, scrub == null) {
        controls = true
        if (scrub != null) return@LaunchedEffect
        kotlinx.coroutines.delay(3500)
        controls = false
    }
    androidx.compose.runtime.LaunchedEffect(controls) {
        while (controls) {
            at = player.currentPosition / 1000.0
            duration = player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            kotlinx.coroutines.delay(500)
        }
    }
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth / 2).toPx() }
        val maxY = with(density) { (maxHeight - 260.dp).toPx() }
        Box(
            Modifier.align(Alignment.BottomCenter)
                // As wide as the tab bar, resting just above it.
                .padding(start = 10.dp, end = 10.dp, bottom = 168.dp)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        dx = (dx + drag.x).coerceIn(-maxX, maxX)
                        dy = (dy + drag.y).coerceIn(-maxY, 0f)
                    }
                }
                // The video's own shape: no bars above or below it.
                .fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f))
                .shadow(14.dp, shape).clip(shape).background(Color.Black)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { shownAt = System.currentTimeMillis() },
        ) {
            Artwork(v.thumb, v.title, Modifier.fillMaxSize())
            AndroidView(
                factory = { c -> android.view.TextureView(c).also { player.setVideoTextureView(it) } },
                modifier = Modifier.fillMaxSize(),
                onRelease = { player.clearVideoTextureView(it) },
            )
            androidx.compose.animation.AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                    // Big enough to hit while it moves (the owner's choice).
                    Tap(onOpen, Modifier.align(Alignment.TopStart).padding(6.dp).size(54.dp), CircleShape) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(R.drawable.ic_maximize_thin, tint = Color.White, size = 30.dp) }
                    }
                    Tap({ TubeSession.closeMini(context) }, Modifier.align(Alignment.TopEnd).padding(6.dp).size(54.dp), CircleShape) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(R.drawable.ic_close_thin, tint = Color.White, size = 30.dp) }
                    }
                    Tap({ if (player.playWhenReady) player.pause() else player.play(); shownAt = System.currentTimeMillis() }, Modifier.align(Alignment.Center).size(72.dp), CircleShape) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                            // Light strokes, moderately big (the owner's choice).
                            Lc(if (playing) R.drawable.ic_pause_bars else R.drawable.ic_play_thin, tint = Color.White, size = 38.dp)
                        }
                    }
                    // The players' own seek bar along the foot (the owner's choice).
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp)) {
                        SeekBar(
                            at = { scrub ?: at }, playing = { at }, duration = duration, scrubbing = scrub != null,
                            onScrub = { scrub = it },
                            onCommit = {
                                scrub?.let { player.seekTo((it * 1000).toLong()); at = it }
                                scrub = null
                                shownAt = System.currentTimeMillis()
                            },
                        )
                    }
                }
            }
        }
    }
}

package com.torrentviewer.mobile

import android.content.Context
import androidx.compose.foundation.background
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

    fun setMiniEnabled(on: Boolean) {
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
 * The mini player (Settings → YouTube): a strip as wide as the tab bar, just above it, with the video playing on the
 * left, its title and channel, and large play/pause and close buttons. Dragged anywhere on the screen; a tap on it
 * opens the video's page again ([onOpen]).
 */
@Composable
fun TubeMiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val v = TubeSession.mini ?: return
    val context = LocalContext.current
    val player = remember { TubeEngine.player(context) }
    var playing by remember { mutableStateOf(player.isPlaying || player.playWhenReady) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying || player.playWhenReady }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                // Finished: the strip goes.
                if (state == androidx.media3.common.Player.STATE_ENDED) TubeSession.closeMini(context)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    // Where it has been dragged to, from its resting place above the tab bar.
    var dx by remember { mutableStateOf(0f) }
    var dy by remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth / 2).toPx() }
        val maxY = with(density) { (maxHeight - 220.dp).toPx() }
        Row(
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
                .fillMaxWidth().height(72.dp)
                .shadow(12.dp, shape).clip(shape).background(SHEET_FACE).grain(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The picture itself, still playing.
            Box(Modifier.fillMaxHeight().width(128.dp).background(Color.Black)) {
                Artwork(v.thumb, v.title, Modifier.fillMaxSize())
                AndroidView(
                    factory = { c -> android.view.TextureView(c).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { player.clearVideoTextureView(it) },
                )
            }
            Tap(onOpen, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    Text(v.title, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = 1)
                    Text(v.channel, style = Type.meta.copy(fontSize = 12.sp), maxLines = 1)
                }
            }
            // Big enough to hit while it moves (the owner's choice).
            Tap({ if (player.playWhenReady) player.pause() else player.play() }, Modifier.size(52.dp), CircleShape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Lc(if (playing) R.drawable.ic_pause_bars else L.drawable.lucide_ic_play, tint = Ink.text, size = 28.dp)
                }
            }
            Tap({ TubeSession.closeMini(context) }, Modifier.padding(end = 6.dp).size(52.dp), CircleShape) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_x, tint = Ink.text, size = 26.dp) }
            }
        }
    }
}

package com.torrentviewer.mobile

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.torrentviewer.core.bytes
import com.composables.icons.lucide.R as L

/**
 * A delete held for a few seconds behind an Undo strip before it takes effect (the owner's ask, as Grabber's). The file
 * is moved to the Bin at once (so Undo and the Bin can both bring it back); the hold decides only whether it's then
 * erased for good ([Downloads.eraseBin]) or left in the Bin. A new hold commits the one before it straight away.
 */
object UndoHold {
    data class Pending(val label: String, val seconds: Int, val undo: () -> Unit, val commit: () -> Unit)

    var pending by mutableStateOf<Pending?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null

    /** Shows the strip for [label], holding [seconds] before [commit]; [undo] undoes it if the viewer taps Undo first. */
    fun start(label: String, seconds: Int = 5, undo: () -> Unit, commit: () -> Unit) {
        commitNow()
        val p = Pending(label, seconds, undo, commit)
        pending = p
        val r = Runnable { if (pending === p) { pending = null; timer = null; runCatching { p.commit() } } }
        timer = r
        main.postDelayed(r, seconds * 1000L)
    }

    /** The viewer tapped Undo: the held action is dropped and its undo run. */
    fun undo() {
        val p = pending ?: return
        timer?.let { main.removeCallbacks(it) }; timer = null
        pending = null
        runCatching { p.undo() }
    }

    /** The hold finishes now (its time ran out, or another took its place): the action takes effect. */
    fun commitNow() {
        val p = pending ?: return
        timer?.let { main.removeCallbacks(it) }; timer = null
        pending = null
        runCatching { p.commit() }
    }
}

/** The Undo strip along the foot of the screen while a delete is held; a thin bar drains over the hold. */
@Composable
fun UndoStrip(modifier: Modifier = Modifier) {
    val p = UndoHold.pending
    AnimatedVisibility(p != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val drain = remember(p) { Animatable(1f) }
        LaunchedEffect(p) { if (p != null) runCatching { drain.animateTo(0f, tween(p.seconds * 1000)) } }
        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface2)) {
            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Lc(L.drawable.lucide_ic_trash_2, tint = Ink.muted, size = 20.dp)
                Text(p?.label.orEmpty(), Modifier.weight(1f).padding(horizontal = 12.dp), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tap({ UndoHold.undo() }, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Lc(L.drawable.lucide_ic_rotate_ccw, tint = Ink.download, size = 18.dp)
                        Text("  Undo", style = Type.label.copy(color = Ink.download, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            Box(Modifier.fillMaxWidth(drain.value.coerceIn(0f, 1f)).height(2.dp).background(Ink.download))
        }
    }
}

/** The Bin: deleted files kept 30 days, each with Restore and Erase (erase is held with Undo); "Empty Bin" too. */
@Composable
fun BinSheet(onDismiss: () -> Unit) {
    val items by Downloads.bin.collectAsState()
    BottomSheet("Bin", onDismiss, trailing = {
        if (items.isNotEmpty()) Tap({ UndoHold.start("Emptied the Bin", undo = {}, commit = { Downloads.emptyBin() }) }, shape = RoundedCornerShape(8.dp)) {
            Text("Empty Bin", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = Type.label.copy(color = Ink.danger, fontWeight = FontWeight.SemiBold))
        }
    }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            if (items.isEmpty()) EmptyNote("The Bin is empty. Deleted files wait here 30 days before they're cleared.")
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(end = 4.dp), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_film, tint = Ink.muted, size = 24.dp) }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(item.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(bytes(item.size), Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint))
                        }
                        // Put it back where it came from.
                        Tap({ Downloads.restoreFromBin(item) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_rotate_ccw, Modifier.padding(8.dp), tint = Ink.download, size = 20.dp) }
                        // Erase for good — held, so a slip can be undone.
                        Tap({ UndoHold.start("Erased \"${item.label}\"", undo = {}, commit = { Downloads.eraseBin(item) }) }, shape = CircleShape) {
                            Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(8.dp), tint = Ink.danger, size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

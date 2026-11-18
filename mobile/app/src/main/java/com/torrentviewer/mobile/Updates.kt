package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.torrentviewer.core.Updates
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes

/** Floating at the top of the screen while a new version is waiting. */
@Composable
fun UpdateCard(watch: Watch, modifier: Modifier = Modifier) {
    val release by Updates.release.collectAsState()
    val hidden by Updates.hidden.collectAsState()
    val r = release ?: return
    // Downloading: the card stays even if it was put off, so the progress is never out of sight.
    val progress by Updates.progress.collectAsState()
    val p = progress
    if (hidden && p == null) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    // While downloading, the card itself fills from the left in the accent colour, as Nuvio's does (the owner's choice).
    // The card in the accent itself, full strength, white words on it (the owner's choice); while downloading, the
    // part still to come is the accent darkened, the part done the accent as it is.
    val fill = Ink.accent
    val ink = Color.White
    Column(
        modifier.fillMaxWidth().padding(horizontal = EDGE, vertical = 8.dp).shadow(8.dp, shape).clip(shape).background(if (p != null) androidx.compose.ui.graphics.lerp(Ink.accent, Color.Black, 0.35f) else Ink.accent)
            .drawBehind { if (p != null) drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * p.coerceIn(0f, 1f), size.height)) },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (p != null) "Downloading update…" else "Update available", style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (p != null) "${(p * 100).toInt()}%  ·  ${bytes((p * r.size).toLong())} of ${bytes(r.size)}  ·  the installer opens when it's done"
                    else "Version ${r.version}  ·  ${bytes(r.size)}",
                    style = Type.meta.copy(color = ink.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // Downloads here, in the app, then hands the file to Android's installer.
            if (p == null) Button("Update", { Updates.download(context, r) { watch.notes.tryEmit(it) } }, compact = true, tint = Color.White)
            Spacer(Modifier.width(4.dp))
            // A cross rather than a "Not now" button: side by side, the two left the words three lines. While downloading, it stops it.
            Tap({ if (p != null) Updates.cancel() else Updates.skip(r.version) }, Modifier.size(36.dp), CircleShape) {
                // A thin cross, drawn: Lucide's stroke is heavier than this bar wants.
                // White, as the words.
                androidx.compose.foundation.Canvas(Modifier.align(Alignment.Center).size(14.dp)) {
                    val w = 1.3.dp.toPx()
                    drawLine(ink, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(ink, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), w, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

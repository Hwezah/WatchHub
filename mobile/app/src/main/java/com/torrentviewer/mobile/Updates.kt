package com.torrentviewer.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Type
import com.torrentviewer.core.Updates
import com.torrentviewer.core.Watch
import com.torrentviewer.core.grain

/** The strip's entrance: a short rise with a little spring at its end (the owner's choice, as the Folio app's). */
private val RISE = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)

/**
 * A new build, as a slim strip pinned along the top of the app (the owner's choice, as the Folio app's): flat and
 * frosted like the tab bar, no rim or shadow, one line — [mark] [what's happening · build — what it changed] [Install] [✕].
 * The build downloads in the background as soon as it's found ([Updates.prepare]): a spinner and "Downloading update…"
 * meanwhile, then a green dot, "Update ready" and Install, which hands it straight to Android's installer. The ✕ hides
 * it until the app is next opened; a download under way carries on.
 */
@Composable
fun UpdateCard(watch: Watch, modifier: Modifier = Modifier) {
    val release by Updates.release.collectAsState()
    val hidden by Updates.hidden.collectAsState()
    val progress by Updates.progress.collectAsState()
    val ready by Updates.ready.collectAsState()
    val failed by Updates.failed.collectAsState()
    val reason by Updates.failReason.collectAsState()
    val context = LocalContext.current
    val r = release ?: return
    // Fetched as soon as it's found (and again when a newer build replaces it), unless put away or already tried and failed.
    LaunchedEffect(r, hidden, ready, progress == null, failed) {
        if (!hidden && progress == null && ready != r && failed != r.version) Updates.prepare(context, r)
    }
    if (hidden) return
    val isReady = ready == r
    val isFailed = !isReady && progress == null && failed == r.version

    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(300, easing = RISE)) }
    // Dark: the tab bar's near-black; light: its deeper paper. Flat, with the grain.
    val face = if (Ink.isDark) NAV_DARK else Ink.surface2
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer {
                val v = rise.value
                alpha = v.coerceIn(0f, 1f)
                translationY = (1f - v) * 18.dp.toPx()
                scaleX = 0.98f + 0.02f * v
                scaleY = 0.98f + 0.02f * v
            }
            .background(face).grain()
            // A thin green strip along the bottom edge, filling as the update downloads: the spinner alone said nothing
            // of how far it had got (the owner's ask).
            .drawWithContent {
                drawContent()
                val p = progress
                if (p != null && !isReady && !isFailed) {
                    val h = 2.dp.toPx()
                    drawRect(Ink.download.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
                    drawRect(Ink.download, androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width * p.coerceIn(0f, 1f), h))
                }
            }
            .statusBarsPadding()
            // One height in every phase (the owner's ask, as Grabber's): "Installing…" has no buttons, so without this
            // the strip would sit shorter than while it downloads.
            .heightIn(min = 34.dp)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The mark: a small spinner while it downloads, a green dot once it's ready, a red one when the download failed.
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                isReady -> Box(Modifier.size(8.dp).background(Ink.good, CircleShape))
                isFailed -> Box(Modifier.size(8.dp).background(Ink.danger, CircleShape))
                else -> CircularProgressIndicator(Modifier.size(13.dp), color = Ink.muted, strokeWidth = 1.6.dp, trackColor = Color.Transparent, strokeCap = StrokeCap.Round)
            }
        }
        val headline = when {
            isReady -> "Update ready"
            isFailed -> "Update failed"
            else -> "Downloading update…"
        }
        val line = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ink.text)) { append(headline) }
            // Failed: why, in a few words (it's tried again by itself a few times); otherwise the build and what it changed.
            val detail = if (isFailed && reason != null) reason.orEmpty() else r.version + (r.summary?.let { " — $it" } ?: "")
            withStyle(SpanStyle(color = Ink.muted, fontSize = 13.sp)) { append("  ·  $detail") }
        }
        Text(line, Modifier.weight(1f), style = Type.label.copy(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isReady || isFailed) StripPill(if (isReady) "Install" else "Retry") {
            if (isReady) Updates.installReady(context) else Updates.prepare(context, r)
        }
        // A ghost ✕: no fill until pressed.
        Tap({ Updates.dismiss() }, Modifier.size(34.dp).semantics { contentDescription = "Hide the update" }, CircleShape) { pressed ->
            Box(Modifier.size(34.dp).background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                // A thin cross, drawn: Lucide's stroke is heavier than this strip wants.
                androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                    val w = 1.4.dp.toPx()
                    drawLine(Ink.muted, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(Ink.muted, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
        }
    }
}

/** The strip's small pill: the accent a notch toward black (12%), white words, the app's squared-off [PILL]. */
@Composable
private fun StripPill(label: String, onClick: () -> Unit) {
    Tap(onClick, shape = PILL) { pressed ->
        Box(
            Modifier.height(34.dp).clip(PILL).background(lerp(Ink.accent, Color.Black, if (pressed) 0.28f else 0.12f), PILL).grain().padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = Type.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1) }
    }
}

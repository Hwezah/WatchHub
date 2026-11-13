package com.torrentviewer.mobile

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.Sub
import com.torrentviewer.core.Type
import com.torrentviewer.mobile.cs.CsStreams
import com.composables.icons.lucide.R as L

/** For Auto download: the highest quality that can be saved, a single file before a playlist (it needs no joining), a link before a torrent. */
fun bestDownloadable(hits: List<Hit>): Hit? = hits.filter { canSave(it) && it.scope == com.torrentviewer.core.Scope.EPISODE }
    .maxWithOrNull(compareBy<Hit>({ rankOf(it.quality) }, { it.direct }, { !isPlaylist(it) }))

private fun rankOf(q: String?): Int = when {
    q == null -> 0
    q == "4K" -> 2160
    else -> q.removeSuffix("p").toIntOrNull() ?: 0
}

/**
 * What can be done with one source, as CloudStream offers it: play it here or in another app, try
 * another mirror from the same source, reload the links, open it in the browser, copy it, save it or
 * its subtitles. [all] is every source found for this title, for the mirrors and Auto download.
 */
@Composable
fun SourceActions(
    hit: Hit,
    heading: String,
    all: List<Hit>,
    onPlay: (Hit) -> Unit,
    onReload: () -> Unit,
    /** Into the Downloads tab: torrents, files and HLS playlists alike. */
    onDownload: (Hit) -> Unit,
    say: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var mirrors by remember { mutableStateOf(false) }
    val link = hit.direct
    val subs = remember(hit.uri) { CsStreams.subtitlesFor(hit.uri) }
    BottomSheet(heading, onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Text(
                listOfNotNull(hit.quality, hit.addon.ifEmpty { null }, hit.name).joinToString("  ·  "),
                Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Action(L.drawable.lucide_ic_play, "Play in app") { onDismiss(); onPlay(hit) }
            if (hit.external) {
                Action(L.drawable.lucide_ic_external_link, "Open in its app") { onDismiss(); onPlay(hit) }
            }
            Action(L.drawable.lucide_ic_download, "Auto download", "The best quality source that can be saved") {
                val best = bestDownloadable(all)
                if (best == null) say("None of these sources can be saved.")
                else {
                    onDismiss()
                    onDownload(best)
                    say("Downloading ${best.quality ?: "the best source"} — see Downloads")
                }
            }
            Action(
                L.drawable.lucide_ic_download, "Download mirror",
                when {
                    link && isDash(hit) -> "A DASH stream: can't be saved yet"
                    link && isPlaylist(hit) -> "A streaming playlist (HLS): saved as one .ts file"
                    else -> null
                },
            ) {
                when {
                    hit.external -> say("This one opens in another app.")
                    link && isDash(hit) -> say("DASH streams can't be saved yet — pick another source.")
                    else -> {
                        onDismiss()
                        onDownload(hit)
                        say("Downloading — see Downloads")
                    }
                }
            }
            Action(L.drawable.lucide_ic_captions, "Download subtitles", if (subs.isEmpty()) "None came with this source" else "${subs.size} with this source") {
                if (subs.isEmpty()) say("No subtitles came with this source. Find more from the player's subtitle menu.")
                else say(downloadSubs(context, subs, heading))
            }
            Action(L.drawable.lucide_ic_refresh_cw, "Reload links", "Ask every source again") { onDismiss(); onReload() }
            if (link) {
                Action(L.drawable.lucide_ic_globe, "Play in browser") {
                    val ok = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hit.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!ok) say("No browser could open it.")
                }
            }
            val magnet = magnetOf(hit)
            if (magnet != null) Action(L.drawable.lucide_ic_magnet, "Copy magnet link", "For any torrent app") {
                copy(context, heading, magnet)
                say("Magnet link copied")
            }
            if (!hit.uri.startsWith("magnet:", true)) Action(L.drawable.lucide_ic_copy, "Copy link") {
                copy(context, heading, hit.uri)
                say("Link copied")
            }
            if (link) {
                Action(L.drawable.lucide_ic_monitor_play, "Play in another player", "VLC, MX Player, Just Player…") {
                    if (!openInPlayer(context, hit, heading)) say("No other video app is installed.")
                }
            }
            val others = all.filter { it.addon == hit.addon && it.uri != hit.uri }
            Action(L.drawable.lucide_ic_list_video, "Play mirror", if (others.isEmpty()) "No other links from ${hit.addon.ifEmpty { "this source" }}" else "${others.size} more from ${hit.addon}") {
                if (others.isEmpty()) say("No other links from this source.") else mirrors = !mirrors
            }
            if (mirrors) others.forEach { m ->
                Tap({ onDismiss(); onPlay(m) }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                    Text(
                        listOfNotNull(m.quality, m.name).joinToString("  ·  "),
                        Modifier.padding(start = 56.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                        style = Type.body.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!link && !hit.external) Note("A torrent: it streams through the app's engine.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Action(icon: Int, label: String, hint: String? = null, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Lc(icon, tint = Ink.muted, size = 20.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp)) }
            }
        }
    }
}

private fun copy(context: Context, label: String, text: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * The source as a magnet link: a torrent's own, or one rebuilt from the info hash a debrid link
 * carries in its path (Torrentio's ".../resolve/<service>/<key>/<hash>/..."). Null when there is none.
 */
fun magnetOf(hit: Hit): String? = when {
    hit.uri.startsWith("magnet:", true) -> hit.uri
    hit.direct -> com.torrentviewer.core.linkHash(hit.uri)?.let { com.torrentviewer.core.magnetFor(it, hit.release) }
    else -> null
}

/** A link opened in another video app with its type, title and — for players that read them (MX, Just Player) — its headers. */
private fun openInPlayer(context: Context, hit: Hit, title: String): Boolean {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(hit.uri), hit.mime ?: "video/*")
        putExtra("title", title)
        if (hit.headers.isNotEmpty()) putExtra("headers", hit.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
    }
    val chooser = Intent.createChooser(view, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/** A file name Android's downloader accepts. */
private fun fileName(s: String) = s.replace(Regex("[^\\p{L}\\p{N} ._()-]+"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "video" }

/** Every subtitle that came with a source, beside where its video would go. */
private fun downloadSubs(context: Context, subs: List<Sub>, title: String): String {
    var started = 0
    subs.forEachIndexed { i, s ->
        val ext = Regex("\\.(srt|vtt|ass|ssa)(\\?|$)", RegexOption.IGNORE_CASE).find(s.url)?.groupValues?.get(1) ?: "srt"
        if (enqueue(context, s.url, fileName("$title ${s.language} ${i + 1}") + ".$ext", emptyMap(), "$title subtitles") != null) started++
    }
    return if (started == 0) "Couldn't start the subtitle downloads." else "Downloading $started subtitle file${if (started == 1) "" else "s"} to Downloads/WatchHub"
}

private fun enqueue(context: Context, url: String, name: String, headers: Map<String, String>, title: String): Long? = runCatching {
    if (!url.startsWith("http", true)) return null
    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    fun request() = DownloadManager.Request(Uri.parse(url)).apply {
        headers.forEach { (k, v) -> addRequestHeader(k, v) }
        setTitle(name)
        setDescription(title)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    }
    // Downloads/WatchHub; older Androids without storage permission refuse it, so the app's own folder then.
    runCatching { dm.enqueue(request().setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "WatchHub/$name")) }.getOrNull()
        ?: dm.enqueue(request().setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name))
}.getOrNull()

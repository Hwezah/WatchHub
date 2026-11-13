package com.torrentviewer.mobile

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
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
            // Android's share sheet: download managers (1DM, ADM), messengers, notes — whatever takes a link.
            Action(L.drawable.lucide_ic_share_2, "Share link", "1DM, ADM, Telegram… — use it soon, links expire") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, magnetOf(hit)?.takeIf { !link } ?: hit.uri)
                    putExtra(Intent.EXTRA_SUBJECT, heading)
                    putExtra(Intent.EXTRA_TITLE, heading)
                }
                if (runCatching { context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                    say("No app can take the link.")
                }
            }
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
            // Every download is green, here as on the buttons.
            Lc(icon, tint = if (icon == L.drawable.lucide_ic_download) Ink.download else Ink.muted, size = 20.dp)
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

/**
 * A source's ⬇: where to download it — this app's downloader, or one of the phone's own (1DM, ADM…),
 * offered by Android's app picker. A small card in the middle of the screen.
 */
@Composable
fun DownloadChoice(hit: Hit, heading: String, onInternal: () -> Unit, say: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    DownloadChoice(
        listOfNotNull(hit.quality, com.torrentviewer.core.bytes(hit.size).takeIf { hit.size > 0 }, hit.addon.ifEmpty { null }).joinToString("  ·  ").ifEmpty { hit.name },
        onInternal = onInternal,
        onExternal = { if (openInDownloader(context, hit, heading)) onDismiss() else say("No app on this phone can take that link.") },
        onDismiss = onDismiss,
    )
}

/**
 * The same choice for an episode or a season, before any source is picked: the internal downloader takes
 * Settings → Preferred download (a season opens the batch sheet set to it); an external one is handed the
 * pick's link, or a season's links at once. [detail] says what will be picked.
 */
@Composable
fun DownloadChoice(
    detail: String,
    onInternal: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit,
    internalNote: String = "Saved in the Downloads tab. Resumes after a pause, keeps to Wi-Fi if you set it, and gets a fresh link if this one expires.",
    externalNote: String = "1DM, ADM or another downloader on your phone — Android shows the ones you have. Start it soon: links like these can expire.",
) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Low on the screen, resting just a notch above the tab bar (about 70 dp over the system's own bar);
        // a tap on the dimmed page around it closes it.
        Box(
            Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 82.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ink.surface)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .padding(18.dp),
        ) {
            Text("Download", style = Type.title.copy(fontWeight = FontWeight.Bold))
            Text(detail, Modifier.padding(top = 2.dp, bottom = 14.dp), style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis)
            ChoiceTile(L.drawable.lucide_ic_download, "Use Internal App Downloader", internalNote, Ink.download) {
                onDismiss()
                onInternal()
            }
            Spacer(Modifier.height(10.dp))
            ChoiceTile(L.drawable.lucide_ic_external_link, "Choose External Downloader", externalNote, onClick = onExternal)
            Text(
                "Cancel", Modifier.align(Alignment.End).padding(top = 14.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 6.dp),
                style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = Ink.muted),
            )
        }
        }
    }
}

@Composable
private fun ChoiceTile(icon: Int, title: String, excerpt: String, tint: androidx.compose.ui.graphics.Color = Ink.text, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) { pressed ->
        Row(
            Modifier.fillMaxWidth().background(Ink.text.copy(alpha = if (pressed) 0.12f else 0.06f)).padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Lc(icon, Modifier.padding(top = 2.dp), tint = tint, size = 22.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                Text(excerpt, Modifier.padding(top = 3.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp))
            }
        }
    }
}

/**
 * Hands the link to whichever downloader the viewer picks, through Android's chooser: the file's link (a
 * torrent's magnet) as a VIEW, with its name and — for apps that read them — its headers and Referer.
 */
fun openInDownloader(context: Context, hit: Hit, title: String): Boolean {
    val link = downloadLink(hit)
    val referer = hit.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(link), if (link.startsWith("magnet:", true)) null else hit.mime ?: "video/*")
        if (link.startsWith("magnet:", true)) data = Uri.parse(link)
        putExtra("title", title)
        putExtra(Intent.EXTRA_TITLE, title)
        if (hit.headers.isNotEmpty()) putExtra("headers", hit.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        referer?.let { putExtra("referer", it) }
    }
    return runCatching { context.startActivity(Intent.createChooser(view, "Download with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** A link a downloader can take: the file's own, or a torrent's magnet. */
fun downloadLink(hit: Hit) = if (hit.direct) hit.uri else magnetOf(hit) ?: hit.uri

/**
 * A season's links for another downloader, all at once: shared as text, one link a line, which 1DM, ADM and
 * most others read as a batch. Headers can't go along, so a link that needs them may be refused there.
 */
fun sendLinks(context: Context, hits: List<Hit>, title: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, hits.joinToString("\n", transform = ::downloadLink))
    }
    return runCatching { context.startActivity(Intent.createChooser(send, "Send links to").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

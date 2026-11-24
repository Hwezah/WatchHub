package com.torrentviewer.mobile

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.CircleShape
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
    /** Links still coming in: the Reload beside the title spins. */
    busy: Boolean = false,
    /** Into the Downloads tab: torrents, files and HLS playlists alike. */
    onDownload: (Hit) -> Unit,
    say: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    /** The source the sheet is about: the one opened, or a mirror whose ⋮ turned the sheet to it. */
    var current by remember { mutableStateOf(current) }
    /** Which list is unfolded under its row: "play" or "download" (the mirrors), "subs" (the subtitles to save). */
    var unfolded by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val link = current.direct
    val subs = remember(current.uri) { CsStreams.subtitlesFor(current.uri) }
    /** The subtitles ticked to save: the first English one to start with (the owner's choice). */
    var ticked by remember(current.uri) { mutableStateOf(com.torrentviewer.core.defaultSubtitlePicks(subs.map { it.language })) }
    // The same source's other links, for Play mirror and Download mirror alike: the highest resolution first, then the lightest.
    val others = remember(current.uri, all) {
        all.filter { it.addon == current.addon && it.uri != current.uri }
            .sortedWith(compareByDescending<Hit> { rankOf(it.quality) }.thenBy { if (it.size > 0) it.size else Long.MAX_VALUE })
    }
    /** A mirror's ⋮: the whole sheet is about it now, from its top. */
    fun turnTo(m: Hit) {
        current = m
        unfolded = null
        scope.launch { scroll.animateScrollTo(0) }
    }
    // Beside the title, a green Reload: these links' tokens expire within minutes.
    BottomSheet(heading, onDismiss, trailing = {
        Tap(onReload, shape = RoundedCornerShape(4.dp)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ReloadArrow(busy, Ink.download, 18.dp)
                Text("  Reload!", style = Type.label.copy(color = Ink.download, fontWeight = FontWeight.SemiBold))
            }
        }
    }) {
        Column(Modifier.verticalScroll(scroll).padding(bottom = 8.dp)) {
            Text(
                listOfNotNull(current.quality, current.size.takeIf { it > 0 }?.let { com.torrentviewer.core.bytes(it) }, current.addon.ifEmpty { null }, current.name).joinToString("  ·  "),
                Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
                style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Action(L.drawable.lucide_ic_play, "Play in app") { onDismiss(); onPlay(current) }
            // Android's share sheet: download managers (1DM, ADM), messengers, notes — whatever takes a link.
            Action(L.drawable.lucide_ic_share_2, "Share link", "1DM, ADM, Telegram… — use it soon, links expire") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, magnetOf(current)?.takeIf { !link } ?: current.uri)
                    putExtra(Intent.EXTRA_SUBJECT, heading)
                    putExtra(Intent.EXTRA_TITLE, heading)
                }
                if (runCatching { context.startActivity(Intent.createChooser(send, "Share link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                    say("No app can take the link.")
                }
            }
            if (current.external) {
                Action(L.drawable.lucide_ic_external_link, "Open in its app") { onDismiss(); onPlay(current) }
            }
            // Settings → Preferred download decides (the owner's choice), not the highest quality: the smallest reasonable
            // file at that resolution and format, the same pick as every other automatic download ([preferredPick]).
            val prefs = remember { downloadPrefs() }
            Action(
                L.drawable.lucide_ic_download, "Auto download",
                "Your preferred ${listOf(prefs.quality, prefs.format).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "download" }} · Settings → Preferred Download",
            ) {
                val pick = preferredPick(allowedHits(all), prefs) ?: bestDownloadable(all)
                if (pick == null) say("None of these sources can be saved.")
                else {
                    onDismiss()
                    onDownload(pick)
                    say("Downloading ${describePick(pick)} — see Downloads")
                }
            }
            Action(
                L.drawable.lucide_ic_download, "Download this source",
                when {
                    link && isDash(current) -> "A DASH stream: can't be saved yet"
                    link && isPlaylist(current) -> "A streaming playlist (HLS): saved as one file"
                    else -> listOfNotNull(current.quality, current.size.takeIf { it > 0 }?.let { com.torrentviewer.core.bytes(it) }).joinToString(" · ").ifEmpty { null }
                },
            ) {
                when {
                    current.external -> say("This one opens in another app.")
                    link && isDash(current) -> say("DASH streams can't be saved yet — pick another source.")
                    else -> {
                        onDismiss()
                        onDownload(current)
                    }
                }
            }
            // Every link this source found, the highest resolution first — the ones Settings leaves out too — to save.
            Action(L.drawable.lucide_ic_list_video, "Download mirror", if (others.isEmpty()) "No other links from ${current.addon.ifEmpty { "this source" }}" else "${others.size} more from ${current.addon}") {
                if (others.isEmpty()) say("No other links from this source.") else unfolded = if (unfolded == "download") null else "download"
            }
            if (unfolded == "download") others.forEach { m ->
                MirrorRow(m, onTap = { if (m.external || m.direct && isDash(m)) say("That one can't be saved.") else { onDismiss(); onDownload(m) } }, onMore = { turnTo(m) })
            }
            // Ticked first: the first English (the owner's choice); the app saves them itself, beside the video.
            Action(L.drawable.lucide_ic_captions, "Download subtitles", if (subs.isEmpty()) "None came with this source" else "${subs.size} with this source · pick which") {
                if (subs.isEmpty()) say("No subtitles came with this source. Find more from the player's subtitle menu.")
                else unfolded = if (unfolded == "subs") null else "subs"
            }
            if (unfolded == "subs") {
                subs.forEachIndexed { i, sub ->
                    val on = i in ticked
                    Tap({ ticked = if (on) ticked - i else ticked + i }, Modifier.fillMaxWidth(), RoundedCornerShape(4.dp)) {
                        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_asterisk, tint = Ink.download, size = 16.dp)
                            Text(
                                com.torrentviewer.core.subtitleLanguage(sub.language) + sub.release.ifEmpty { sub.name }.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty(),
                                Modifier.weight(1f).padding(start = 12.dp), style = Type.body.copy(color = if (on) Ink.text else Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            // A circle that fills when ticked, as a torrent's files are chosen.
                            Lc(if (on) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle, tint = if (on) Ink.download else Ink.faint, size = 20.dp)
                        }
                    }
                }
                Button(
                    if (ticked.isEmpty()) "Tick the ones to save" else "Save ${ticked.size} subtitle${if (ticked.size == 1) "" else "s"}",
                    {
                        val pick = ticked.sorted().mapNotNull { subs.getOrNull(it) }
                        if (pick.isNotEmpty()) scope.launch {
                            val n = Downloads.saveSubtitles(pick, heading)
                            say(if (n == 0) "Couldn't save the subtitles." else "Saved $n subtitle${if (n == 1) "" else "s"} beside the video")
                        }
                        unfolded = null
                    },
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_download, compact = true,
                )
            }
            Action(L.drawable.lucide_ic_rotate_cw, "Reload links", "Ask every source again") { onDismiss(); onReload() }
            if (link) {
                Action(L.drawable.lucide_ic_globe, "Play in browser") {
                    val ok = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    if (!ok) say("No browser could open it.")
                }
            }
            val magnet = magnetOf(current)
            if (magnet != null) Action(L.drawable.lucide_ic_magnet, "Copy magnet link", "For any torrent app") {
                copy(context, heading, magnet)
                say("Magnet link copied")
            }
            if (!current.uri.startsWith("magnet:", true)) Action(L.drawable.lucide_ic_copy, "Copy link") {
                copy(context, heading, current.uri)
                say("Link copied")
            }
            if (link) {
                Action(L.drawable.lucide_ic_monitor_play, "Play in another player", "VLC, MX Player, Just Player…") {
                    if (!openInPlayer(context, current, heading)) say("No other video app is installed.")
                }
            }
            Action(L.drawable.lucide_ic_list_video, "Play mirror", if (others.isEmpty()) "No other links from ${current.addon.ifEmpty { "this source" }}" else "${others.size} more from ${current.addon}") {
                if (others.isEmpty()) say("No other links from this source.") else unfolded = if (unfolded == "play") null else "play"
            }
            if (unfolded == "play") others.forEach { m -> MirrorRow(m, onTap = { onDismiss(); onPlay(m) }, onMore = { turnTo(m) }) }
            if (!link && !current.external) EmptyNote("A torrent: it streams through the app's engine.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** A mirror in Play mirror or Download mirror: what it is, then its ⋮ — every action for it (the owner's choice). */
@Composable
private fun MirrorRow(m: Hit, onTap: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tap(onTap, Modifier.weight(1f), RoundedCornerShape(5.dp)) {
            Text(
                listOfNotNull(m.quality, m.size.takeIf { it > 0 }?.let { com.torrentviewer.core.bytes(it) }, m.name).joinToString("  ·  "),
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = Type.body.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Tap(onMore, Modifier.semantics { contentDescription = "Options for this mirror" }, CircleShape) {
            Lc(L.drawable.lucide_ic_ellipsis_vertical, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp)
        }
    }
}

@Composable
private fun Action(icon: Int, label: String, hint: String? = null, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(5.dp)) {
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
        onExternal = { to ->
            val handed = openInDownloader(context, hit, heading, to)
            ExternalDownloaders.note(handed, many = false)?.let(say)
            if (handed != Handed.Failed) onDismiss()
        },
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
    /** Tapped: [Downloader.Default]; held: whatever was picked in the "Download With" sheet. */
    onExternal: (Downloader) -> Unit,
    onDismiss: () -> Unit,
    internalNote: String = "Into the Downloads tab · resumes, renews its link",
    externalNote: String = "1DM, ADM or another downloader",
    /** What kind of hand-off this is, to find the apps that take it: one link, or a season's as text. */
    probe: Intent = ExternalDownloaders.viewProbe(),
    /** A line in green under the choices: where the picked resolution comes from, and where to change it. */
    disclaimer: String? = null,
) {
    // Held, the external choice gives way to the "Download With" sheet; let go of that without a pick, it is back.
    var choosing by remember { mutableStateOf(false) }
    if (choosing) {
        DownloaderSheet(probe, many = probe.action == Intent.ACTION_SEND, onPick = { onExternal(it) }, onDismiss = { choosing = false })
        return
    }
    // A sheet from below, as every other choice in the app (the owner's choice), not a box floating over the page.
    BottomSheet("", onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
            // "Download" and, beside it, what it is: an asterisk, then the resolution, size and site, in the dark green.
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Download", style = Type.title.copy(fontWeight = FontWeight.Bold))
                Lc(L.drawable.lucide_ic_asterisk, Modifier.padding(start = 12.dp, end = 4.dp), tint = Ink.download, size = 16.dp)
                Text(
                    detail, Modifier.weight(1f).basicMarquee(), maxLines = 1,
                    style = Type.meta.copy(color = Ink.download, fontWeight = FontWeight.SemiBold),
                )
            }
            ChoiceTile(L.drawable.lucide_ic_download, "Internal Downloader", internalNote, Ink.download) {
                onDismiss()
                onInternal()
            }
            Spacer(Modifier.height(10.dp))
            // A tap goes to the default app; held, the "Download With" sheet offers the others.
            ChoiceTile(
                L.drawable.lucide_ic_external_link, "External Downloader",
                "$externalNote · hold for more", onLongClick = { choosing = true },
            ) { onExternal(Downloader.Default) }
            disclaimer?.let { Text(it, Modifier.padding(top = 12.dp), style = Type.meta.copy(color = Ink.download, fontWeight = FontWeight.Medium)) }
        }
    }
}

@Composable
private fun ChoiceTile(icon: Int, title: String, excerpt: String, tint: androidx.compose.ui.graphics.Color = Ink.text, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(9.dp), onLongClick = onLongClick) { pressed ->
        Row(
            Modifier.fillMaxWidth().background(Ink.text.copy(alpha = if (pressed) 0.12f else 0.06f)).padding(CARD - 2.dp),
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
 * Hands the link to a downloader ([to]: by default the app picked last, else 1DM / ADM, else Android's
 * picker): the file's link (a torrent's magnet) as a VIEW, with its name and — for apps that read them — its
 * headers and Referer.
 */
fun openInDownloader(context: Context, hit: Hit, title: String, to: Downloader = Downloader.Default): Handed {
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
    return ExternalDownloaders.hand(context, view, to, "Download with", link)
}

/** A link a downloader can take: the file's own, or a torrent's magnet. */
fun downloadLink(hit: Hit) = if (hit.direct) hit.uri else magnetOf(hit) ?: hit.uri

/**
 * A season's links for another downloader, all at once: shared as text, one link a line, which 1DM, ADM and
 * most others read as a batch. Headers can't go along, so a link that needs them may be refused there.
 */
fun sendLinks(context: Context, hits: List<Hit>, title: String, to: Downloader = Downloader.Default, names: List<String> = emptyList()): Handed {
    val text = hits.joinToString("\n", transform = ::downloadLink)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        // Each link's name, in the same order ("The Penguin · S01E01"), for a downloader that reads it (Grabber does):
        // a playlist's address (index_287.m3u8) says nothing of what it is.
        if (names.size == hits.size) putExtra("titles", names.toTypedArray())
        // Each link's headers, in the same order, for a downloader that sends them (Grabber does): some file hosts
        // (Moviesmod's) hand over a file only with the Referer of the page it came from. "referers" for the simple case,
        // "headers_json" (a JSON array of {name: value} objects, one per link) for all of them.
        if (hits.any { it.headers.isNotEmpty() }) {
            putExtra("referers", hits.map { h -> h.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value.orEmpty() }.toTypedArray())
            putExtra("headers_json", org.json.JSONArray(hits.map { h -> org.json.JSONObject(h.headers) }).toString())
        }
    }
    return ExternalDownloaders.hand(context, send, to, "Send links to", text)
}

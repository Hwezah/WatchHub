package com.torrentviewer.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Type
import com.torrentviewer.core.bytes
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/**
 * A download's every action, as 1DM lists them (the owner's ask: a download that sat still had no way on): pause or
 * resume, start again where it is, ask for a fresh link, open its page in the Browser, paste a new link to the same file
 * (what's downloaded is kept), copy its link, rename it, save what's downloaded so far as a file that plays, its
 * properties, and delete. Opened by tapping the download's card.
 */
@Composable
fun DownloadOptionsSheet(row: LiveDownload, onCancel: () -> Unit, onSwap: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val job = row.job
    /** Which of its own sheets is up instead of the list: "rename", "update", "props". */
    var inner by remember { mutableStateOf<String?>(null) }
    fun say(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val page = job.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value?.takeIf { it.startsWith("http", true) }
    when (inner) {
        "rename" -> return RenameDownloadSheet(job.label, onSave = { Downloads.rename(job.uri, it); onDismiss() }, onDismiss = onDismiss)
        "update" -> return UpdateLinkSheet(row, onDismiss)
        "props" -> return DownloadPropertiesSheet(row, onDismiss)
    }
    val paused = row.state == DlState.PAUSED
    val options = buildList {
        add(Option("toggle", if (paused) "Resume" else "Pause", icon = if (paused) L.drawable.lucide_ic_play else R.drawable.ic_pause_bars))
        add(Option("force", "Force start", "Start again from where it is: for one that sits still", icon = L.drawable.lucide_ic_zap))
        if (job.link && (job.imdb != null || job.source != null)) add(Option("refresh", "Refresh link", "Ask the sources for a fresh link to this file", icon = L.drawable.lucide_ic_rotate_cw))
        if (page != null) add(Option("browser", "Refresh link in the Browser", "Open the page it came from, to grab the link again", icon = L.drawable.lucide_ic_globe))
        if (job.link) add(Option("update", "Update link", "Paste a new link to the same file — what's downloaded is kept", icon = L.drawable.lucide_ic_link))
        if (Downloads.canSwap(job)) add(Option("swap", "Different source", "The same title from another source", icon = L.drawable.lucide_ic_shuffle))
        add(Option("copy", "Copy download link", icon = L.drawable.lucide_ic_copy))
        add(Option("copyname", "Copy download link + file name", icon = L.drawable.lucide_ic_clipboard_copy))
        add(Option("rename", "Rename file", icon = L.drawable.lucide_ic_pencil))
        if (job.link && row.done > 0) add(Option("partial", "Save what's downloaded", "${bytes(row.done)} so far, as a file that plays up to there", icon = L.drawable.lucide_ic_save))
        add(Option("props", "Properties", "Link, size, folder, source", icon = L.drawable.lucide_ic_info))
        add(Option("delete", "Delete…", icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger))
    }
    Sheet(job.label, options, null, onPick = { pick ->
        when (pick) {
            "toggle" -> if (paused) Downloads.resume(job.uri) else Downloads.pause(job.uri)
            "force" -> { Downloads.forceStart(job.uri); say("Starting again from where it is") }
            "refresh" -> { Downloads.retry(job.uri, byHand = true); say("Asking for a fresh link…") }
            "browser" -> page?.let {
                Browser.newTab()
                Browser.go(it)
                Nav.tab = Tab.BROWSER
            }
            "swap" -> onSwap()
            "copy" -> copyText(context, job.label, job.uri).also { say("Link copied") }
            "copyname" -> copyText(context, job.label, "${job.uri}\n${job.label}").also { say("Link and name copied") }
            "partial" -> {
                val f = Downloads.savePartial(job.uri)
                say(if (f == null) "Nothing to save yet" else "Saved as ${f.name} — see On this phone")
            }
            "delete" -> onCancel()
            "rename", "update", "props" -> { inner = pick; return@Sheet }
        }
        onDismiss()
    }, onDismiss = onDismiss)
}

private fun copyText(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** A download's new name, typed in. */
@Composable
private fun RenameDownloadSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BottomSheet("Rename", onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Field(name, { name = it }, "Name", Modifier.fillMaxWidth(), onSubmit = { onSave(name) }, focus = focus)
            Button("Save", { onSave(name) }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_check, compact = true)
        }
    }
}

/**
 * A new link for the same file, pasted (the owner's ask): what's downloaded moves to it. The new link's size is asked
 * for and set beside the old one, so a different file is said before it's used.
 */
@Composable
private fun UpdateLinkSheet(row: LiveDownload, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val expected = row.total.takeIf { it > 0 } ?: row.job.size
    LaunchedEffect(link) {
        note = null
        if (!link.startsWith("http", true)) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        val size = probeLink(link.trim(), row.job.headers)?.size ?: return@LaunchedEffect
        note = when {
            size <= 0 || expected <= 0 -> "The new link's size: ${if (size > 0) bytes(size) else "not said"}"
            kotlin.math.abs(size - expected) <= expected / 200 -> "The same size, ${bytes(size)}: it carries on from ${bytes(row.done)}"
            else -> "That file is ${bytes(size)}, not ${bytes(expected)}: a different file — it would start again"
        }
    }
    BottomSheet(row.job.label, onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(row.job.uri, style = Type.meta.copy(color = Ink.faint), maxLines = 2)
            Text("Paste a new link to the same file (get it again from the site). Progress is kept.", Modifier.padding(top = 10.dp, bottom = 8.dp), style = Type.body)
            Field(link, { link = it }, "https://…", Modifier.fillMaxWidth(), focus = focus)
            note?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = if (it.startsWith("That file")) Ink.warn else Ink.download)) }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button("Paste", {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { link = it }
                }, Modifier.weight(1f), icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
                Button("Use it", {
                    scope.launch {
                        val ok = Downloads.replaceLink(row.job.uri, link)
                        android.widget.Toast.makeText(context, if (ok) "New link in use" else "That isn't a new link", android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) onDismiss()
                    }
                }, Modifier.weight(1f), icon = L.drawable.lucide_ic_check, compact = true, fill = Ink.download)
            }
        }
    }
}

/** What a download is: its link, sizes, where it goes, where it came from, the folder it's kept in while it runs. */
@Composable
private fun DownloadPropertiesSheet(row: LiveDownload, onDismiss: () -> Unit) {
    val job = row.job
    val lines = listOfNotNull(
        "Name" to job.label,
        "Link" to job.uri,
        "Size" to (if (row.total > 0) bytes(row.total) else if (job.size > 0) bytes(job.size) else "Not known"),
        "Downloaded" to bytes(row.done),
        "State" to (row.error ?: row.state.label),
        job.quality?.let { "Resolution" to it },
        job.source?.let { "Source" to it },
        job.headers.entries.firstOrNull { it.key.equals("referer", true) }?.let { "Referer page" to it.value },
        if (job.link) "Working folder" to Downloads.workOf(job.uri).path else null,
    )
    BottomSheet("Properties", onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            lines.forEach { (k, v) ->
                Text(k, Modifier.padding(top = 8.dp), style = Type.overline.copy(color = Ink.faint))
                Text(v, style = Type.meta.copy(color = Ink.text), maxLines = 4)
            }
        }
    }
}

/** What a link says about itself, one byte asked for: size, type, whether it resumes, its file name. */
data class LinkInfo(val size: Long, val type: String?, val resumable: Boolean, val fileName: String?, val finalUrl: String)

/** [LinkInfo] for [url] with [headers], redirects followed by hand; null when it doesn't answer with a file. */
suspend fun probeLink(url: String, headers: Map<String, String> = emptyMap()): LinkInfo? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        var target = url
        repeat(6) {
            val conn = (java.net.URL(target).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                if (headers.keys.none { it.equals("user-agent", true) }) setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    target = java.net.URL(java.net.URL(target), conn.getHeaderField("Location") ?: return@runCatching null).toString()
                    return@repeat
                }
                if (code !in 200..299) return@runCatching null
                val size = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: conn.contentLengthLong.takeIf { code == 200 && it > 1 } ?: 0L
                return@runCatching LinkInfo(size, conn.contentType, code == 206, fileNameOf(conn.getHeaderField("Content-Disposition"), target), target)
            } finally {
                conn.disconnect()
            }
        }
        null
    }.getOrNull()
}

/**
 * Before an internal download of a link, what it is and how to save it (the owner's ask, as Grabber's "Download file"):
 * the link and, asked of its server, its size, type and whether it resumes; the file's name and the page it came from
 * (Referer), both editable; the subtitles that came with it, the first English ticked; then Cancel, Check again, Add
 * (queued, paused) or Download. [onSave] gets the name, the Referer, whether to hold it paused, and the subtitles ticked.
 */
@Composable
fun DownloadFileSheet(
    url: String,
    name: String,
    headers: Map<String, String> = emptyMap(),
    playlist: Boolean = false,
    subs: List<com.torrentviewer.core.Sub> = emptyList(),
    onSave: (name: String, referer: String, paused: Boolean, subs: List<com.torrentviewer.core.Sub>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var fileName by remember { mutableStateOf(name) }
    var referer by remember { mutableStateOf(headers.entries.firstOrNull { it.key.equals("referer", true) }?.value.orEmpty()) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var asking by remember { mutableStateOf(0) }
    var checked by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(com.torrentviewer.core.defaultSubtitlePicks(subs.map { it.language })) }
    LaunchedEffect(asking) {
        checked = false
        info = if (playlist) null else probeLink(url, headers)
        checked = true
    }
    fun save(paused: Boolean) {
        onSave(fileName.trim().ifEmpty { name }, referer.trim(), paused, ticked.sorted().mapNotNull { subs.getOrNull(it) })
        onDismiss()
    }
    BottomSheet("", onDismiss) {
        androidx.compose.foundation.layout.Column(Modifier.androidxScroll().padding(horizontal = 20.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(end = 12.dp).androidxDisc(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { com.torrentviewer.core.Lc(L.drawable.lucide_ic_download, tint = Ink.download, size = 20.dp) }
                Text("Download file", Modifier.weight(1f), style = Type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                // Open its page here, copy the link, share it.
                Tap({ Browser.newTab(); Browser.go(url); Nav.tab = Tab.BROWSER; onDismiss() }, shape = androidx.compose.foundation.shape.CircleShape) {
                    com.torrentviewer.core.Lc(L.drawable.lucide_ic_globe, Modifier.padding(8.dp), tint = Ink.text, size = 22.dp)
                }
                Tap({ copyText(context, fileName, url); android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show() }, shape = androidx.compose.foundation.shape.CircleShape) {
                    com.torrentviewer.core.Lc(L.drawable.lucide_ic_copy, Modifier.padding(8.dp), tint = Ink.text, size = 22.dp)
                }
                Tap({
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share link").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, shape = androidx.compose.foundation.shape.CircleShape) {
                    com.torrentviewer.core.Lc(L.drawable.lucide_ic_share_2, Modifier.padding(8.dp), tint = Ink.text, size = 22.dp)
                }
            }
            Text(url, Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.text), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val about = when {
                playlist -> "Video stream (HLS) · joined and saved as one file"
                !checked -> "Asking the server…"
                info == null -> "The server didn't answer with a file — Check again, or try another link"
                else -> listOfNotNull(
                    info!!.size.takeIf { it > 0 }?.let { bytes(it) },
                    info!!.type,
                    if (info!!.resumable) "resumable, ${Downloads.connections} connections" else "one connection, not resumable",
                ).joinToString(" · ")
            }
            Text(about, Modifier.padding(top = 6.dp, bottom = 12.dp), style = Type.meta.copy(color = Ink.faint))
            Field(fileName, { fileName = it }, "File name", Modifier.fillMaxWidth())
            Field(referer, { referer = it }, "Referer page (leave empty if not sure)", Modifier.padding(top = 10.dp).fillMaxWidth())
            if (subs.isNotEmpty()) {
                Text("Subtitles · ${ticked.size} of ${subs.size} ticked", Modifier.padding(top = 14.dp, bottom = 4.dp), style = Type.overline.copy(color = Ink.faint))
                subs.forEachIndexed { i, sub ->
                    val on = i in ticked
                    Tap({ ticked = if (on) ticked - i else ticked + i }, Modifier.fillMaxWidth(), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) {
                        androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            com.torrentviewer.core.Lc(if (on) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle, tint = if (on) Ink.download else Ink.faint, size = 20.dp)
                            Text(com.torrentviewer.core.subtitleLanguage(sub.language), Modifier.padding(start = 12.dp), style = Type.body.copy(color = if (on) Ink.text else Ink.muted))
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button("Cancel", onDismiss, Modifier.weight(1f), compact = true)
                Button("Check again", { asking++ }, Modifier.weight(1f), icon = L.drawable.lucide_ic_rotate_cw, compact = true)
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button("Add", { save(paused = true) }, Modifier.weight(1f), icon = L.drawable.lucide_ic_plus, compact = true)
                Button("Download", { save(paused = false) }, Modifier.weight(1f), icon = L.drawable.lucide_ic_download, compact = true, fill = Ink.download)
            }
        }
    }
}

/** The sheet's content scrolls when it's taller than the sheet. */
@Composable
private fun Modifier.androidxScroll(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

/** The download mark's soft green disc, as Grabber's sheet heads itself. */
private fun Modifier.androidxDisc(): Modifier = this.then(
    Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.download.copy(alpha = 0.16f)),
)

/**
 * A link copied from somewhere else, pasted (the tab bar's ＋, as Grabber's): it then goes through [DownloadFileSheet].
 * The clipboard's link is in the field already when it is one.
 */
@Composable
fun AddLinkSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clip = remember { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty() }
    var link by remember { mutableStateOf(clip.takeIf { it.startsWith("http", true) || it.startsWith("magnet:", true) }.orEmpty()) }
    var next by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    next?.let { url ->
        val guess = url.substringBefore('?').substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.ifBlank { "Download" }
        val hls = Regex("(?i)\\.m3u8($|\\?)").containsMatchIn(url)
        DownloadFileSheet(url, guess, playlist = hls, onSave = { name, referer, paused, _ ->
            val headers = if (referer.isNotEmpty()) mapOf("Referer" to referer) else emptyMap()
            Downloads.addWeb(url, name, headers, hls)
            if (paused) Downloads.pause(url)
            android.widget.Toast.makeText(context, if (paused) "Added, paused — see Downloads" else "Downloading — see Downloads", android.widget.Toast.LENGTH_SHORT).show()
        }, onDismiss = onDismiss)
        return
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BottomSheet("Add a link", onDismiss) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Field(link, { link = it }, "https://… or magnet:…", Modifier.fillMaxWidth(), focus = focus, onSubmit = { if (link.isNotBlank()) next = link.trim() })
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                Button("Paste", {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { link = it }
                }, Modifier.weight(1f), icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
                Button("Next", {
                    val l = link.trim()
                    when {
                        l.startsWith("magnet:", true) -> {
                            Downloads.add(DownloadJob(uri = l, title = "Torrent", titleName = null))
                            android.widget.Toast.makeText(context, "Downloading — see Downloads", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                        l.startsWith("http", true) -> next = l
                        else -> android.widget.Toast.makeText(context, "That isn't a link", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.weight(1f), icon = L.drawable.lucide_ic_arrow_right, compact = true)
            }
        }
    }
}

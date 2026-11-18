package com.torrentviewer.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.TubeChannel
import com.torrentviewer.core.TubeVideo
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.grain
import com.torrentviewer.core.bytes
import com.torrentviewer.core.clockText
import com.torrentviewer.core.compactCount
import com.torrentviewer.core.parseSubscriptions
import com.torrentviewer.core.tubeLine
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as L

/** The Tube tab's five parts, chosen along its top (the screenshot's bottom bar, moved up: the app has its own). */
private enum class TubeSection(val label: String) { SUBS("Subscriptions"), HISTORY("History"), DOWNLOADS("Downloads"), YOU("Watch Later") }

/** A page opened over the sections: a search, a channel, or a playlist. Back closes it. */
private sealed interface TubeRoute {
    data object You : TubeRoute
    data class Channel(val url: String, val name: String) : TubeRoute
    data class Playlist(val url: String, val name: String) : TubeRoute
}

/** A video's thumbnail and a channel's picture: rounded, as the screenshot's cards. */
private val THUMB = RoundedCornerShape(14.dp)

/** One short message for a failed read: YouTube's own words are long and technical. */
private fun tubeError(e: Throwable): String = when {
    e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "No connection to YouTube."
    e.javaClass.simpleName.contains("ReCaptcha") -> "YouTube is asking to slow down. Try again in a minute."
    e.javaClass.simpleName.contains("AgeRestricted") -> "YouTube needs a sign-in for this one (age restricted)."
    e.javaClass.simpleName.contains("Unavailable") || e.javaClass.simpleName.contains("Private") -> "This video isn't available."
    else -> "Couldn't reach YouTube."
}

/**
 * The Tube tab: a YouTube client of our own (NewPipeExtractor underneath, see [Tube]). Home is the newest from
 * subscriptions and then what's trending; Subscriptions, History, Downloads and You along the top; Search and
 * Cast in the header. A video opens on its own page over everything, as YouTube's does.
 */
@Composable
fun TubeScreen(watch: Watch) {
    var section by rememberSaveable { mutableStateOf(TubeSection.SUBS) }
    var typed by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var hints by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(typed) {
        if (typed.isBlank() || typed == query) { hints = emptyList(); return@LaunchedEffect }
        delay(250)
        hints = runCatching { Tube.suggestions(typed) }.getOrDefault(emptyList()).take(8)
    }
    val routes = remember { mutableStateListOf<TubeRoute>() }
    /** The videos opened, the newest on top: Back from a related video returns to the one before. */
    val playing = remember { mutableStateListOf<TubeVideo>() }
    var menu by remember { mutableStateOf<TubeVideo?>(null) }
    var saving by remember { mutableStateOf<TubeVideo?>(null) }
    val context = LocalContext.current

    val open: (TubeVideo) -> Unit = { playing.add(it) }
    val openChannel: (String, String) -> Unit = { url, name ->
        playing.clear()
        routes.add(TubeRoute.Channel(url, name))
    }

    BackHandler(LocalShown.current && playing.isEmpty() && routes.isNotEmpty()) { routes.removeAt(routes.lastIndex) }
    // Searching: Back clears the search and the feed comes back.
    BackHandler(LocalShown.current && playing.isEmpty() && routes.isEmpty() && (query.isNotEmpty() || hints.isNotEmpty())) {
        typed = ""; query = ""; hints = emptyList()
    }
    val playlist: (String, String) -> Unit = { url, name -> routes.add(TubeRoute.Playlist(url, name)) }

    Box(Modifier.fillMaxSize().background(Ink.bg).grain()) {
        when (val route = routes.lastOrNull()) {
            null -> Column(Modifier.fillMaxSize()) {
                TubeHeader(typed, { typed = it }, onSubmit = { query = typed.trim(); hints = emptyList() }, onLogin = { routes.add(TubeRoute.You) })
                when {
                    hints.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        items(hints) { h ->
                            Row(Modifier.fillMaxWidth().clickable { typed = h; query = h; hints = emptyList() }.padding(horizontal = 22.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Lc(R.drawable.ic_search_thin, tint = Ink.faint, size = 18.dp)
                                Text(h, Modifier.padding(start = 14.dp), style = Type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    query.isNotBlank() -> Paged(query, { Tube.search(query) }, "Nothing found for \"$query\".", open, { menu = it }, openChannel, playlist)
                    else -> HomeFeed(open, { menu = it }, openChannel)
                }
            }
            TubeRoute.You -> Column(Modifier.fillMaxSize()) {
                BackBar("You", onBack = { routes.removeAt(routes.lastIndex) })
                SectionRow(section) { section = it }
                when (section) {
                    TubeSection.SUBS -> SubsFeed(open, { menu = it }, openChannel)
                    TubeSection.HISTORY -> HistoryList(open, { menu = it }, openChannel)
                    TubeSection.DOWNLOADS -> TubeDownloads(watch)
                    TubeSection.YOU -> YouPage(open, { menu = it }, openChannel)
                }
            }
            is TubeRoute.Channel -> androidx.compose.runtime.key(route) {
                ChannelPage(route.url, route.name, open, { menu = it }, onPlaylist = { url, name -> routes.add(TubeRoute.Playlist(url, name)) }, onBack = { routes.removeAt(routes.lastIndex) })
            }
            is TubeRoute.Playlist -> androidx.compose.runtime.key(route) {
                PlaylistPage(route.url, route.name, open, { menu = it }, openChannel, onBack = { routes.removeAt(routes.lastIndex) })
            }
        }
    }

    playing.lastOrNull()?.let { v ->
        androidx.compose.runtime.key(v.id + playing.size) {
            WatchPage(
                v,
                onClose = { playing.removeAt(playing.lastIndex) },
                onOpen = open,
                onChannel = openChannel,
                onDownload = { saving = it },
                onMenu = { menu = it },
            )
        }
    }

    menu?.let { v ->
        val later = Tube.inLater(v.id)
        val inHistory = Tube.history.value.any { it.id == v.id }
        Sheet(
            title = v.title,
            options = listOfNotNull(
                Option("download", "Download", "To Grabber!/YouTube", icon = L.drawable.lucide_ic_download, iconTint = Ink.download),
                Option("later", if (later) "Remove from Watch Later" else "Save to Watch Later", icon = L.drawable.lucide_ic_clock),
                Option("share", "Share", icon = L.drawable.lucide_ic_share_2),
                v.channelUrl?.let { Option("channel", "Go to ${v.channel.ifBlank { "channel" }}", icon = L.drawable.lucide_ic_user) },
                if (inHistory) Option("forget", "Remove from History", icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger) else null,
            ),
            selected = null,
            onPick = {
                menu = null
                when (it) {
                    "download" -> saving = v
                    "later" -> Tube.toggleLater(v)
                    "share" -> runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), v.title))
                    }
                    "channel" -> v.channelUrl?.let { url -> openChannel(url, v.channel) }
                    "forget" -> Tube.forget(v.id)
                }
            },
            onDismiss = { menu = null },
        )
    }

    saving?.let { v -> SaveSheet(v.url, v.title, v.thumb, tube = v, onDone = { saving = null }) }
}

/**
 * The Tube tab's only bar (the owner's choice: very basic): a thin search mark, a roomy, quiet input, and a thin
 * sign-in mark that opens You — subscriptions, history, downloads, Watch Later. No account: everything is kept here.
 */
@Composable
private fun TubeHeader(typed: String, onTyped: (String) -> Unit, onSubmit: () -> Unit, onLogin: () -> Unit) {
    // One container for the whole bar, barely rounded; the input has no box of its own, only its green cursor.
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
            .height(58.dp).frosted(RoundedCornerShape(8.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tap(onSubmit, shape = CircleShape) { Lc(R.drawable.ic_search_thin, Modifier.padding(8.dp), tint = Ink.text, size = 28.dp) }
        BareInput(typed, onTyped, "Search YouTube", Modifier.weight(1f).padding(horizontal = 6.dp), onSubmit = onSubmit)
        if (typed.isNotEmpty()) Tap({ onTyped("") }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(6.dp), tint = Ink.muted, size = 18.dp) }
        Tap(onLogin, shape = CircleShape) { Lc(R.drawable.ic_user_thin, Modifier.padding(8.dp), tint = Ink.text, size = 28.dp) }
    }
}

/** The five parts as words spread across the width, the chosen one bold with the accent's dot. */
@Composable
private fun SectionRow(on: TubeSection, onPick: (TubeSection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TubeSection.entries.forEach { s ->
            val chosen = s == on
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onPick(s) }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.label, style = Type.label.copy(fontSize = 14.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium, color = if (chosen) Ink.text else Ink.muted), maxLines = 1)
                if (chosen) Box(Modifier.padding(start = 5.dp).size(6.dp).clip(CircleShape).background(Ink.accent))
            }
        }
    }
}

/**
 * A list read from YouTube: a spinner while it comes, a short message and ↻ if it fails, [empty] when there's
 * nothing. [load] runs again when [key] changes or ↻ is pressed.
 */
@Composable
private fun Loaded(key: Any, load: suspend (fresh: Boolean) -> List<TubeVideo>, empty: String, header: LazyListScope.() -> Unit = {}, row: @Composable (TubeVideo) -> Unit) {
    var videos by remember(key) { mutableStateOf<List<TubeVideo>?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var round by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, round) {
        error = null
        runCatching { load(round > 0) }.onSuccess { videos = it }.onFailure { error = tubeError(it) }
    }
    val list = videos
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 190.dp)) {
        header()
        when {
            error != null && list == null -> item { Retry(error!!) { round++ } }
            list == null -> item { Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { Spinner(size = 40.dp) } }
            list.isEmpty() -> item { Note(empty, Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) }
            else -> {
                items(list, key = { it.id }) { row(it) }
                item { Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Tap({ round++ }, shape = CircleShape) { Lc(L.drawable.lucide_ic_refresh_cw, Modifier.padding(12.dp), tint = Ink.muted, size = 20.dp) } } }
            }
        }
    }
}

/** A failed read: what went wrong in one line, and ↻. */
@Composable
private fun Retry(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = Type.body.copy(color = Ink.muted), maxLines = 2)
        Tap(onRetry, shape = CircleShape) { Lc(L.drawable.lucide_ic_refresh_cw, Modifier.padding(10.dp), tint = Ink.text, size = 20.dp) }
    }
}

@Composable
private fun HomeFeed(onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit) {
    val subs by Tube.subscriptions.collectAsState()
    Loaded(subs.size, { Tube.home(it) }, "Nothing from YouTube right now.") { VideoCard(it, onOpen, onMenu, onChannel) }
}

/** The subscribed channels' pictures in a row, then their newest videos. */
@Composable
private fun SubsFeed(onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit) {
    val subs by Tube.subscriptions.collectAsState()
    if (subs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Note("No subscriptions yet. Tap Subscribe on a channel, or bring yours over from YouTube.")
            ImportButton(Modifier.padding(top = 16.dp))
        }
        return
    }
    Loaded(subs.map { it.url }, { Tube.subscriptionFeed(it) }, "No new videos from your channels.", header = {
        item {
            LazyRow(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(subs, key = { it.url }) { ch ->
                    Column(Modifier.width(64.dp).clickable { onChannel(ch.url, ch.name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        ChannelAvatar(ch.avatar, ch.name, 54.dp)
                        Text(ch.name, Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }) { VideoCard(it, onOpen, onMenu, onChannel) }
}

@Composable
private fun HistoryList(onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit) {
    val history by Tube.history.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    if (history.isEmpty()) {
        Note("Videos you watch show up here.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 190.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${history.size} watched", Modifier.weight(1f), style = Type.meta)
                Tap({ clearing = true }, shape = RoundedCornerShape(6.dp)) { Text("Clear All", Modifier.padding(8.dp), style = Type.label.copy(color = Ink.accent)) }
            }
        }
        items(history, key = { it.id }) { SmallVideoRow(it, onOpen, onMenu) }
    }
    if (clearing) Sheet(
        title = "Clear your Tube history?",
        options = listOf(Option(true, "Clear History", icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger), Option(false, "Keep It", icon = L.drawable.lucide_ic_check)),
        selected = null,
        onPick = { if (it) Tube.clearHistory(); clearing = false },
        onDismiss = { clearing = false },
    )
}

/** YouTube downloads: those still coming, and those saved in Grabber!/YouTube and Grabber!/Audio. */
@Composable
private fun TubeDownloads(watch: Watch) {
    val live by Downloads.live.collectAsState()
    val finished by Downloads.finished.collectAsState()
    val tube = setOf(Downloads.TUBE_KIND, Downloads.TUBE_AUDIO)
    val media = setOf(Downloads.MEDIA_KIND, Downloads.MEDIA_AUDIO)
    // Tube's own, and those yt-dlp saved from YouTube (MP3 and the other formats).
    val coming = live.filter { it.job.kind in tube || (it.job.kind in media && "youtube.com" in it.job.uri) }
    val saved = finished.filter { it.kind in tube || (it.kind in media && it.path.contains("/YouTube/") || it.kind == Downloads.MEDIA_AUDIO && it.poster?.contains("ytimg") == true) }.sortedByDescending { it.at }
    var deleting by remember { mutableStateOf<Finished?>(null) }
    if (coming.isEmpty() && saved.isEmpty()) {
        Note("Videos you download show up here, and in Files under Grabber!/YouTube.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 190.dp)) {
        items(coming, key = { "live-" + it.job.uri }) { row ->
            val f = row.files.firstOrNull()
            val pct = if (f != null && f.size > 0) (f.done * 100 / f.size).toInt() else 0
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(row.job.poster, row.job.label, Modifier.width(132.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(row.job.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(row.error ?: "${row.state.label}  ·  $pct%  ·  ${row.job.quality ?: ""}", Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 1)
                }
                Tap({ Downloads.cancel(row.job.uri) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
            }
        }
        items(saved, key = { "done-" + it.path }) { f ->
            Tap({ watch.playFile(f.path, f.label, null, f.poster) }, Modifier.fillMaxWidth().padding(bottom = 12.dp), onLongClick = { deleting = f }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(f.poster, f.label, Modifier.width(132.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(f.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${if (f.kind == Downloads.TUBE_AUDIO || f.kind == Downloads.MEDIA_AUDIO) "Audio" else "Video"}  ·  ${bytes(f.size)}", Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 12.sp))
                    }
                    Tap({ deleting = f }, shape = CircleShape) { Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
                }
            }
        }
    }
    deleting?.let { f ->
        Sheet(
            title = "Delete ${f.label}?",
            options = listOf(Option(true, "Delete from this phone", bytes(f.size), icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger), Option(false, "Keep It", icon = L.drawable.lucide_ic_check)),
            selected = null,
            onPick = { if (it) Downloads.delete(f); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** You: Watch Later, the channels subscribed to (to open or leave), and bringing subscriptions over. */
@Composable
private fun YouPage(onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit) {
    val later by Tube.later.collectAsState()
    val subs by Tube.subscriptions.collectAsState()
    val history by Tube.history.collectAsState()
    var leaving by remember { mutableStateOf<TubeChannel?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 190.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Count(history.size, "Watched")
                Count(later.size, "Watch Later")
                Count(subs.size, "Subscriptions")
            }
        }
        item { Text("Watch Later", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontWeight = FontWeight.Bold)) }
        if (later.isEmpty()) item { Note("Save videos from their ⋯ menu to watch them later.", Modifier.padding(bottom = 18.dp)) }
        items(later, key = { "later-" + it.id }) { SmallVideoRow(it, onOpen, onMenu) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Subscriptions", Modifier.weight(1f), style = Type.title.copy(fontWeight = FontWeight.Bold))
            }
            ImportButton(Modifier.padding(bottom = 14.dp))
        }
        items(subs, key = { "sub-" + it.url }) { ch ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChannel(ch.url, ch.name) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(ch.avatar, ch.name, 40.dp)
                Text(ch.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tap({ leaving = ch }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(10.dp), tint = Ink.muted, size = 18.dp) }
            }
        }
    }
    leaving?.let { ch ->
        Sheet(
            title = "Unsubscribe from ${ch.name}?",
            options = listOf(Option(true, "Unsubscribe", icon = L.drawable.lucide_ic_x, iconTint = Ink.danger), Option(false, "Stay Subscribed", icon = L.drawable.lucide_ic_check)),
            selected = null,
            onPick = { if (it) Tube.toggleSubscription(ch); leaving = null },
            onDismiss = { leaving = null },
        )
    }
}

@Composable
private fun Count(n: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = Type.title.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp))
        Text(label, style = Type.meta.copy(fontSize = 12.sp))
    }
}

/**
 * Subscriptions brought over: Google Takeout's subscriptions.csv (YouTube and YouTube Music → subscriptions) or a
 * NewPipe export (.json), picked from the phone's files.
 */
@Composable
private fun ImportButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var said by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        val found = parseSubscriptions(text)
        said = if (found.isEmpty()) "No channels in that file. Pick subscriptions.csv from Google Takeout, or a NewPipe export."
        else Tube.importSubscriptions(found).let { n -> if (n == 0) "All ${found.size} channels were already here." else "Added $n channels." }
    }
    Column(modifier) {
        Button("Import Subscriptions", { pick.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) }, icon = L.drawable.lucide_ic_upload, compact = true)
        said?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta) }
        Text("From Google Takeout (subscriptions.csv) or a NewPipe export.", Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp))
    }
}

/* ---------------------------------------------------------------------- cards */

/** A channel's round picture, or its first letter on a tinted disc. */
@Composable
private fun ChannelAvatar(url: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Ink.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = Type.label.copy(color = Ink.accent, fontWeight = FontWeight.Bold))
        if (url != null) Artwork(url, name, Modifier.fillMaxSize())
    }
}

/** A thumbnail, 16:9 and rounded, with its length (or LIVE) in the corner. */
@Composable
private fun Thumb(url: String?, name: String, modifier: Modifier = Modifier, seconds: Long = 0, live: Boolean = false, corner: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(corner)).background(Ink.surface)) {
        Artwork(url, name, Modifier.fillMaxSize())
        val tag = if (live) "LIVE" else clockText(seconds)
        if (tag.isNotEmpty()) Text(
            tag,
            Modifier.align(Alignment.BottomEnd).padding(8.dp).background(if (live) Color(0xFFCC0000) else Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp),
        )
    }
}

/** The feed's card, as the screenshot: a full-width thumbnail, then the channel's picture, two lines of title and ⋯. */
@Composable
private fun VideoCard(v: TubeVideo, onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Tap({ onOpen(v) }, Modifier.fillMaxWidth(), THUMB, onLongClick = { onMenu(v) }) { Thumb(v.thumb, v.title, Modifier.fillMaxWidth(), v.seconds, v.live, corner = 14.dp) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Box(Modifier.padding(start = 4.dp, end = 12.dp).clip(CircleShape).clickable { v.channelUrl?.let { onChannel(it, v.channel) } }) { ChannelAvatar(v.avatar, v.channel, 40.dp) }
            Column(Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(v) }) {
                Text(v.title, style = Type.title.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tubeLine(v), Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Tap({ onMenu(v) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_ellipsis_vertical, Modifier.padding(6.dp), tint = Ink.text, size = 20.dp) }
        }
    }
}

/** A smaller row (History, Watch Later, Up Next): thumbnail on the left, words beside it. */
@Composable
private fun SmallVideoRow(v: TubeVideo, onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit) {
    Tap({ onOpen(v) }, Modifier.fillMaxWidth().padding(bottom = 12.dp), RoundedCornerShape(10.dp), onLongClick = { onMenu(v) }) {
        Row {
            Thumb(v.thumb, v.title, Modifier.width(160.dp), v.seconds, v.live)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(v.title, style = Type.label.copy(fontWeight = FontWeight.SemiBold, lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tubeLine(v), Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Tap({ onMenu(v) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_ellipsis_vertical, Modifier.padding(4.dp), tint = Ink.muted, size = 18.dp) }
        }
    }
}

/** A channel or playlist among search results. */
@Composable
private fun LinkRow(link: TubeLink, onOpen: () -> Unit) {
    Tap(onOpen, Modifier.fillMaxWidth().padding(bottom = 14.dp), RoundedCornerShape(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (link.channel) Box(Modifier.width(160.dp), contentAlignment = Alignment.Center) { ChannelAvatar(link.thumb, link.name, 76.dp) }
            else Box(Modifier.width(160.dp)) {
                Thumb(link.thumb, link.name, Modifier.fillMaxWidth())
                Lc(L.drawable.lucide_ic_list_video, Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(5.dp)).padding(4.dp), tint = Color.White, size = 16.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(link.name, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(if (link.channel) "Channel" else "Playlist", link.line).filter { it.isNotBlank() }.joinToString(" • "), Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 1)
            }
        }
    }
}

/** A page's top: ← and its name. */
@Composable
private fun BackBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tap(onBack, shape = CircleShape) { Lc(L.drawable.lucide_ic_arrow_left, Modifier.padding(10.dp), tint = Ink.text, size = 22.dp) }
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/**
 * Pages of videos (and channels and playlists) that keep coming as the list nears its end: a search, a channel's
 * tab, a playlist. [first] reads page one.
 */
@Composable
private fun Paged(
    key: Any,
    first: suspend () -> TubePage,
    empty: String,
    onOpen: (TubeVideo) -> Unit,
    onMenu: (TubeVideo) -> Unit,
    onChannel: (String, String) -> Unit,
    onPlaylist: (String, String) -> Unit,
    small: Boolean = false,
    header: LazyListScope.() -> Unit = {},
) {
    val videos = remember(key) { mutableStateListOf<TubeVideo>() }
    val links = remember(key) { mutableStateListOf<TubeLink>() }
    var next by remember(key) { mutableStateOf<(suspend () -> TubePage)?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var round by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, round) {
        loading = true
        error = null
        runCatching { first() }.onSuccess {
            videos.clear(); links.clear()
            videos += it.videos; links += it.links; next = it.next
        }.onFailure { error = tubeError(it) }
        loading = false
    }
    val state = rememberLazyListState()
    val nearEnd by remember(key) { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 4 } ?: false } }
    LaunchedEffect(nearEnd, next, loading) {
        val more = next ?: return@LaunchedEffect
        if (!nearEnd || loading) return@LaunchedEffect
        loading = true
        runCatching { more() }.onSuccess { page ->
            val seen = videos.map { it.id }.toSet()
            videos += page.videos.filter { it.id !in seen }
            links += page.links
            next = page.next
        }.onFailure { next = null }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 190.dp)) {
        header()
        items(links, key = { "link-" + it.url }) { l -> LinkRow(l) { if (l.channel) onChannel(l.url, l.name) else onPlaylist(l.url, l.name) } }
        items(videos, key = { it.id }) { if (small) SmallVideoRow(it, onOpen, onMenu) else VideoCard(it, onOpen, onMenu, onChannel) }
        when {
            error != null -> item { Retry(error!!) { round++ } }
            loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner(size = 34.dp) } }
            videos.isEmpty() && links.isEmpty() -> item { Note(empty, Modifier.padding(vertical = 12.dp)) }
        }
    }
}

/** A channel: banner, picture, name, subscribers, Subscribe; its Videos and Playlists. */
@Composable
private fun ChannelPage(url: String, name: String, onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onPlaylist: (String, String) -> Unit, onBack: () -> Unit) {
    var page by remember(url) { mutableStateOf<TubeChannelPage?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var round by remember(url) { mutableIntStateOf(0) }
    var tab by rememberSaveable(url) { mutableIntStateOf(0) }
    val subs by Tube.subscriptions.collectAsState()
    LaunchedEffect(url, round) {
        error = null
        runCatching { Tube.channel(url) }.onSuccess { page = it }.onFailure { error = tubeError(it) }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(page?.channel?.name ?: name, onBack)
        val p = page
        when {
            p == null && error != null -> Box(Modifier.padding(horizontal = 16.dp)) { Retry(error!!) { round++ } }
            p == null -> Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { Spinner(size = 40.dp) }
            else -> {
                val handler = if (tab == 1) p.playlists else p.videos
                val header: LazyListScope.() -> Unit = {
                    item {
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            p.banner?.let { Artwork(it, p.channel.name, Modifier.fillMaxWidth().aspectRatio(5f).clip(RoundedCornerShape(12.dp))) }
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                ChannelAvatar(p.channel.avatar, p.channel.name, 64.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(p.channel.name, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (p.subscribers >= 0) Text("${compactCount(p.subscribers)} subscribers", Modifier.padding(top = 2.dp), style = Type.meta)
                                }
                            }
                            SubscribeButton(p.channel, subs.isNotEmpty() && Tube.subscribed(p.channel.url), Modifier.padding(top = 14.dp).fillMaxWidth())
                            if (p.playlists != null) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                listOf("Videos", "Playlists").forEachIndexed { i, label ->
                                    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { tab = i }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(label, style = Type.title.copy(fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium, color = if (tab == i) Ink.text else Ink.muted))
                                        if (tab == i) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(Ink.accent))
                                    }
                                }
                            }
                        }
                    }
                }
                if (handler == null) LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    header()
                    item { Note("This channel has nothing here.") }
                } else Paged(
                    url + tab, { Tube.channelTab(handler, p.channel) }, "This channel has nothing here.", onOpen, onMenu,
                    onChannel = { _, _ -> }, onPlaylist = onPlaylist, small = true, header = header,
                )
            }
        }
    }
}

/** Subscribed, as a switch (the owner's choice over a Subscribe button). */
@Composable
private fun SubscribeButton(ch: TubeChannel, on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterEnd) { LabeledToggle("Subscribed", on, { Tube.toggleSubscription(ch) }) }
}

@Composable
private fun PlaylistPage(url: String, name: String, onOpen: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit, onBack: () -> Unit) {
    var title by remember(url) { mutableStateOf(name) }
    Column(Modifier.fillMaxSize()) {
        BackBar(title, onBack)
        Paged(url, { Tube.playlist(url).let { (n, page) -> if (n.isNotBlank()) title = n; page } }, "This playlist is empty.", onOpen, onMenu, onChannel, onPlaylist = { _, _ -> }, small = true)
    }
}

/* ---------------------------------------------------------------------- saving */

/* ---------------------------------------------------------------------- the video page */

/**
 * A video's page, over everything (its own window, so full screen covers the tab bar): the player on top, then the
 * title, numbers, actions, the channel with Subscribe, the description and Up Next. Full screen turns the phone
 * sideways without reloading the video.
 */
@Composable
private fun WatchPage(start: TubeVideo, onClose: () -> Unit, onOpen: (TubeVideo) -> Unit, onChannel: (String, String) -> Unit, onDownload: (TubeVideo) -> Unit, onMenu: (TubeVideo) -> Unit) {
    var details by remember(start.id) { mutableStateOf<TubeDetails?>(null) }
    var error by remember(start.id) { mutableStateOf<String?>(null) }
    var round by remember(start.id) { mutableIntStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var quality by remember(start.id) { mutableStateOf<TubeQuality?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val subs by Tube.subscriptions.collectAsState()
    val later by Tube.later.collectAsState()
    val activity = LocalActivity.current
    val context = LocalContext.current

    LaunchedEffect(start.id, round) {
        error = null
        runCatching { Tube.details(start.id) }.onSuccess { d ->
            details = d
            // 720p or the nearest below: sharp on a phone, half 1080p's data. Live: its HLS.
            quality = d.qualities.firstOrNull { it.hls } ?: d.qualities.filter { it.height in 1..720 }.maxByOrNull { it.height } ?: d.qualities.firstOrNull()
            Tube.watched(d.video)
        }.onFailure { error = tubeError(it) }
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(full) {
            activity?.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                val c = WindowInsetsControllerCompat(w, w.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (full) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
                c.isAppearanceLightStatusBars = false
            }
            onDispose { }
        }
        DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
        BackHandler { if (full) full = false else onClose() }

        val d = details
        Column(Modifier.fillMaxSize().background(if (full) Color.Black else Ink.bg).then(if (full) Modifier else Modifier.grain())) {
            Box(
                (if (full) Modifier.fillMaxSize() else Modifier.statusBarsPadding().fillMaxWidth().aspectRatio(16f / 9f)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val q = quality
                when {
                    q != null && d != null -> TubePlayer(d.video, q, onEnded = { d.related.firstOrNull()?.let(onOpen) })
                    error != null -> Artwork(start.thumb, start.title, Modifier.fillMaxSize())
                    else -> {
                        Artwork(start.thumb, start.title, Modifier.fillMaxSize())
                        Spinner(size = 40.dp)
                    }
                }
                // Over the picture: back (or out of full screen), quality, full screen.
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tap({ if (full) full = false else onClose() }, shape = CircleShape) {
                        Lc(if (full) L.drawable.lucide_ic_arrow_left else L.drawable.lucide_ic_chevron_down, Modifier.padding(10.dp), tint = Color.White, size = 22.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (d != null && d.qualities.size > 1) Tap({ choosing = true }, shape = RoundedCornerShape(6.dp)) {
                        Text(quality?.label ?: "", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    }
                    Tap({ full = !full }, shape = CircleShape) {
                        Lc(if (full) L.drawable.lucide_ic_minimize else L.drawable.lucide_ic_maximize, Modifier.padding(10.dp), tint = Color.White, size = 20.dp)
                    }
                }
            }
            if (!full) {
                val v = d?.video ?: start
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { more = !more }) {
                            Text(v.title, style = Type.title.copy(fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp), maxLines = if (more) 6 else 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(v.views.takeIf { it >= 0 }?.let { "${compactCount(it)} views" }, v.ago).joinToString(" • ") + if (d != null && d.description.isNotBlank()) "  ...more" else "", Modifier.padding(top = 6.dp), style = Type.meta.copy(fontSize = 13.sp))
                        }
                    }
                    item {
                        // Actions spread across the width.
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            val saved = later.any { it.id == v.id }
                            TubeAction(L.drawable.lucide_ic_heart, d?.likes?.takeIf { it >= 0 }?.let { compactCount(it) } ?: "Likes") {}
                            TubeAction(L.drawable.lucide_ic_clock, if (saved) "Saved" else "Later", on = saved) { Tube.toggleLater(v) }
                            TubeAction(L.drawable.lucide_ic_download, "Download") { onDownload(v) }
                            TubeAction(L.drawable.lucide_ic_share_2, "Share") {
                                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), v.title)) }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { v.channelUrl?.let { onChannel(it, v.channel) } }, verticalAlignment = Alignment.CenterVertically) {
                                ChannelAvatar(v.avatar, v.channel, 40.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(v.channel, style = Type.label.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    d?.subscribers?.takeIf { it >= 0 }?.let { Text("${compactCount(it)} subscribers", style = Type.meta.copy(fontSize = 12.sp)) }
                                }
                            }
                            v.channelUrl?.let { url ->
                                SubscribeButton(TubeChannel(url, v.channel, v.avatar), subs.isNotEmpty() && Tube.subscribed(url), Modifier.width(132.dp))
                            }
                        }
                    }
                    if (d != null && d.description.isNotBlank()) item {
                        Text(
                            androidx.core.text.HtmlCompat.fromHtml(d.description, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim(),
                            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(Ink.text.copy(alpha = 0.06f))
                                .clickable { more = !more }.padding(12.dp),
                            style = Type.body.copy(fontSize = 14.sp), maxLines = if (more) 400 else 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    error?.let { e -> item { Retry(e) { round++ } } }
                    if (d != null && d.related.isNotEmpty()) {
                        item { Text("Up Next", Modifier.padding(top = 20.dp, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold)) }
                        items(d.related, key = { "rel-" + it.id }) { SmallVideoRow(it, onOpen, onMenu) }
                    }
                }
            }
        }
    }

    if (choosing) details?.let { d ->
        Sheet(
            title = "Quality",
            options = d.qualities.map { Option(it, it.label, if (it.audio != null) null else if (it.hls) "Adapts to the connection" else "One file") },
            selected = quality,
            onPick = { quality = it; choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/** One of the video page's actions: an icon over a word. */
@Composable
private fun TubeAction(icon: Int, label: String, on: Boolean = false, onClick: () -> Unit) {
    Tap(onClick, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Lc(icon, tint = if (on) Ink.accent else Ink.text, size = 22.dp)
            Text(label, Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 12.sp, color = if (on) Ink.accent else Ink.text))
        }
    }
}

/**
 * The video, in Media3 as the trailers play ([TrailerControls] over it): picture-only and sound joined, or HLS.
 * Starts where it was left; a change of quality carries on at the same moment. [onEnded]: the next video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TubePlayer(v: TubeVideo, q: TubeQuality, onEnded: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(v.id) { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() }
    var ratio by remember(v.id) { mutableStateOf(16f / 9f) }
    var shown by remember(v.id) { mutableStateOf(false) }
    val ended = androidx.compose.runtime.rememberUpdatedState(onEnded)
    LaunchedEffect(q) {
        val at = if (player.currentPosition > 0) player.currentPosition else Tube.spot(v.id) * 1000
        fun source(url: String, hls: Boolean): androidx.media3.exoplayer.source.MediaSource {
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(Trailers.userAgent(url)).setAllowCrossProtocolRedirects(true)
            val item = androidx.media3.common.MediaItem.fromUri(url)
            return if (hls) androidx.media3.exoplayer.hls.HlsMediaSource.Factory(http).createMediaSource(item)
            else androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(http).createMediaSource(item)
        }
        val video = source(q.video, q.hls)
        player.setMediaSource(q.audio?.let { androidx.media3.exoplayer.source.MergingMediaSource(video, source(it, false)) } ?: video, at)
        player.prepare()
        player.playWhenReady = true
    }
    // Where it is, kept every few seconds for History's resume.
    LaunchedEffect(v.id) {
        while (true) {
            delay(5000)
            val length = player.duration.takeIf { it > 0 } ?: 0L
            if (!v.live && player.currentPosition > 0) Tube.setSpot(v.id, player.currentPosition / 1000, length / 1000)
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onRenderedFirstFrame() {
                shown = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    Tube.setSpot(v.id, 0, 0)
                    ended.value()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val length = player.duration.takeIf { it > 0 } ?: 0L
            if (!v.live && player.currentPosition > 0) Tube.setSpot(v.id, player.currentPosition / 1000, length / 1000)
            player.removeListener(listener)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { c -> android.view.TextureView(c).also { player.setVideoTextureView(it) } }, modifier = Modifier.aspectRatio(ratio))
        if (shown) TrailerControls(remember(player) { ExoPlayback(player) }) else Spinner(size = 40.dp)
    }
}

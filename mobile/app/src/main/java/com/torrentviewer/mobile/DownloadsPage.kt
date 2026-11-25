package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import com.torrentviewer.core.grain
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago
import com.torrentviewer.core.bytes
import com.torrentviewer.core.absoluteNumber
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.left
import com.torrentviewer.core.pickTag
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.speed
import com.composables.icons.lucide.R as L

/**
 * A release to keep, labelled the way History labels a stream of it.
 *
 * A whole season (or series) pack picked without an episode is downloaded as its torrent when the link names it (a
 * debrid link carries the torrent's hash): a link is only ever one file, while the torrent holds every episode — one
 * card with the episodes inside, each saved to Video/Shows/<show>/Season N. Packs with no torrent behind them stay links.
 */
fun downloadJob(hit: Hit, from: Title?, episode: Episode? = null, foundAt: Long = System.currentTimeMillis()): DownloadJob {
    val magnet = if (episode == null && hit.scope != com.torrentviewer.core.Scope.EPISODE && from?.kind != "movie" && hit.direct) magnetOf(hit) else null
    if (magnet != null) return DownloadJob(
        uri = magnet, title = hit.name, titleName = from?.title, poster = from?.poster, imdb = from?.imdb, kind = from?.kind ?: "tv",
        source = hit.addon.ifEmpty { null }, linkName = hit.name, size = hit.size,
        // One season named: its episodes; several, or none named (a complete series): every episode ([WHOLE_PACK]).
        season = com.torrentviewer.core.seasonsIn(hit.name).singleOrNull() ?: WHOLE_PACK,
    )
    return downloadLinkJob(hit, from, episode, foundAt)
}

/** A pack's job wanting every episode in it, whatever its season: not a real season number. */
const val WHOLE_PACK = -1

private fun downloadLinkJob(hit: Hit, from: Title?, episode: Episode?, foundAt: Long) = DownloadJob(
    fetchedAt = if (hit.direct) foundAt else 0,
    link = hit.direct,
    headers = if (hit.direct) hit.headers else emptyMap(),
    playlist = hit.direct && isPlaylist(hit),
    // An "Auto" playlist (several resolutions in one) is saved at the preferred resolution, not its biggest.
    quality = hit.quality ?: com.torrentviewer.core.SettingsStore.value.value.dlQuality.takeIf { hit.direct && isPlaylist(hit) && it.isNotEmpty() },
    wanted = hit.quality,
    source = hit.addon.ifEmpty { null },
    linkName = hit.name,
    size = hit.size,
    epSeason = episode?.season,
    epNumber = episode?.episode,
    uri = hit.uri,
    title = hit.name,
    titleName = if (episode != null && from != null) "${from.title} · ${episodeTag(episode.season, episode.episode)}" else from?.title,
    poster = from?.poster,
    imdb = from?.imdb,
    kind = from?.kind,
    tags = listOfNotNull(episode?.let { pickTag(it.season, it.episode, from?.let { t -> absoluteNumber(t.episodes, it.season, it.episode) }) }),
)

/** What is downloading, and what is kept on this phone to watch without a connection. */
@Composable
fun DownloadsScreen(watch: Watch, onBack: (() -> Unit)?, header: (@Composable () -> Unit)? = null) {
    val live by Downloads.live.collectAsState()
    val finished by Downloads.finished.collectAsState()
    var cancelling by remember { mutableStateOf<LiveDownload?>(null) }
    var swapping by remember { mutableStateOf<LiveDownload?>(null) }
    /** A download's card tapped: its every action ([DownloadOptionsSheet]). */
    var options by remember { mutableStateOf<LiveDownload?>(null) }
    /** "On this phone" opened by its chevron. */
    var showSaved by rememberSaveable { mutableStateOf(false) }
    /** Open, and not locked since: the lock timing out or (Immediately) another page shuts it again. */
    val savedShown = showSaved  // the whole page is behind the lock now
    var deleting by remember { mutableStateOf<Finished?>(null) }
    var binOpen by remember { mutableStateOf(false) }
    val binItems by Downloads.bin.collectAsState()
    /** A film's or episode's ⓘ tapped: its story. */
    var story by remember { mutableStateOf<Finished?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    /** Downloads swiped off the list, held so Undo can bring them back before they're really stopped. */
    val held = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = EDGE, end = EDGE, bottom = NAV_CLEARANCE)) {
        // Inside the Library, its heading and tabs; opened on its own, a heading with a close.
        item {
            if (header != null) Column { header() }
            else Heading("Downloads") {
                if (onBack != null) Tap(onBack, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(8.dp), tint = Ink.muted, size = 22.dp) }
            }
        }
        if (live.isEmpty() && finished.isEmpty()) item {
            // Centred on an upright phone.
            val centred = isPortraitPhone()
            Text(
                "Nothing downloaded yet. Download from a title's page, and it plays here without a connection.",
                Modifier.fillMaxWidth().padding(horizontal = if (centred) 12.dp else 0.dp),
                style = Type.body.copy(color = Ink.faint, textAlign = if (centred) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start),
            )
        }
        if (live.isNotEmpty()) item {
            Label("Downloading")
            Text("Swipe a row — right to pause or resume, left to remove", Modifier.padding(top = 2.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.faint))
            // All of them at once: pause, carry on, or stop and delete (asked first).
            SpreadRow(Modifier.padding(bottom = 12.dp)) {
                Chip("Pause All", false, onClick = { live.forEach { if (it.state != DlState.PAUSED && it.state != DlState.FAILED) Downloads.pause(it.job.uri) } }, icon = R.drawable.ic_pause_bars, compact = true)
                Chip("Resume All", false, onClick = {
                    live.forEach { if (it.state == DlState.PAUSED) Downloads.resume(it.job.uri) else if (it.state == DlState.FAILED) Downloads.retry(it.job.uri) }
                }, icon = L.drawable.lucide_ic_play, compact = true)
                Chip("Delete All", false, onClick = { deletingAll = true }, icon = L.drawable.lucide_ic_trash_2, compact = true)
            }
        }
        // Newest first (the owner's choice): a download just sent turns up at the top. Swiped-off rows are hidden until
        // the hold runs ([held]).
        items(live.asReversed().filter { it.job.uri !in held }, key = { "live-" + it.job.uri }) { row ->
            val running = row.state != DlState.PAUSED && row.state != DlState.FAILED
            SwipeRow(
                backing = DL_CARD, inset = 8.dp,
                // Right (runs on the swipe): pause a running one, else resume (or retry a failed one). Left holds open to a
                // red ✕ that, when tapped, takes it off the list — hidden at once, held so Undo can bring it back.
                onRight = { if (running) Downloads.pause(row.job.uri) else if (row.state == DlState.FAILED) Downloads.retry(row.job.uri, byHand = true) else Downloads.resume(row.job.uri) },
                rightIcon = if (running) R.drawable.ic_pause_bars else L.drawable.lucide_ic_play,
                rightTint = if (running) Ink.warn else Ink.good,
                onLeft = {
                    held.add(row.job.uri)
                    UndoHold.start("Removed ${row.job.label}", undo = { held.remove(row.job.uri) }, commit = { Downloads.cancel(row.job.uri); held.remove(row.job.uri) })
                },
                leftIcon = L.drawable.lucide_ic_x, leftTint = Ink.danger,
            ) {
                ActiveRow(
                    row,
                    onPause = { Downloads.pause(row.job.uri) },
                    onResume = { Downloads.resume(row.job.uri) },
                    onRetry = { Downloads.retry(row.job.uri, byHand = true) },
                    onCancel = { cancelling = row },
                    onSwap = { swapping = row },
                    onWanted = { index, on -> Downloads.setWanted(row.job.uri, index, on) },
                    onOrder = { Downloads.setOrder(row.job.uri, it) },
                    onOptions = { options = row },
                )
            }
        }
        // What's on the phone, folded away until its chevron opens it (the owner's choice).
        if (finished.isNotEmpty()) item {
            // Behind the lock (Settings → Lock): the chevron asks for the pin first, the list opening once it's right.
            Tap({ showSaved = !showSaved }, Modifier.fillMaxWidth(), RoundedCornerShape(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(start = 14.dp)) { Label("On this phone  ·  ${bytes(finished.sumOf { it.size })}") }
                    // The Bin, when it holds anything: restore or erase deleted files (the owner's ask, as Grabber's).
                    if (binItems.isNotEmpty()) Tap({ binOpen = true }, shape = CircleShape) {
                        Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(6.dp), tint = Ink.muted, size = 22.dp)
                    }
                    Lc(if (savedShown) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, Modifier.padding(end = 14.dp), tint = Ink.muted, size = 28.dp)
                }
            }
        }
        if (savedShown && finished.isNotEmpty()) item {
            Text("Swipe a file — right to play, left for Bin or Delete", Modifier.padding(start = 14.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.faint))
        }
        // A show's episodes fold under one card; the most recently finished show first.
        val shows = if (!savedShown) emptyList() else finished.groupBy { it.imdb ?: it.label.substringBefore(" · ") }.values.sortedByDescending { g -> g.maxOf { it.at } }
        items(shows, key = { "done-" + it.first().path }) { group ->
            val play: (Finished) -> Unit = { f ->
                val from = f.imdb?.let { Title(it, f.kind ?: "movie", f.label.substringBefore(" · "), poster = f.poster) }
                watch.playFile(f.path, f.label, from, f.poster)
            }
            if (group.size == 1) {
                val f = group[0]
                // Right to play (runs on the swipe); left holds open to reveal a red delete (the bin-with-an-X), which
                // runs only when tapped — moved to the Bin, held with Undo. The Bin itself lives on the heading above.
                SwipeRow(
                    onRight = { play(f) }, rightIcon = L.drawable.lucide_ic_play, rightTint = Ink.good,
                    onLeft = { val e = Downloads.toBin(f); UndoHold.start("Deleted \"${f.label}\"", undo = { Downloads.restoreFromBin(e) }, commit = { Downloads.eraseBin(e) }) },
                    leftIcon = R.drawable.ic_trash_x, leftTint = Ink.danger,
                    backing = DL_CARD, inset = 8.dp,
                ) {
                    FinishedRow(f, f.label, poster = true, { play(f) }, { deleting = f }, onStory = storyOf(f) { story = it })
                }
            } else ShowGroup(group.sortedBy { it.label }, play, onStory = { story = it }) { deleting = it }
        }
    }

    // Asked first: what has arrived is thrown away.
    swapping?.let { row ->
        Sheet(
            title = "Use a different source for ${row.job.label}?",
            options = listOf(
                Option(true, "Find a different source", "Stops this one, deletes what arrived, and never picks it again", icon = L.drawable.lucide_ic_shuffle, iconTint = Ink.download),
                Option(false, "Keep this one", icon = L.drawable.lucide_ic_download),
            ),
            selected = null,
            onPick = {
                if (it) Downloads.differentSource(row.job.uri)
                swapping = null
            },
            onDismiss = { swapping = null },
        )
    }
    cancelling?.let { row ->
        Sheet(
            title = "Stop downloading ${row.job.label}?",
            options = listOf(Option(true, "Stop and delete what has arrived", "Files already saved stay on this phone", icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger), Option(false, "Keep downloading", icon = L.drawable.lucide_ic_download, iconTint = Ink.download)),
            selected = null,
            onPick = {
                if (it) Downloads.cancel(row.job.uri)
                cancelling = null
            },
            onDismiss = { cancelling = null },
        )
    }
    if (deletingAll) Sheet(
        title = "Stop and delete all ${live.size} downloads?",
        options = listOf(Option(true, "Stop and delete them all", "Files already saved stay on this phone", icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger), Option(false, "Keep downloading", icon = L.drawable.lucide_ic_download, iconTint = Ink.download)),
        selected = null,
        onPick = {
            if (it) live.forEach { row -> Downloads.cancel(row.job.uri) }
            deletingAll = false
        },
        onDismiss = { deletingAll = false },
    )
    story?.let { f -> FileStorySheet(f.file, f, emptyList()) { story = null } }
    options?.let { row ->
        // The live row, not the one tapped: its state moves on while the sheet is up.
        val now = live.find { it.job.uri == row.job.uri } ?: row
        DownloadOptionsSheet(now, onCancel = { options = null; cancelling = now }, onSwap = { options = null; swapping = now }, onDismiss = { options = null })
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // A saved file's choices: share it, move it to the Bin (recoverable), delete it for good, or keep it. Both deletes
    // are held for a few seconds with an Undo strip, and the file goes to the Bin first either way, so nothing is lost by
    // a slip (the owner's ask, as Grabber's).
    deleting?.let { f ->
        Sheet(
            title = f.label,
            options = listOf(
                Option("share", "Share", "Send the file to another app", icon = L.drawable.lucide_ic_share_2),
                Option("bin", "Move to Bin", "Keep it in the Bin to restore later", icon = L.drawable.lucide_ic_trash_2),
                Option("delete", "Delete permanently", bytes(f.size), icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger),
                Option("keep", "Keep it", icon = L.drawable.lucide_ic_check),
            ),
            selected = null,
            onPick = {
                when (it) {
                    "share" -> shareFile(context, f.file, f.label)
                    "bin" -> { val e = Downloads.toBin(f); UndoHold.start("Moved \"${f.label}\" to the Bin", undo = { Downloads.restoreFromBin(e) }, commit = {}) }
                    "delete" -> { val e = Downloads.toBin(f); UndoHold.start("Deleted \"${f.label}\"", undo = { Downloads.restoreFromBin(e) }, commit = { Downloads.eraseBin(e) }) }
                }
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    if (binOpen) BinSheet { binOpen = false }
}

/** Every download card's shape: well rounded, as Grabber's list is (the owner's choice). */
private val DL_CARD = RoundedCornerShape(14.dp)




/** A download card's badge in place of a poster: a film strip (or the batch layers for a pack) in the accent. */
@Composable
private fun FilmBadge(icon: Int = L.drawable.lucide_ic_film) {
    // A bare icon in the accent, no disc behind it (the owner's choice), a notch bigger.
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Lc(icon, tint = Ink.accent, size = 26.dp)
    }
}

/** A saved file: a card of its own ([card]), or a plain line inside its show's card. */
@Composable
private fun FinishedRow(f: Finished, name: String, poster: Boolean, onPlay: () -> Unit, onDelete: () -> Unit, onStory: (() -> Unit)? = null) {
    val card = poster
    Tap(onPlay, Modifier.fillMaxWidth().padding(bottom = 8.dp), if (card) DL_CARD else RoundedCornerShape(5.dp), onLongClick = onDelete) {
        Row(
            (if (card) Modifier.frosted(DL_CARD, CARD_FACE).padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card) FilmBadge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                Text("${bytes(f.size)}  ·  ${ago(f.at)}", Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp))
            }
            // A film's or an episode's story (not YouTube's or the Browser's files): its ⓘ, before Play.
            if (onStory != null) Tap(onStory, shape = CircleShape) { Lc(L.drawable.lucide_ic_info, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
            Lc(L.drawable.lucide_ic_play, Modifier.padding(start = 4.dp), tint = Ink.text, size = 20.dp)
            Tap(onDelete, shape = CircleShape) { Lc(L.drawable.lucide_ic_trash_2, Modifier.padding(10.dp), tint = Ink.muted, size = 20.dp) }
        }
    }
}

/** A show's downloaded episodes under one card, folded until tapped. */
@Composable
private fun ShowGroup(episodes: List<Finished>, onPlay: (Finished) -> Unit, onStory: (Finished) -> Unit = {}, onDelete: (Finished) -> Unit) {
    val first = episodes.first()
    var open by rememberSaveable(first.imdb ?: first.label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(DL_CARD).frosted(DL_CARD, CARD_FACE).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
        Tap({ open = !open }, Modifier.fillMaxWidth(), RoundedCornerShape(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A show's saved episodes are a batch: the layers badge (the owner's choice).
                FilmBadge(L.drawable.lucide_ic_layers)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(first.label.substringBefore(" · "), Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    Text("${episodes.size} episodes  ·  ${bytes(episodes.sumOf { it.size })}", Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp))
                }
                Lc(if (open) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, Modifier.padding(8.dp), tint = Ink.muted, size = 26.dp)
            }
        }
        if (open) Column(Modifier.padding(top = 8.dp)) {
            episodes.forEach { f -> FinishedRow(f, f.label.substringAfter(" · "), poster = false, { onPlay(f) }, { onDelete(f) }, onStory = storyOf(f, onStory)) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 6.dp, bottom = 10.dp), style = Type.overline.copy(fontSize = 12.sp))
}

/**
 * One download as a card. A pack folds its episodes away under a count; opened, they are
 * listed in episode order, each with its own progress and a circle to skip it or put it back.
 */
@Composable
private fun ActiveRow(row: LiveDownload, onPause: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onSwap: () -> Unit, onWanted: (Int, Boolean) -> Unit, onOrder: (Boolean) -> Unit, onOptions: () -> Unit = {}) {
    val pct = (row.ratio * 100).toInt()
    val pack = row.files.size > 1
    val wanted = row.files.filter { it.wanted }
    var open by rememberSaveable(row.job.uri) { mutableStateOf(false) }
    /** Seasons unfolded in a multi-season pack; the one downloading now starts open. */
    var openSeasons by remember(row.job.uri) {
        mutableStateOf(setOf(row.files.firstOrNull { it.wanted && !it.saved }?.let { parseEpisode(it.name)?.season }))
    }
    // How far it has come, as a fill across the whole card from the left (the owner's choice: no strip, a shorter card),
    // in a shade between the card's and the page's — under the grain, so it reads as part of the card. Eased, not jumping.
    val done by androidx.compose.animation.core.animateFloatAsState(if (row.ratio.isNaN()) 0f else row.ratio.coerceIn(0f, 1f), label = "filled")
    // The strips and the fill in the accent (the owner's choice), toned into the card so its words stay clear.
    val fill = androidx.compose.ui.graphics.lerp(CARD_FACE, Ink.accent, 0.35f)
    // Paused (any download), a broken link, or one being fetched afresh (not one queued behind the others): the card as
    // upright strips (the owner's choice: vertical, not diagonal), so what isn't downloading reads apart at a glance —
    // each strip a part of the file, filled as far as that part has come, as a download over several connections shows
    // its threads (the owner's idea): uneven where the parts are, in order where the file fills from the start.
    val striped = row.state == DlState.PAUSED ||
        row.job.link && (row.state == DlState.FAILED || row.state == DlState.RESOLVING && row.error?.startsWith("Waiting for its turn") != true)
    val known by androidx.compose.runtime.produceState<List<Float>?>(null, striped, row.state, row.done) {
        value = if (striped) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Downloads.partsDone(row.job) } else null
    }
    val strips = known ?: com.torrentviewer.core.inOrderMap(row.ratio, 24)
    /** The card's own row's height: stripes and fill stop there. */
    var headPx by remember { mutableIntStateOf(0) }
    // Grabber's look (the owner's choice): a well-rounded card, a film badge where the poster was.
    Column(
        Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(bottom = 8.dp).clip(DL_CARD).background(CARD_FACE)
            .drawWithCache {
                // Each strip's edge: a hairline in the fill's shade, so empty strips still read as strips.
                val edge = 1.dp.toPx()
                // Only the card's own row (the owner's choice): the episodes folded open under it stay plain.
                val head = if (headPx > 0) minOf(size.height, headPx + 20.dp.toPx()) else size.height
                onDrawBehind {
                    clipRect(bottom = head) {
                    if (striped && strips.isNotEmpty()) {
                        val w = size.width / strips.size
                        strips.forEachIndexed { i, f ->
                            val x = i * w
                            if (f > 0f) drawRect(fill, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = size.copy(width = w * f))
                            if (i > 0) drawRect(fill, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = size.copy(width = edge))
                        }
                    } else if (done > 0f) drawRect(fill, size = size.copy(width = size.width * done))
                    }
                }
            }
            .grain()
            // The card itself, tapped: every action for this download (the owner's ask, as 1DM's list).
            .clickable(onClick = onOptions)
            // With the right-hand icons pulled up in line with the text, the card has room for a little less above and below.
            .padding(start = 16.dp, end = CARD - 2.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(Modifier.onSizeChanged { headPx = it.height }, verticalAlignment = Alignment.CenterVertically) {
            // A pack (several files) gets the batch layers in place of the film strip (the owner's choice).
            FilmBadge(if (pack) L.drawable.lucide_ic_layers else L.drawable.lucide_ic_film)
            // Beside the badge, centred on it (the owner's choice): the name and how it's going.
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
                // One line; a name too long for it scrolls slowly across (the owner's choice), a short one stays still.
                Text(row.job.label, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), maxLines = 1)
                // A link being renewed says nothing (the owner's choice): the spinner beside it is enough.
                val status = when (row.state) {
                        // A link being renewed says so briefly, so a long search isn't a silent spinner.
                        DlState.RESOLVING -> if (row.job.link) row.error?.let {
                            when {
                                it.startsWith("Waiting for its turn") -> "Queued — starts when one finishes"
                                it.startsWith("Got a fresh link") -> "Starting…"
                                it.contains("link", true) -> "Finding another source…"
                                else -> it
                            }
                        } else "Finding peers…"
                        // As Grabber words it: what it's doing, then how much of how much (and the rate while it runs).
                        DlState.DOWNLOADING -> listOfNotNull(if (row.speed > 0) speed(row.speed) else "$pct%", sizes(row), "${row.peers} peers".takeIf { !row.job.link && row.total == 0L }).joinToString(" · ")
                        DlState.PAUSED -> listOfNotNull("Paused", sizes(row) ?: "$pct%").joinToString(" · ")
                        DlState.WIFI -> listOfNotNull("Waiting for Wi-Fi", sizes(row) ?: "$pct%").joinToString(" · ")
                        DlState.FAILED -> row.error ?: "Failed"
                    }
                // One line; a longer message scrolls across, as a long title does.
                if (status != null) Text(
                    status,
                    Modifier.padding(top = 1.dp).basicMarquee(),
                    style = Type.meta.copy(fontSize = 12.sp, color = if (row.state == DlState.FAILED) Ink.danger else Ink.muted),
                    maxLines = 1,
                )
            }
            // At the far right: what the file is ("720p | MKV | x265"), split by pipes, to judge a size that looks wrong
            // for its resolution — and under it the buttons (the owner's choice).
            Column(horizontalAlignment = Alignment.End) {
                if (row.job.tags3.isNotEmpty()) Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        row.job.tags3.forEachIndexed { i, tag ->
                            // A pipe between them in the accent (the owner's choice), not a dot.
                            if (i > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = Ink.accent)) { append("  |  ") }
                            append(tag)
                        }
                    },
                    Modifier.padding(end = 8.dp),
                    style = Type.meta.copy(fontSize = 12.sp, color = Ink.muted, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    softWrap = false,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    row.state == DlState.PAUSED -> Tap(onResume, shape = CircleShape) { Lc(L.drawable.lucide_ic_play, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tint = Ink.text, size = 20.dp) }
                    // Failed: ↻ asks the sources again for the next fresh link at this resolution.
                    row.state == DlState.FAILED -> Tap(onRetry, shape = CircleShape) { ReloadArrow(false, Ink.download, 20.dp, Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                    // Queued behind the others: nothing is happening to it yet, so no spinner — it can be paused like a running one.
                    row.state == DlState.RESOLVING && row.error?.startsWith("Waiting for its turn") == true ->
                        Tap(onPause, shape = CircleShape) { Lc(R.drawable.ic_pause_bars, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tint = Ink.text, size = 20.dp) }
                    // Looking for a fresh link: a small spinner where the button was.
                    row.state == DlState.RESOLVING && row.job.link -> Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) { com.torrentviewer.core.Spinner(size = 20.dp) }
                    row.state == DlState.WIFI -> Unit
                    else -> Tap(onPause, shape = CircleShape) { Lc(R.drawable.ic_pause_bars, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tint = Ink.text, size = 20.dp) }
                }
                // A source that looks wrong — a link or a torrent — swapped for a different one, by hand (not while a link is being looked for).
                // A torrent stuck finding peers can be swapped too.
                if (Downloads.canSwap(row.job) && !(row.job.link && row.state == DlState.RESOLVING && row.error?.startsWith("Waiting for its turn") != true)) {
                    Tap(onSwap, shape = CircleShape) { Lc(L.drawable.lucide_ic_shuffle, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tint = Ink.muted, size = 20.dp) }
                }
                // Inside the card's padding (the owner's choice: a notch of room from the edge).
                Tap(onCancel, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), tint = Ink.muted, size = 22.dp) }
                // A pack's episodes fold under the card from here (the owner's choice), right of the ✕.
                if (pack) Tap({ open = !open }, shape = CircleShape) {
                    Lc(if (open) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, Modifier.padding(horizontal = 3.dp, vertical = 1.dp), tint = Ink.muted, size = 28.dp)
                }
                }
            }
        }
        if (row.state == DlState.WIFI || pack) Column {
        // Waiting is a choice the viewer can overrule for this one download.
        if (row.state == DlState.WIFI) Chip("Use mobile data", false, onClick = onResume, Modifier.padding(top = 10.dp))
        // Fastest / In order live in Settings → Downloads now, for every download, not on each card.

        if (pack) {
            if (open) {
                Text("Episodes  ·  ${wanted.size} of ${row.files.size} chosen", Modifier.padding(top = 14.dp, bottom = 8.dp), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold))
                // No All / None (the owner's choice): each episode is ticked on its own row.
                val next = wanted.firstOrNull { !it.saved }?.index
                // Seasons as their own groups: a complete-series pack is a hundred lines otherwise.
                val seasons = row.files.groupBy { parseEpisode(it.name)?.season }
                if (seasons.size <= 1) {
                    row.files.forEach { f -> EpisodeLine(f, isNext = f.index == next && row.state == DlState.DOWNLOADING) { onWanted(f.index, !f.wanted) } }
                } else {
                    seasons.forEach { (season, files) ->
                        SeasonGroup(
                            season,
                            files,
                            expanded = season in openSeasons,
                            onExpand = { openSeasons = if (season in openSeasons) openSeasons - season else openSeasons + season },
                            onWanted = onWanted,
                        ) { f -> EpisodeLine(f, isNext = f.index == next && row.state == DlState.DOWNLOADING) { onWanted(f.index, !f.wanted) } }
                    }
                }
            }
        }
        }
    }
}

/**
 * One season of a pack: a circle that keeps or skips the whole season, its name, how many
 * of its chosen episodes are saved, and a fold to show or hide them.
 */
@Composable
private fun SeasonGroup(
    season: Int?,
    files: List<DlFile>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onWanted: (Int, Boolean) -> Unit,
    episode: @Composable (DlFile) -> Unit,
) {
    val chosen = files.filter { it.wanted }
    val all = chosen.size == files.size
    val saved = chosen.count { it.saved }
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // All on → skip what is not saved yet; anything off → take the whole season.
            Tap({ files.filter { if (all) !it.saved else !it.wanted }.forEach { onWanted(it.index, !all) } }, shape = CircleShape) {
                Lc(
                    if (chosen.isEmpty()) L.drawable.lucide_ic_circle else if (all) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle_dashed,
                    Modifier.padding(6.dp),
                    tint = if (chosen.isEmpty()) Ink.faint else Ink.accent,
                    size = 20.dp,
                )
            }
            Tap(onExpand, Modifier.weight(1f), RoundedCornerShape(4.dp)) {
                Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(season?.let { if (it == 0) "Specials" else "Season $it" } ?: "Other files", Modifier.weight(1f), style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        if (chosen.isEmpty()) "Skipped" else "$saved of ${chosen.size} saved",
                        Modifier.padding(end = 6.dp),
                        style = Type.meta.copy(fontSize = 12.sp, color = if (chosen.isNotEmpty() && saved == chosen.size) Ink.good else Ink.faint),
                    )
                    Lc(if (expanded) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, tint = Ink.muted, size = 24.dp)
                }
            }
        }
        if (expanded) Column(Modifier.padding(start = 12.dp)) { files.forEach { episode(it) } }
    }
}

/** An episode in a pack: a circle to keep or skip it, its tag, a thin bar, and where it stands. */
@Composable
private fun EpisodeLine(f: DlFile, isNext: Boolean, onToggle: () -> Unit) {
    val ep = parseEpisode(f.name)?.let { "S${it.season}:E${it.episode}" } ?: f.name
    val pct = if (f.size > 0) (f.done * 100 / f.size).toInt() else 0
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // A saved episode is on the phone already; there is nothing to skip.
        Tap({ if (!f.saved) onToggle() }, shape = CircleShape) {
            Lc(
                when {
                    f.saved -> L.drawable.lucide_ic_circle_check
                    f.wanted -> L.drawable.lucide_ic_circle_check
                    else -> L.drawable.lucide_ic_circle
                },
                Modifier.padding(6.dp),
                tint = when {
                    f.saved -> Ink.good
                    f.wanted -> Ink.accent
                    else -> Ink.faint
                },
                size = 20.dp,
            )
        }
        Text(ep, Modifier.width(64.dp).padding(start = 4.dp), style = Type.meta.copy(color = if (f.wanted) Ink.text else Ink.faint, fontWeight = FontWeight.SemiBold), maxLines = 1)
        // The room the line had, kept (the owner's choice): no line now, only the percentage at the end.
        Spacer(Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(
            when {
                f.saved -> "Saved"
                !f.wanted -> "Skipped"
                isNext -> "$pct%  ·  next"
                else -> "$pct%"
            },
            Modifier.width(84.dp),
            style = Type.meta.copy(fontSize = 12.sp, color = if (f.saved) Ink.good else Ink.faint, textAlign = TextAlign.End),
            maxLines = 1,
        )
    }
}

/** "20.1 MB of 718 MB", as Grabber says it; null before the size is known. */
private fun sizes(row: LiveDownload): String? = if (row.total > 0) "${bytes(row.done)} of ${bytes(row.total)}" else null

/** A saved file's ⓘ, for a film or an episode of the catalogue only (it has an IMDb id): YouTube's and the Browser's files have none. */
private fun storyOf(f: Finished, open: (Finished) -> Unit): (() -> Unit)? = if (f.imdb?.startsWith("tt") == true) ({ open(f) }) else null

/** [file] handed to another app (a chat, Drive, another player): through the app's file provider, read-only. */
fun shareFile(context: android.content.Context, file: java.io.File, title: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(type).putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, title).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(send, "Share $title").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

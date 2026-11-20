package com.torrentviewer.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import androidx.compose.foundation.shape.CircleShape
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.bytes
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.searchEpisode
import com.torrentviewer.mobile.cs.CsStreams
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.composables.icons.lucide.R as L

/** A season's sources, being gathered: each episode's releases as they come in. */
data class SeasonScan(
    val titleImdb: String,
    val season: Int,
    val episodes: List<Episode>,
    /** By episode id: what each episode has found so far — filled in as its sources answer, not only at the end. */
    val found: Map<String, List<Hit>> = emptyMap(),
    /** Episodes every source has answered for (their ids): their picks are final. */
    val finished: Set<String> = emptySet(),
    /** When the scan started: its links are at least this old, and renewed before they start if too old. */
    val at: Long = System.currentTimeMillis(),
) {
    val checked get() = finished.size
    val done get() = finished.size >= episodes.size
}

/**
 * Batch season downloads: every episode of a season asked for its sources (addons and plugins, as the
 * title page does, a few at a time), then the viewer's filters pick one release per episode from what
 * was found, and those go to the Downloads tab. The scan runs on with the sheet closed, and is kept
 * for the season so reopening the sheet doesn't ask again.
 */
object SeasonBatch {
    /**
     * Episodes asked about at once: two, as when the light Moviesmod files reliably came in. Three (with link checks
     * running alongside) crowded the plugins' ten slots and the phone's connection, and StreamPlay — whose extractors
     * hop through several sites per link — ran out of time before reaching them, leaving 1 GB picks.
     */
    private const val AT_ONCE = 2

    private val _scan = MutableStateFlow<SeasonScan?>(null)
    val scan: StateFlow<SeasonScan?> = _scan
    private var job: Job? = null

    /** How long one episode may be asked about: past this, what has come in is what it gets. */
    private const val EPISODE_MS = 60_000L

    /**
     * However good the first answers look, an episode's search runs at least this long: the fast sources (Torrentio)
     * answer in seconds, the plugins with the smaller files later, and a pick made before they're in isn't the smallest.
     */
    private const val EPISODE_MIN_MS = 25_000L

    /**
     * An episode's sources, as the title page finds them — but not waited on to the end ([gather]). Waiting on
     * every plugin (up to minutes each, per episode) made a season take many minutes for links nobody needed.
     */
    suspend fun sources(title: Title, ep: Episode, providerMs: Long = CsStreams.PROVIDER_MS, onPartial: (List<Hit>) -> Unit = {}): List<Hit> = kotlinx.coroutines.coroutineScope {
        // Links are checked as they arrive, not all at the end: by the time the last site answers, most are known.
        var checking: Job? = null
        var lastCheck = 0L
        val search: suspend (com.torrentviewer.core.OnHits) -> List<Hit> = { onHits ->
            CsStreams.withPlugins(title, ep, onHits, providerMs) { fromAddons -> searchEpisode(title, ep.season, ep.episode, fromAddons) }
        }
        val watch: (List<Hit>) -> Unit = { partial ->
            onPartial(partial)
            val t = System.currentTimeMillis()
            if (checking?.isActive != true && t - lastCheck > 3000) {
                lastCheck = t
                checking = launch { runCatching { LinkProbe.describe(partial) } }
            }
        }
        // Every source, the slow sites too (the owner's rule): the light 720p MKV links are often the last to answer. The
        // one exception, on any network: an episode that already holds a confirmed MoviesMod 720p MKV of 400 MB or less
        // ([confirmed]) stops there — the owner's first site, and nothing later would be meaningfully lighter.
        val all = gatherUntil(EPISODE_CAP_MS, watch, search)
        // The checks under way finish (they're most of the work), then whatever came after them is checked.
        checking?.join()
        // Then whatever is left unchecked, asked for its file name ([LinkProbe]): the light 720p MKVs are what a pick is after.
        LinkProbe.describe(all)
    }

    /**
     * A pick that needs nothing more: from MoviesMod (the owner's first site, [trustRank]), the preferred resolution and
     * format, a direct file whose size is known and at most [LIGHT_EPISODE_BYTES]. An episode that has one stops asking
     * the slower sites; any other waits for every site, since MoviesMod's files come late through StreamPlay.
     */
    private fun confirmed(hits: List<Hit>, prefs: DownloadPrefs): Boolean {
        val pick = seasonPick(hits, prefs) ?: return false
        // Light too: a 1 GB 720p from a quick site stopped the scan before the slow sites' 300 MB ones came in.
        val light = (LIGHT_EPISODE_BYTES * if (pick.kind == "movie") 2.5 else 1.0).toLong()
        return pick.direct && trustRank(pick) == 3 && !isPlaylist(pick) && pick.size in 1..light && onTarget(listOf(pick), prefs, ::seasonPick)
    }

    /**
     * An episode stops early only on a confirmed MoviesMod pick this light (a film: 2.5 times). Any site's used to stop
     * it, and a quick site's 365 MB 720p ended an episode before MoviesMod's 271 MB one came in (the owner caught it).
     */
    private const val LIGHT_EPISODE_BYTES = 400L * 1024 * 1024

    /** [gatherAll], but done as soon as what has come holds a [confirmed] pick (after a short floor for the quick sources). */
    private suspend fun gatherUntil(capMs: Long, onPartial: (List<Hit>) -> Unit, search: suspend (com.torrentviewer.core.OnHits) -> List<Hit>): List<Hit> = runCatching {
        val prefs = downloadPrefs()
        val started = System.currentTimeMillis()
        val latest = java.util.concurrent.atomic.AtomicReference<List<Hit>>(emptyList())
        val enough = kotlinx.coroutines.CompletableDeferred<Unit>()
        var told = 0L
        val job = AppScope.async(kotlinx.coroutines.Dispatchers.Default) {
            search { now ->
                latest.set(now)
                val t = System.currentTimeMillis()
                if (t - told >= 1000) {
                    told = t
                    val seen = allowedHits(now).map(::guessed)
                    runCatching { onPartial(seen) }
                    if (t - started > SAVING_FLOOR_MS && confirmed(seen, prefs)) enough.complete(Unit)
                }
            }
        }
        try {
            allowedHits(
                withTimeoutOrNull(capMs) {
                    kotlinx.coroutines.selects.select<List<Hit>?> {
                        job.onAwait { it }
                        enough.onAwait { null }
                    }
                } ?: latest.get(),
            )
        } finally {
            job.cancel()
        }
    }.getOrDefault(emptyList())

    /** Even with a light pick in hand, an episode is asked about this long: the quick sources' files come in by then. */
    private const val SAVING_FLOOR_MS = 20_000L

    /** The longest one episode's sources are waited on: only a plugin that hangs is ever cut short. */
    private const val EPISODE_CAP_MS = 300_000L

    /** Every answer [search] gives, waited on to the end — or [capMs], with what had come by then. */
    private suspend fun gatherAll(capMs: Long, onPartial: (List<Hit>) -> Unit, search: suspend (com.torrentviewer.core.OnHits) -> List<Hit>): List<Hit> = runCatching {
        val latest = java.util.concurrent.atomic.AtomicReference<List<Hit>>(emptyList())
        var told = 0L
        val job = AppScope.async(kotlinx.coroutines.Dispatchers.Default) {
            search { now ->
                latest.set(now)
                // What has come so far, for the sheet to pick from at once — at most once a second, read by size where
                // a link doesn't say ([guessed]); the file names are asked for at the end.
                val t = System.currentTimeMillis()
                if (t - told >= 1000) {
                    told = t
                    runCatching { onPartial(allowedHits(now).map(::guessed)) }
                }
            }
        }
        try {
            allowedHits(withTimeoutOrNull(capMs) { job.await() } ?: latest.get())
        } finally {
            job.cancel()
        }
    }.getOrDefault(emptyList())

    /**
     * Longer still for an episode with nothing worth taking yet — no pick as preferred, and no light link that may
     * turn out to be one: the slow sites (Moviesmod and the like, through StreamPlay) are where the small files are,
     * and stopping before they answered left that episode with an 11 GB 1080p.
     */
    private const val EPISODE_MORE_MS = 75_000L

    /** A light link that doesn't say what it is: likely the small 720p a pick wants, once [LinkProbe] has asked. */
    private fun promising(hits: List<Hit>) = hits.any { unlabelled(it) && batchable(it) && it.size in 1..LIGHT_BYTES }
    private const val LIGHT_BYTES = 1_500L * 1024 * 1024

    /**
     * What [search] finds, for a download pick: it stops once what's in already holds a pick as preferred
     * ([goodEnough]) and [minMs] has passed, or after [limitMs], with what has come in. The search is its own job, not the caller's child:
     * a plugin stuck in a slow request is cancelled and left behind, not waited for — waiting on it is what made
     * a time limit here mean nothing.
     */
    suspend fun gather(limitMs: Long, minMs: Long = 0, moreMs: Long = 0, search: suspend (com.torrentviewer.core.OnHits) -> List<Hit>): List<Hit> = runCatching {
        val started = System.currentTimeMillis()
        val prefs = downloadPrefs()
        val latest = java.util.concurrent.atomic.AtomicReference<List<Hit>>(emptyList())
        val enough = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = AppScope.async(kotlinx.coroutines.Dispatchers.Default) {
            search { now ->
                latest.set(now)
                if (!enough.isCompleted && goodEnough(allowedHits(now), prefs)) enough.complete(Unit)
            }
        }
        try {
            val all = withTimeoutOrNull(limitMs) {
                kotlinx.coroutines.selects.select<List<Hit>?> {
                    job.onAwait { it }
                    enough.onAwait { null }
                }
            }
            // Good enough early: still given the rest of [minMs], for the slower sources' (often smaller) files.
            val left = minMs - (System.currentTimeMillis() - started)
            var more = if (all == null && left > 0) withTimeoutOrNull(left) { job.await() } else null
            // Out of time with nothing worth taking: the slow sources get [moreMs] more, a look every two seconds.
            if (all == null && more == null && moreMs > 0 && !enough.isCompleted) {
                val until = System.currentTimeMillis() + moreMs
                while (System.currentTimeMillis() < until && !job.isCompleted && !enough.isCompleted && !promising(allowedHits(latest.get()))) kotlinx.coroutines.delay(2000)
                if (job.isCompleted) more = runCatching { job.await() }.getOrNull()
            }
            allowedHits(all ?: more ?: latest.get())
        } finally {
            job.cancel()
        }
    }.getOrDefault(emptyList())

    /** Starts gathering the season's sources, unless that's already under way or done for this season. */
    fun begin(title: Title, season: Int, again: Boolean = false) {
        val now = _scan.value
        if (!again && now != null && now.titleImdb == title.imdb && now.season == season) return
        // Only what has aired: an episode still to come has no release, and the sites answer with something else.
        val episodes = com.torrentviewer.core.airedBy(title.episodes.filter { it.season == season }).sortedBy { it.episode }
        job?.cancel()
        // Asked again: from the sites themselves, not answers or pages kept from the last scan.
        if (again) com.torrentviewer.core.forgetStreams()
        val mine = SeasonScan(title.imdb, season, episodes)
        _scan.value = mine
        val gate = Semaphore(AT_ONCE)
        /** [hits] added to what [ep] has ([mergedHits]: a link once seen stays); [final] marks it answered, else asked again. */
        fun put(ep: Episode, hits: List<Hit>, final: Boolean) = synchronized(this@SeasonBatch) {
            // This scan's only: one cancelled for a new one ("Ask Every Source Again") never writes into it.
            _scan.value = _scan.value?.takeIf { it.titleImdb == title.imdb && it.season == season && it.at == mine.at }?.let {
                it.copy(found = it.found + (ep.id to mergedHits(it.found[ep.id].orEmpty(), hits)), finished = if (final) it.finished + ep.id else it.finished - ep.id)
            }
        }
        // Off the main thread: ranking and filtering hundreds of releases per episode there made the app stutter.
        job = AppScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (again) com.torrentviewer.mobile.cs.CsPlugins.forgetPages()
            kotlinx.coroutines.coroutineScope {
                for (ep in episodes) launch {
                    // Each answer shown as it comes: the sheet picks from it at once, and swaps in better ones as they arrive.
                    val hits = gate.withPermit { sources(title, ep, providerMs = SCAN_PROVIDER_MS) { partial -> put(ep, partial, final = false) } }
                    put(ep, hits, final = true)
                }
            }
            // A second look, one episode at a time, for each that didn't get a MoviesMod or MoviesDrive file
            // ([wantsTrustedAgain]): with nothing else running and each plugin given longer, StreamPlay reaches the sites it
            // ran out of time for (the owner's E03 fell to HDHub's 515 MB over MoviesDrive's 394 MB). What comes is added
            // to what the episode had; it shows "still looking" meanwhile.
            val prefs = downloadPrefs()
            for (ep in episodes) {
                ensureActive()
                val had = _scan.value?.takeIf { it.at == mine.at }?.found?.get(ep.id) ?: break
                if (!wantsTrustedAgain(had, prefs)) continue
                put(ep, emptyList(), final = false)
                val more = sources(title, ep, providerMs = SECOND_LOOK_MS) { partial -> put(ep, partial, final = false) }
                ensureActive()
                put(ep, more, final = true)
            }
        }
    }

    /**
     * Each plugin's time per episode in a season scan, against a minute on a title page: nobody sits waiting on a season,
     * and the owner's rule is that its sources come through in full before the picks are made — StreamPlay's MoviesMod and
     * MoviesDrive links hop through several sites and came in after a minute. A plugin done sooner is done sooner.
     */
    private const val SCAN_PROVIDER_MS = 150_000L

    /** Each plugin's time on the second look ([begin]), with nothing else running: longer still. */
    private const val SECOND_LOOK_MS = 240_000L


    /** Queues every picked release; how many went. */
    fun download(title: Title, picks: List<Pair<Episode, Hit>>, paused: Boolean = false): Int {
        val at = _scan.value?.at ?: System.currentTimeMillis()
        // [paused]: added to the Downloads tab without starting — each waits for its ▶.
        picks.forEach { (ep, hit) -> Downloads.add(downloadJob(hit, title, ep, foundAt = at).copy(paused = paused)) }
        return picks.size
    }
}

/**
 * Download a whole season: filters over the sources found for every episode — resolution, codec, format,
 * built-in subtitles, source — each offering only what was found, and what each episode would get.
 */
@Composable
fun BatchSheet(title: Title, season: Int, say: (String) -> Unit, onDismiss: () -> Unit, external: Boolean = false, sendTo: Downloader = Downloader.Default) {
    val context = androidx.compose.ui.platform.LocalContext.current
    /** The app the links go to passes their headers on (Grabber): links that need a Referer can go too. */
    val headersGo = remember(sendTo) { external && ExternalDownloaders.sendsHeaders(context, sendTo) }
    /** The app the links go to opens torrents (1DM does): magnets can go too. Other apps get only direct links. */
    val magnetsGo = remember(sendTo) { external && ExternalDownloaders.takesMagnets(context, sendTo) }
    LaunchedEffect(title.imdb, season) { SeasonBatch.begin(title, season) }
    val scanned by SeasonBatch.scan.collectAsState()
    val scan = scanned?.takeIf { it.titleImdb == title.imdb && it.season == season }
    var filter by remember(title.imdb, season) { mutableStateOf(BatchFilter()) }
    // No filter chosen: each episode gets what Settings → Preferred download would pick for it — the preferred
    // resolution, else the next one up, then codec, format… as preferences, so every episode with anything gets
    // something. A chip tapped below is a hard choice instead: only releases with it.
    val prefs = downloadPrefs()
    // Open from the start: its ticks are how an episode is left out.
    var showPlan by remember { mutableStateOf(true) }
    /** Episodes ticked off by hand (their ids): left out of the download and the send. */
    var skipped by remember(title.imdb, season) { mutableStateOf(emptySet<String>()) }
    /** Fresh links being fetched before they go to another app. */
    var sending by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** The Send button held: the "Download With" sheet over this one. */
    var choosing by remember { mutableStateOf(false) }
    /** The app picked in that sheet, waiting to be sent to. */
    var chosen by remember { mutableStateOf<Downloader?>(null) }

    BottomSheet("Download season $season", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = CARD).padding(bottom = 12.dp)) {
            if (scan == null) {
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 20.dp)
                    Text("   Starting…", style = Type.meta.copy(color = Ink.muted))
                }
                return@Column
            }
            /** What the plan may take from an episode's releases: for another app, the links that need no headers when there are any. */
            // Grabber takes each link's headers with it, so for Grabber nothing is left out.
            fun usableFor(hits: List<Hit>): List<Hit> {
                if (!external) return hits
                val sendable = hits.filter { (it.direct || magnetsGo) && (headersGo || it.headers.isEmpty()) }
                return sendable.ifEmpty { hits }
            }
            // What a pick may take: the chips count only that, thin torrents left out as in the lists.
            val all = prefs.usable(scan.found.values.flatten())
            if (!scan.done) Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 20.dp)
                Text("   Checking episodes · ${scan.checked} of ${scan.episodes.size}", style = Type.meta.copy(color = Ink.muted))
            }

            if (scan.done && all.none(::batchable)) {
                EmptyNote("Nothing downloadable was found for this season's episodes.")
                return@Column
            }

            // Each row offers what the others leave possible; tapping the chosen value again clears it.
            // The rows' choices are counted when the scan or the chips change, not on every redraw.
            // Counted in episodes, from what the plan itself may take (for another app, only links that need no headers):
            // the number on a chip is how many episodes choosing it can fill.
            val options = remember(scan, filter, external, headersGo, magnetsGo) {
                val eps = scan.found.values.map { hits -> usableFor(prefs.usable(hits)) }
                listOf(
                    episodeOptions(eps, filter.copy(quality = null)) { it.quality },
                    episodeOptions(eps, filter.copy(codec = null)) { it.codec },
                    episodeOptions(eps, filter.copy(format = null)) { it.format },
                    episodeOptions(eps, filter.copy(subsOnly = false)) { if (it.subs) "With subs" else null },
                    episodeOptions(eps, filter.copy(source = null)) { it.source },
                )
            }
            // Spread end to end here only (the owner's choice); every other bar row stays in plain flow.
            FilterRow("Resolution", options[0], filter.quality, spread = true) { filter = filter.copy(quality = it) }
            FilterRow("Codec", options[1], filter.codec, label = ::codecLabel) { filter = filter.copy(codec = it) }
            FilterRow("Format", options[2], filter.format) { filter = filter.copy(format = it) }
            FilterRow("Subtitles", options[3], if (filter.subsOnly) "With subs" else null) { filter = filter.copy(subsOnly = it != null) }
            FilterRow("Source", options[4], filter.source, label = { if (it == "Link") "Direct link" else it }) { filter = filter.copy(source = it) }

            // For another app: links that need no headers first, since a shared link can't carry them.
            // Worked out when the scan, the chips or the preferences change, not on every redraw of the sheet.
            val plan = remember(scan, filter, prefs, external, headersGo, magnetsGo) {
                // Every episode, from the start: its pick so far, swapped for a better one as its sources answer.
                scan.episodes.map { ep ->
                    val hits = scan.found[ep.id].orEmpty()
                    val from = usableFor(hits)
                    ep to if (filter == BatchFilter()) seasonPick(from, prefs) else batchPick(from, filter, prefs.direct, prefs)
                }
            }
            val picks = plan.mapNotNull { (ep, hit) -> hit?.takeIf { ep.id !in skipped }?.let { ep to it } }
            // Only those every source has answered for: the rest are still being looked for.
            val missing = plan.filter { it.second == null && it.first.id in scan.finished }.map { it.first.episode }
            val known = picks.sumOf { it.second.size }
            Text(
                "Matches ${picks.size} of ${scan.episodes.size} episodes" +
                    (if (known > 0) " · about ${bytes(known)}" else "") +
                    (if (picks.any { it.second.size == 0L }) " + some of unknown size" else ""),
                Modifier.padding(top = 6.dp), style = Type.body.copy(fontWeight = FontWeight.SemiBold),
            )
            if (missing.isNotEmpty()) Text(
                "No match for episode${if (missing.size == 1) "" else "s"} ${missing.joinToString(", ")}" + if (!scan.done) " yet" else "",
                Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint),
            )
            Text(
                if (filter == BatchFilter()) "Per episode: your preferred ${listOf(prefs.quality, prefs.codec, prefs.format).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "settings" }} where found, " +
                    "else the next resolution up — then the lightest. Tap a chip above to insist on something."
                else "Per episode: only releases with what you chose above; ${if (filter.codec == null) "HEVC (x265) first, then " else ""}the smallest file — nothing under ${bytes(MIN_EPISODE_BYTES)}.",
                Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
            )

            // The Download button's size and shape, in the plain translucent style.
            Button(
                if (showPlan) "Hide what each episode gets" else "Show what each episode gets", { showPlan = !showPlan },
                Modifier.padding(top = 14.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_list_video,
            )
            // A light season pack (a torrent of the whole season at 720p, 450 MB an episode at most): one download instead of eight.
            val pack = remember(scan, prefs, magnetsGo) {
                if (external && !magnetsGo) null else lightPack(scan.found.values.flatten(), season, scan.episodes.size, prefs)
            }
            if (pack != null && filter == BatchFilter()) Column(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Ink.text.copy(alpha = 0.06f)).padding(12.dp),
            ) {
                Text("Season pack", style = Type.overline.copy(color = Ink.muted))
                Text(
                    listOfNotNull(pack.quality, traitsOf(pack).codec.takeIf { it != UNKNOWN }?.let(::codecLabel), bytes(pack.size), "${pack.seeders} seeds").joinToString(" · ") +
                        " — about ${bytes(pack.size / scan.episodes.size.coerceAtLeast(1))} an episode",
                    Modifier.padding(top = 2.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(pack.release, Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(
                    if (external) "Send the Pack" else "Download the Pack",
                    {
                        if (external) {
                            ExternalDownloaders.note(openInDownloader(context, pack, "${title.title} · season $season", sendTo), many = false)?.let(say)
                        } else {
                            Downloads.add(downloadJob(pack, title).copy(season = season))
                            say("Downloading season $season as one pack — see Downloads")
                        }
                        onDismiss()
                    },
                    Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_layers, fill = Ink.download,
                )
            }
            if (showPlan) plan.forEach { (ep, hit) ->
                val t = hit?.let(::traitsOf)
                val on = hit != null && ep.id !in skipped
                // A tick to keep or leave out each episode, as a pack's episodes have in Downloads.
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tap({ if (hit != null) skipped = if (ep.id in skipped) skipped - ep.id else skipped + ep.id }, shape = CircleShape) {
                    Lc(
                        if (on) L.drawable.lucide_ic_circle_check else L.drawable.lucide_ic_circle,
                        Modifier.padding(6.dp), tint = if (on) Ink.download else Ink.faint, size = 20.dp,
                    )
                }
                Text(
                    "${episodeTag(ep.season, ep.episode)}  " + if (hit == null) (if (ep.id in scan.finished) "—" else "searching…") else
                        listOfNotNull(hit.quality, t?.codec?.takeIf { it != UNKNOWN }?.let(::codecLabel), t?.format?.takeIf { it != UNKNOWN }, bytes(hit.size).takeIf { hit.size > 0 }, hit.addon.ifEmpty { null })
                            .joinToString(" · "),
                    Modifier.padding(start = 4.dp), style = Type.meta.copy(color = if (on) Ink.muted else Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                }
                // Why this pick, on a line of its own (at the end of the row it was cut off): still looking, its size
                // unconfirmed, or why it isn't the preferred 720p MKV.
                val note = if (hit == null) null else when {
                    ep.id !in scan.finished -> "still looking"
                    // A much lighter 720p MKV exists, but only for the app's own downloader (it needs its site's headers, or is a torrent).
                    external && filter == BatchFilter() && onTarget(listOf(hit), prefs, ::seasonPick) &&
                        seasonPick(scan.found[ep.id].orEmpty(), prefs)?.let { it.size > 0 && hit.size > 0 && it.size < hit.size * 0.7 } == true ->
                        "a ${bytes(seasonPick(scan.found[ep.id].orEmpty(), prefs)?.size ?: 0)} one is only for the app's own downloader"
                    filter == BatchFilter() && prefs.quality.isNotEmpty() && !onTarget(listOf(hit), prefs, ::seasonPick) ->
                        // For another app: 720p MKVs that need their site's headers, which a shared link can't carry.
                        if (external && onTarget(scan.found[ep.id].orEmpty(), prefs, ::seasonPick)) "its ${prefs.quality} ${prefs.format} files need the app's own downloader"
                        else "no ${prefs.quality} ${prefs.format} found"
                    hit.size <= 0L -> "size unknown"
                    else -> null
                }
                if (note != null) Text(
                    note, Modifier.padding(start = 38.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            val many = "${picks.size} episode${if (picks.size == 1) "" else "s"}"
            /**
             * Another app's downloader: every pick's link at once, in episode order, as found — straight away, however long
             * ago the scan was (the owner's choice: asking every source again took minutes). Stale links are renewed only
             * when asked, with Ask Every Source Again.
             */
            fun send(to: Downloader) {
                if (sending || picks.isEmpty()) return
                val handed = sendLinks(context, picks.map { it.second }, "${title.title} · season $season", to, picks.map { "${title.title} · ${com.torrentviewer.core.episodeTag(it.first.season, it.first.episode)}" })
                ExternalDownloaders.note(handed, many = true)?.let(say)
                if (handed != Handed.Failed) onDismiss()
            }
            // Picked in the "Download With" sheet (drawn over this one, below): sent from here, where the picks are.
            LaunchedEffect(chosen) { chosen?.let { chosen = null; send(it) } }
            if (!external && picks.isNotEmpty()) {
                // Add beside Download, the same size: Add puts the episodes in the Downloads tab paused, Download starts them.
                Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        "Add", {
                            SeasonBatch.download(title, picks, paused = true)
                            say("Added $many of season $season to Downloads, paused — tap ▶ there to start")
                            onDismiss()
                        },
                        Modifier.weight(1f), icon = L.drawable.lucide_ic_plus, primary = true,
                    )
                    Button(
                        "Download", {
                            SeasonBatch.download(title, picks)
                            say("Downloading $many of season $season — see Downloads")
                            onDismiss()
                        },
                        Modifier.weight(1f), icon = L.drawable.lucide_ic_download, fill = Ink.download,
                    )
                }
            } else Button(
                when {
                    picks.isEmpty() -> "Nothing matches"
                    sending -> "Getting fresh links…"
                    else -> "Send $many to Downloader"
                },
                { if (picks.isNotEmpty() && external) send(sendTo) },
                Modifier.padding(top = 16.dp).fillMaxWidth(), icon = if (external) L.drawable.lucide_ic_external_link else L.drawable.lucide_ic_download,
                fill = Ink.download.takeIf { picks.isNotEmpty() },
                onLongClick = { if (external && picks.isNotEmpty() && !sending) choosing = true },
            )
            if (external && picks.isNotEmpty()) Text(
                "Long tap for share and download options!",
                Modifier.fillMaxWidth().padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (scan.done) Solo(Modifier.padding(top = 14.dp, bottom = 6.dp)) { m -> Chip("Ask Every Source Again", false, onClick = { SeasonBatch.begin(title, season, again = true) }, m, tall = true) }
        }
    }
    if (choosing) DownloaderSheet(ExternalDownloaders.sendProbe(), many = true, onPick = { chosen = it }, onDismiss = { choosing = false })
}

/**
 * Links found this recently go to another app's downloader as they are: they last well beyond this, and asking
 * every source again only kept the viewer waiting. Older ones are renewed first, since an external app can't.
 */
private const val SEND_AS_FOUND_MS = 30 * 60 * 1000L

private fun codecLabel(c: String) = when (c) {
    "x265" -> "x265 (HEVC)"
    "x264" -> "x264 (AVC)"
    else -> c
}

/** One filter: its name, then Any and each value found with its count; [spread] lays it across the width ([BarChoices]). */
@Composable
fun FilterRow(name: String, options: List<Pair<String, Int>>, chosen: String?, label: (String) -> String = { it }, spread: Boolean = false, onPick: (String?) -> Unit) {
    if (options.isEmpty() && chosen == null) return
    Text(name, Modifier.padding(top = 8.dp, bottom = 6.dp), style = Type.overline.copy(color = Ink.faint))
    // Words split by coloured bars, the chosen one with a dot (the owner's choice: Grabber's look, no pills).
    val values = listOf<String?>(null) + options.map { it.first }
    BarChoices(listOf("Any") + options.map { (value, n) -> "${label(value)} · $n" }, values.indexOf(chosen).takeIf { it >= 0 }, spread = spread) { i ->
        val value = values[i]
        onPick(if (value != null && value == chosen) null else value)
    }
}

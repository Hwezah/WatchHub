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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Note
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
    /** By episode id, once that episode has answered. */
    val found: Map<String, List<Hit>> = emptyMap(),
    /** When the scan started: its links are at least this old, and renewed before they start if too old. */
    val at: Long = System.currentTimeMillis(),
) {
    val checked get() = found.size
    val done get() = found.size >= episodes.size
}

/**
 * Batch season downloads: every episode of a season asked for its sources (addons and plugins, as the
 * title page does, a few at a time), then the viewer's filters pick one release per episode from what
 * was found, and those go to the Downloads tab. The scan runs on with the sheet closed, and is kept
 * for the season so reopening the sheet doesn't ask again.
 */
object SeasonBatch {
    private const val AT_ONCE = 3

    private val _scan = MutableStateFlow<SeasonScan?>(null)
    val scan: StateFlow<SeasonScan?> = _scan
    private var job: Job? = null

    /** How long one episode may be asked about: past this, what has come in is what it gets. */
    private const val EPISODE_MS = 40_000L

    /**
     * An episode's sources, as the title page finds them — but not waited on to the end ([gather]). Waiting on
     * every plugin (up to minutes each, per episode) made a season take many minutes for links nobody needed.
     */
    suspend fun sources(title: Title, ep: Episode): List<Hit> =
        gather(EPISODE_MS) { onHits -> CsStreams.withPlugins(title, ep, onHits) { fromAddons -> searchEpisode(title, ep.season, ep.episode, fromAddons) } }

    /**
     * What [search] finds, for a download pick: it stops once what's in already holds a pick as preferred
     * ([goodEnough]), or after [limitMs] with what has come in. The search is its own job, not the caller's child:
     * a plugin stuck in a slow request is cancelled and left behind, not waited for — waiting on it is what made
     * a time limit here mean nothing.
     */
    suspend fun gather(limitMs: Long, search: suspend (com.torrentviewer.core.OnHits) -> List<Hit>): List<Hit> = runCatching {
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
            allowedHits(all ?: latest.get())
        } finally {
            job.cancel()
        }
    }.getOrDefault(emptyList())

    /** Starts gathering the season's sources, unless that's already under way or done for this season. */
    fun begin(title: Title, season: Int, again: Boolean = false) {
        val now = _scan.value
        if (!again && now != null && now.titleImdb == title.imdb && now.season == season) return
        val episodes = title.episodes.filter { it.season == season }.sortedBy { it.episode }
        job?.cancel()
        // Asked again: from the sites themselves, not answers or pages kept from the last scan.
        if (again) com.torrentviewer.core.forgetStreams()
        _scan.value = SeasonScan(title.imdb, season, episodes)
        val gate = Semaphore(AT_ONCE)
        // Off the main thread: ranking and filtering hundreds of releases per episode there made the app stutter.
        job = AppScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (again) com.torrentviewer.mobile.cs.CsPlugins.forgetPages()
            for (ep in episodes) launch {
                val hits = gate.withPermit { sources(title, ep) }
                synchronized(this@SeasonBatch) {
                    _scan.value = _scan.value?.takeIf { it.titleImdb == title.imdb && it.season == season }
                        ?.let { it.copy(found = it.found + (ep.id to hits)) }
                }
            }
        }
    }

    /** Queues every picked release; how many went. */
    fun download(title: Title, picks: List<Pair<Episode, Hit>>): Int {
        val at = _scan.value?.at ?: System.currentTimeMillis()
        picks.forEach { (ep, hit) -> Downloads.add(downloadJob(hit, title, ep, foundAt = at)) }
        return picks.size
    }
}

/**
 * Download a whole season: filters over the sources found for every episode — resolution, codec, format,
 * built-in subtitles, source — each offering only what was found, and what each episode would get.
 */
@Composable
fun BatchSheet(title: Title, season: Int, say: (String) -> Unit, onDismiss: () -> Unit, external: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(title.imdb, season) { SeasonBatch.begin(title, season) }
    val scanned by SeasonBatch.scan.collectAsState()
    val scan = scanned?.takeIf { it.titleImdb == title.imdb && it.season == season }
    var filter by remember(title.imdb, season) { mutableStateOf(BatchFilter()) }
    // No filter chosen: each episode gets what Settings → Preferred download would pick for it — the preferred
    // resolution, else the next one up, then codec, format… as preferences, so every episode with anything gets
    // something. A chip tapped below is a hard choice instead: only releases with it.
    val prefs = downloadPrefs()
    var showPlan by remember { mutableStateOf(false) }
    /** Fresh links being fetched before they go to another app. */
    var sending by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    BottomSheet("Download season $season", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = CARD).padding(bottom = 12.dp)) {
            if (scan == null) {
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 20.dp)
                    Text("   Starting…", style = Type.meta.copy(color = Ink.muted))
                }
                return@Column
            }
            // What a pick may take: the chips count only that, thin torrents left out as in the lists.
            val all = prefs.usable(scan.found.values.flatten())
            if (!scan.done) Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 20.dp)
                Text("   Checking episodes · ${scan.checked} of ${scan.episodes.size}", style = Type.meta.copy(color = Ink.muted))
            }
            if (scan.done && all.none(::batchable)) {
                Note("Nothing downloadable was found for this season's episodes.")
                return@Column
            }

            // Each row offers what the others leave possible; tapping the chosen value again clears it.
            // The rows' choices are counted when the scan or the chips change, not on every redraw.
            val options = remember(scan, filter) {
                listOf(
                    filterOptions(all, filter.copy(quality = null)) { it.quality },
                    filterOptions(all, filter.copy(codec = null)) { it.codec },
                    filterOptions(all, filter.copy(format = null)) { it.format },
                    filterOptions(all, filter.copy(subsOnly = false)) { if (it.subs) "With subs" else null },
                    filterOptions(all, filter.copy(source = null)) { it.source },
                )
            }
            FilterRow("Resolution", options[0], filter.quality) { filter = filter.copy(quality = it) }
            FilterRow("Codec", options[1], filter.codec, label = ::codecLabel) { filter = filter.copy(codec = it) }
            FilterRow("Format", options[2], filter.format) { filter = filter.copy(format = it) }
            FilterRow("Subtitles", options[3], if (filter.subsOnly) "With subs" else null) { filter = filter.copy(subsOnly = it != null) }
            FilterRow("Source", options[4], filter.source, label = { if (it == "Link") "Direct link" else it }) { filter = filter.copy(source = it) }

            // For another app: links that need no headers first, since a shared link can't carry them.
            // Worked out when the scan, the chips or the preferences change, not on every redraw of the sheet.
            val plan = remember(scan, filter, prefs, external) {
                scan.episodes.mapNotNull { ep ->
                    scan.found[ep.id]?.let { hits ->
                        val from = if (external) hits.filter { it.headers.isEmpty() }.ifEmpty { hits } else hits
                        ep to if (filter == BatchFilter()) preferredPick(from, prefs) else batchPick(from, filter, prefs.direct, prefs)
                    }
                }
            }
            val picks = plan.mapNotNull { (ep, hit) -> hit?.let { ep to it } }
            val missing = plan.filter { it.second == null }.map { it.first.episode }
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
            if (showPlan) plan.forEach { (ep, hit) ->
                val t = hit?.let(::traitsOf)
                Text(
                    "${episodeTag(ep.season, ep.episode)}  " + if (hit == null) "—" else
                        listOfNotNull(hit.quality, t?.codec?.takeIf { it != UNKNOWN }?.let(::codecLabel), t?.format?.takeIf { it != UNKNOWN }, bytes(hit.size).takeIf { hit.size > 0 }, hit.addon.ifEmpty { null })
                            .joinToString(" · "),
                    Modifier.padding(top = 6.dp), style = Type.meta.copy(color = if (hit == null) Ink.faint else Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            val many = "${picks.size} episode${if (picks.size == 1) "" else "s"}"
            Button(
                when {
                    picks.isEmpty() -> "Nothing matches"
                    sending -> "Getting fresh links…"
                    external -> "Send $many to a downloader"
                    else -> "Download $many"
                },
                {
                    if (picks.isEmpty()) return@Button
                    if (external) {
                        if (sending) return@Button
                        sending = true
                        // Another app's downloader: every pick's link at once, in episode order — each asked for
                        // again first, since the ones found while the season was checked may have expired already.
                        scope.launch {
                            val gate = Semaphore(3)
                            val fresh = kotlinx.coroutines.coroutineScope {
                                picks.map { (ep, hit) ->
                                    async {
                                        gate.withPermit {
                                            refreshPick(SeasonBatch.sources(title, ep), "", hit.addon.ifEmpty { null }, hit.name, hit.quality) ?: hit
                                        }
                                    }
                                }.map { it.await() }
                            }
                            sending = false
                            if (sendLinks(context, fresh, "${title.title} · season $season")) onDismiss()
                            else say("No app on this phone can take those links.")
                        }
                        return@Button
                    }
                    SeasonBatch.download(title, picks)
                    say("Downloading $many of season $season — see Downloads")
                    onDismiss()
                },
                Modifier.padding(top = 16.dp).fillMaxWidth(), icon = if (external) L.drawable.lucide_ic_external_link else L.drawable.lucide_ic_download,
                fill = Ink.download.takeIf { picks.isNotEmpty() },
            )
            if (scan.done) Solo(Modifier.padding(top = 10.dp)) { m -> Chip("Ask every source again", false, onClick = { SeasonBatch.begin(title, season, again = true) }, m, compact = true) }
        }
    }
}

private fun codecLabel(c: String) = when (c) {
    "x265" -> "x265 (HEVC)"
    "x264" -> "x264 (AVC)"
    else -> c
}

/** One filter: its name, then Any and each value found with its count. */
@Composable
fun FilterRow(name: String, options: List<Pair<String, Int>>, chosen: String?, label: (String) -> String = { it }, onPick: (String?) -> Unit) {
    if (options.isEmpty() && chosen == null) return
    Text(name, Modifier.padding(top = 8.dp, bottom = 6.dp), style = Type.overline.copy(color = Ink.faint))
    SpreadRow {
        Chip("Any", chosen == null, onClick = { onPick(null) }, compact = true)
        options.forEach { (value, n) ->
            Chip("${label(value)} · $n", chosen == value, onClick = { onPick(if (chosen == value) null else value) }, compact = true)
        }
    }
}

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

    /** An episode's sources, as the title page finds them. */
    suspend fun sources(title: Title, ep: Episode): List<Hit> = runCatching {
        CsStreams.withPlugins(title, ep, {}) { onHits -> searchEpisode(title, ep.season, ep.episode, onHits) }
    }.getOrDefault(emptyList())

    /** Starts gathering the season's sources, unless that's already under way or done for this season. */
    fun begin(title: Title, season: Int, again: Boolean = false) {
        val now = _scan.value
        if (!again && now != null && now.titleImdb == title.imdb && now.season == season) return
        val episodes = title.episodes.filter { it.season == season }.sortedBy { it.episode }
        job?.cancel()
        _scan.value = SeasonScan(title.imdb, season, episodes)
        val gate = Semaphore(AT_ONCE)
        job = AppScope.launch {
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
        picks.forEach { (ep, hit) -> Downloads.add(downloadJob(hit, title, ep)) }
        return picks.size
    }
}

/**
 * Download a whole season: filters over the sources found for every episode — resolution, codec, format,
 * built-in subtitles, source — each offering only what was found, and what each episode would get.
 */
@Composable
fun BatchSheet(title: Title, season: Int, say: (String) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(title.imdb, season) { SeasonBatch.begin(title, season) }
    val scanned by SeasonBatch.scan.collectAsState()
    val scan = scanned?.takeIf { it.titleImdb == title.imdb && it.season == season }
    var filter by remember(title.imdb, season) { mutableStateOf(BatchFilter()) }
    // Opens with Settings → Preferred download chosen, each where the season has it, once its sources are in.
    var preset by remember(title.imdb, season) { mutableStateOf(false) }
    LaunchedEffect(scan?.done) {
        val s = scan ?: return@LaunchedEffect
        if (!s.done || preset) return@LaunchedEffect
        preset = true
        // Already chosen by hand while the season was being checked: left as chosen.
        if (filter != BatchFilter()) return@LaunchedEffect
        val st = com.torrentviewer.core.SettingsStore.value.value
        val want = DownloadPrefs(st.dlQuality, st.dlCodec, st.dlFormat, st.dlSubs, st.dlSource).asFilter()
        val all = s.found.values.flatten()
        fun has(f: BatchFilter) = all.any { batchable(it) && f.accepts(traitsOf(it)) }
        // Each preference kept only if something is left with it and the ones before it.
        var f = BatchFilter()
        listOf<(BatchFilter) -> BatchFilter>(
            { it.copy(quality = want.quality) }, { it.copy(codec = want.codec) }, { it.copy(format = want.format) },
            { it.copy(subsOnly = want.subsOnly) }, { it.copy(source = want.source) },
        ).forEach { step -> step(f).takeIf(::has)?.let { f = it } }
        filter = f
    }
    var showPlan by remember { mutableStateOf(false) }

    BottomSheet("Download season $season", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            if (scan == null) return@Column
            val all = scan.found.values.flatten()
            if (!scan.done) Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 20.dp)
                Text("   Checking episodes · ${scan.checked} of ${scan.episodes.size}", style = Type.meta.copy(color = Ink.muted))
            }
            if (scan.done && all.none(::batchable)) {
                Note("Nothing downloadable was found for this season's episodes.")
                return@Column
            }

            // Each row offers what the others leave possible; tapping the chosen value again clears it.
            FilterRow("Resolution", filterOptions(all, filter.copy(quality = null)) { it.quality }, filter.quality) { filter = filter.copy(quality = it) }
            FilterRow("Codec", filterOptions(all, filter.copy(codec = null)) { it.codec }, filter.codec, label = ::codecLabel) { filter = filter.copy(codec = it) }
            FilterRow("Format", filterOptions(all, filter.copy(format = null)) { it.format }, filter.format) { filter = filter.copy(format = it) }
            FilterRow(
                "Subtitles", filterOptions(all, filter.copy(subsOnly = false)) { if (it.subs) "With subs" else null },
                if (filter.subsOnly) "With subs" else null,
            ) { filter = filter.copy(subsOnly = it != null) }
            FilterRow("Source", filterOptions(all, filter.copy(source = null)) { it.source }, filter.source, label = { if (it == "Link") "Direct link" else it }) { filter = filter.copy(source = it) }

            val plan = scan.episodes.mapNotNull { ep -> scan.found[ep.id]?.let { ep to batchPick(it, filter) } }
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
                "Per episode: ${if (filter.codec == null) "HEVC (x265) first, then " else ""}the smallest file — nothing under ${bytes(MIN_EPISODE_BYTES)}.",
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

            Button(
                if (picks.isEmpty()) "Nothing matches" else "Download ${picks.size} episode${if (picks.size == 1) "" else "s"}",
                {
                    if (picks.isEmpty()) return@Button
                    SeasonBatch.download(title, picks)
                    say("Downloading ${picks.size} episodes of season $season — see Downloads")
                    onDismiss()
                },
                Modifier.padding(top = 16.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_download, primary = picks.isNotEmpty(),
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
private fun FilterRow(name: String, options: List<Pair<String, Int>>, chosen: String?, label: (String) -> String = { it }, onPick: (String?) -> Unit) {
    if (options.isEmpty() && chosen == null) return
    Text(name, Modifier.padding(top = 8.dp, bottom = 6.dp), style = Type.overline.copy(color = Ink.faint))
    SpreadRow {
        Chip("Any", chosen == null, onClick = { onPick(null) }, compact = true)
        options.forEach { (value, n) ->
            Chip("${label(value)} · $n", chosen == value, onClick = { onPick(if (chosen == value) null else value) }, compact = true)
        }
    }
}

package com.torrentviewer.mobile

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.composables.icons.lucide.R as L

/** A batch season download under way, or done: how far it got, and which episodes had nothing at the resolution. */
data class BatchState(
    val titleImdb: String,
    val season: Int,
    val quality: String,
    val total: Int,
    val done: Int = 0,
    val started: Int = 0,
    val missed: List<Int> = emptyList(),
    val last: String? = null,
    val finished: Boolean = false,
)

/**
 * Downloads a whole season at one resolution: every episode's sources are asked in turn (addons and
 * plugins, as the title page does), [batchPick] keeps one, and it goes to the torrent downloader or,
 * for a direct link, to Android's. Runs on even when its sheet is closed.
 */
object SeasonBatch {
    private val _state = MutableStateFlow<BatchState?>(null)
    val state: StateFlow<BatchState?> = _state
    private var job: Job? = null

    /** An episode's sources, as the title page finds them. */
    suspend fun sources(title: Title, ep: Episode): List<Hit> = runCatching {
        CsStreams.withPlugins(title, ep, {}) { onHits -> searchEpisode(title, ep.season, ep.episode, onHits) }
    }.getOrDefault(emptyList())

    fun start(context: Context, title: Title, season: Int, quality: String) {
        val episodes = title.episodes.filter { it.season == season }.sortedBy { it.episode }
        if (episodes.isEmpty()) return
        job?.cancel()
        _state.value = BatchState(title.imdb, season, quality, episodes.size)
        job = AppScope.launch {
            for (ep in episodes) {
                val pick = batchPick(sources(title, ep), quality)
                val tag = episodeTag(ep.season, ep.episode)
                // Torrents, files and HLS playlists all go to the Downloads tab.
                if (pick != null) Downloads.add(downloadJob(pick, title, ep))
                _state.value = _state.value?.let { s ->
                    s.copy(
                        done = s.done + 1,
                        started = s.started + if (pick != null) 1 else 0,
                        missed = if (pick == null) s.missed + ep.episode else s.missed,
                        last = pick?.let { "$tag · ${listOfNotNull(if (isHevc(it)) "HEVC" else null, bytes(it.size).takeIf { _ -> it.size > 0 }).joinToString(" · ").ifEmpty { it.addon }}" },
                    )
                }
            }
            _state.value = _state.value?.copy(finished = true)
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value?.copy(finished = true)
    }
}

/**
 * The batch download sheet: the resolutions the season's first episode has, then, once one is picked,
 * the run's progress. The same sheet, chips and buttons as the rest of the title page.
 */
@Composable
fun BatchSheet(title: Title, season: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val run by SeasonBatch.state.collectAsState()
    val mine = run?.takeIf { it.titleImdb == title.imdb && it.season == season }
    var qualities by remember(title.imdb, season) { mutableStateOf<List<Pair<String, Int>>?>(null) }
    LaunchedEffect(title.imdb, season) {
        val first = title.episodes.filter { it.season == season }.minByOrNull { it.episode } ?: return@LaunchedEffect run { qualities = emptyList() }
        qualities = batchQualities(SeasonBatch.sources(title, first))
    }
    BottomSheet("Batch download season $season", onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            if (mine != null) {
                Text(
                    if (mine.finished) "Done — started ${mine.started} of ${mine.total} at ${mine.quality}"
                    else "${mine.quality} · ${mine.done} of ${mine.total} episodes checked · ${mine.started} downloading",
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                )
                mine.last?.let { Text("Latest: $it", Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Ink.faint)) }
                if (mine.missed.isNotEmpty()) {
                    Note("Nothing at ${mine.quality} for episode${if (mine.missed.size == 1) "" else "s"} ${mine.missed.joinToString(", ")}.", Modifier.padding(top = 8.dp))
                }
                Text("Each episode appears in the Downloads tab as it is found.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
                if (!mine.finished) Button("Stop", { SeasonBatch.cancel() }, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_x)
                if (!mine.finished) return@Column
                Text("Start another", Modifier.padding(top = 18.dp, bottom = 8.dp), style = Type.overline.copy(color = Ink.faint))
            }
            val list = qualities
            when {
                list == null -> Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 24.dp)
                    Text("   Checking which resolutions there are…", style = Type.body)
                }
                list.isEmpty() -> Note("No downloadable single-episode sources found for this season.")
                else -> {
                    Text(
                        "Pick a resolution. For each episode the smallest file at it is kept, HEVC (x265) first, " +
                            "skipping anything under ${bytes(MIN_EPISODE_BYTES)} — trailers and samples.",
                        Modifier.padding(bottom = 12.dp), style = Type.meta.copy(color = Ink.faint),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { (q, n) ->
                            Chip("$q · $n", false, onClick = { SeasonBatch.start(context, title, season, q) }, icon = L.drawable.lucide_ic_download)
                        }
                    }
                }
            }
        }
    }
}

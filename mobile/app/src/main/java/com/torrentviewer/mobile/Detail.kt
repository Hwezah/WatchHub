package com.torrentviewer.mobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import com.torrentviewer.core.awardsOf
import com.torrentviewer.core.WatchedBadge
import com.torrentviewer.core.CertificateBadge
import com.torrentviewer.core.castPhoto
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ep
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Facts
import com.torrentviewer.core.Health
import com.torrentviewer.core.Hit
import com.torrentviewer.core.History
import com.torrentviewer.core.latestPerTitle
import com.torrentviewer.core.Ink
import com.torrentviewer.core.LANGS
import com.torrentviewer.core.langName
import com.torrentviewer.core.grain
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Meta
import com.torrentviewer.core.NewEpisodes
import com.torrentviewer.core.Note
import com.torrentviewer.core.OnHits
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Scope
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.TorBox
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.episodeTag
import com.torrentviewer.core.episodeProgress
import com.torrentviewer.core.health
import com.torrentviewer.core.heroScrims
import com.torrentviewer.core.quickest
import com.torrentviewer.core.reviewLinks
import com.torrentviewer.core.runtimeSeconds
import com.torrentviewer.core.searchEpisode
import com.torrentviewer.core.absoluteNumbers
import com.torrentviewer.core.continueEpisode
import com.torrentviewer.core.episodesFromReleases
import com.torrentviewer.core.looksLikeSeries
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.seasonsIn
import com.torrentviewer.core.searchStreams
import com.torrentviewer.core.Addons
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** The exact strings quality() returns, best first. */
private val QUALITY_ORDER = listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "CAM")

/** Episodes listed before "Show all". */
private const val FIRST_EPISODES = 10

/** Past this the list is noise; the best are at the top. */
private const val MAX_SOURCES = 60

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * One title: the TV's page on a phone. Upright, the backdrop heads the page with the logo
 * on it; turned, it sits behind on the right and the words run down the left, as on the TV.
 */
@Composable
fun DetailScreen(initial: Title, watch: Watch, onOrder: (com.torrentviewer.core.WatchOrder) -> Unit, onOpen: (Title) -> Unit, onPerson: (com.torrentviewer.core.CastMember) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var title by remember(initial.imdb) { mutableStateOf(initial) }
    /** The episode list's lookups are done: a series still without one is searched as a whole, and its releases name the episodes. */
    var episodesChecked by remember(initial.imdb) { mutableStateOf(false) }
    LaunchedEffect(initial.imdb) {
        // Some addon catalogues list a series as a film; Cinemeta then has no film by that id, but has the series.
        val found = Meta.title(initial.imdb, initial.kind) ?: if (initial.kind == "movie") Meta.title(initial.imdb, "tv")?.takeIf { it.isSeries } else null
        found?.let { title = it }
        // A new series Cinemeta lists no episodes for yet: TMDB's, so it has episodes and a season download.
        val t = title
        if (t.isSeries && t.episodes.isEmpty()) com.torrentviewer.core.Tmdb.episodes(t.imdb).takeIf { it.isNotEmpty() }?.let { title = t.copy(episodes = it) }
        episodesChecked = true
    }
    // Rating, cast and what's like it. Without a key or a connection the page is as it was.
    val tmdb by produceState<com.torrentviewer.core.TmdbTitle?>(null, initial.imdb) { value = com.torrentviewer.core.Tmdb.title(initial.imdb, initial.kind) }
    val certificate = tmdb?.certificate
    if (com.torrentviewer.core.Kids.on && com.torrentviewer.core.tooOldForKids(certificate)) {
        TooOldForKids(title, certificate.orEmpty(), onBack)
        return
    }
    val saved by SavedStore.items.collectAsState()
    val history by History.items.collectAsState()
    val inList = saved.any { it.imdb == title.imdb && it.list == "later" }
    val liked = saved.any { it.imdb == title.imdb && it.list == "favorite" }

    val seasons = remember(title.episodes) { title.episodes.map { it.season }.filter { it > 0 }.distinct().sorted() }
    /** Where this series was left: the episode after the last one finished, or the one stopped part-way. */
    // What Stremio has seen counts too: after a reinstall, this phone's own history is empty.
    // And what Simkl and MDBList have seen, from any app that tells them.
    val stremio by com.torrentviewer.core.WatchedEverywhere.collectAsState(com.torrentviewer.core.Stremio.library.value)
    val continueAt = remember(history, title.episodes, stremio) { continueEpisode(title, history, stremio) }
    var season by rememberSaveable(title.imdb) { mutableStateOf<Int?>(null) }
    // Not while the episode list is still loading: that would throw away the season being come back to.
    LaunchedEffect(seasons) {
        if (seasons.isNotEmpty() && (season == null || season !in seasons)) season = continueAt?.season?.takeIf { it in seasons } ?: seasons.first()
    }
    val episodes = title.episodes.filter { it.season == season }
    // Long-running anime: every episode by its number through the show, a hundred at a time.
    val through = title.numberedThrough
    val numbered = remember(title.episodes) {
        if (!through) emptyList() else absoluteNumbers(title.episodes).toList()
    }
    val blocks = remember(numbered) { numbered.chunked(100) }
    var block by rememberSaveable(title.imdb) { mutableStateOf(0) }
    // Opens on the hundred the series was left in.
    LaunchedEffect(blocks) {
        if (block == 0) continueAt?.let { c -> blocks.indexOfFirst { b -> b.any { it.first.id == c.id } }.takeIf { it > 0 }?.let { block = it } }
    }
    var numberQuery by rememberSaveable(title.imdb) { mutableStateOf("") }
    val progress = remember(history, title.imdb, stremio, title.episodes) { episodeProgress(history, title.imdb, stremio, title.episodes) }

    var episode by remember(title.imdb) { mutableStateOf<Episode?>(null) }
    // Back from the player: the episode that was picked, once the list is in.
    var episodeId by rememberSaveable(title.imdb) { mutableStateOf<String?>(null) }
    LaunchedEffect(title.episodes) { if (episode == null) episode = title.episodes.find { it.id == episodeId } }
    var hits by remember(title.imdb) { mutableStateOf<List<Hit>?>(null) }
    var busy by remember(title.imdb) { mutableStateOf(false) }
    var quality by remember(title.imdb) { mutableStateOf<String?>(null) }
    /** Which request the visible sources belong to; answers arrive out of order. */
    var ticket by remember { mutableIntStateOf(0) }
    // Torrents under MIN_SEEDS (10) seeds are left out (Settings → Streaming), unless asked for on this page.
    val settingsNow by com.torrentviewer.core.SettingsStore.value.collectAsState()
    val cachedIds by TorBox.cached.collectAsState()
    var showFew by remember(title.imdb) { mutableStateOf(false) }
    // Settings → Streaming's left-out resolutions, codecs and formats: gone from the list, its pills and every download pick.
    // Worked out only when what they depend on changes, not on every redraw: hundreds of sources, each read by several regexes.
    val leaveOut = settingsNow.leaveOut
    val hideFew = settingsNow.hideFewSeeds
    val cachedLower = remember(cachedIds) { cachedIds.mapTo(HashSet()) { c -> c.lowercase() } }
    // distinctBy: a list crashes on two rows with one id, wherever the sources came from.
    val kept = remember(hits, leaveOut, cachedLower) { hits?.filter { allowed(it, leaveOut, cachedLower) }?.distinctBy { it.id } }
    val atQuality = remember(kept, quality) { kept.orEmpty().filter { quality == null || it.quality == quality } }
    val shown = remember(atQuality, hideFew, showFew, cachedLower) { if (hideFew && !showFew) atQuality.filterNot { fewSeeds(it, cachedLower) } else atQuality }
    val hiddenFew = atQuality.size - shown.size
    var sheet by remember { mutableStateOf(false) }
    /** Download on a film or an episode: its releases, to pick one from. */
    var downloadSheet by remember { mutableStateOf(false) }
    var reviews by remember { mutableStateOf(false) }
    /** The releases' language, by its code; empty is any language. */
    var lang by rememberSaveable(title.imdb) { mutableStateOf("") }
    fun searchLang() = LANGS.find { it.code == lang }
    var pickingLang by remember { mutableStateOf(false) }
    var expanded by remember(title.imdb) { mutableStateOf(false) }
    var allEpisodes by rememberSaveable(title.imdb, season) { mutableStateOf(false) }
    /** "Download season" open: the season, and its packs (null while still looking). */
    var seasonPacks by remember(title.imdb) { mutableStateOf<Pair<Int, List<Hit>?>?>(null) }
    /** The trailer playing in its card, over the page: its YouTube id. */
    var trailerId by remember(title.imdb) { mutableStateOf<String?>(null) }
    /** The batch download sheet's season, while it is open. */
    var batchFor by remember(title.imdb) { mutableStateOf<Int?>(null) }
    /** A source's ⬇ asked: where to download it (this app or another), for that source and its episode. */
    var downloadFor by remember(title.imdb) { mutableStateOf<Pair<Hit, Episode?>?>(null) }
    /** An episode's ⬇ or a season's Download asked: this app's downloader, or another app's. */
    var askEpisode by remember(title.imdb) { mutableStateOf<Episode?>(null) }
    var askSeason by remember(title.imdb) { mutableStateOf<Int?>(null) }
    /** The batch sheet hands its picks to another app's downloader instead of this one's. */
    var batchExternal by remember(title.imdb) { mutableStateOf(false) }
    /** A source's ⋯ open: the source, and the episode it is for. */
    var actions by remember(title.imdb) { mutableStateOf<Pair<Hit, Episode?>?>(null) }
    val list = rememberLazyListState()

    fun findSources(ep: Episode?): Deferred<List<Hit>> {
        val mine = ++ticket
        busy = true
        hits = null
        quality = null
        episode = ep
        episodeId = ep?.id
        val run: suspend (OnHits) -> List<Hit> = { onHits ->
            try {
                // The Stremio addons' streams and the CloudStream plugins', side by side.
                com.torrentviewer.mobile.cs.CsStreams.withPlugins(title, ep, onHits) { fromAddons ->
                    if (ep != null) searchEpisode(title, ep.season, ep.episode, fromAddons, searchLang())
                    else searchStreams(title.title, title.imdb, title.year?.take(4), lang = searchLang(), onHits = fromAddons, type = title.type)
                }
            } finally {
                if (mine == ticket) busy = false
            }
        }
        return scope.quickest(run) { partial -> if (mine == ticket) hits = partial }.also { d ->
            scope.launch { val all = d.await(); if (mine == ticket && hits.isNullOrEmpty()) hits = all }
        }
    }

    /** The sources' ↻: every addon and plugin asked again, not their answers kept from earlier. */
    fun reloadSources(ep: Episode?) {
        if (busy) return
        com.torrentviewer.core.forgetStreams()
        scope.launch {
            com.torrentviewer.mobile.cs.CsPlugins.forgetPages()
            findSources(ep)
        }
    }

    /** Keep a release on the phone, in the Downloads tab. */
    fun keep(hit: Hit, ep: Episode?) {
        Downloads.add(downloadJob(hit, title, ep))
        watch.notes.tryEmit("Downloading ${ep?.let { "${title.title} · ${episodeTag(it.season, it.episode)}" } ?: title.title} — see Downloads")
    }

    /** Nothing found; with a language picked, that is the likely reason, so it says so. */
    fun noneFound(what: String) = when {
        !Addons.hasStreams && com.torrentviewer.mobile.cs.CsPlugins.providers().isEmpty() -> Addons.NONE_YET
        lang.isEmpty() -> "No sources found for $what."
        else -> "No $what in ${langName(lang)} — change the language and try again."
    }

    /** An episode's Download: its releases to pick from, as a film's. */
    /**
     * One tap, one download: the release that best fits Settings → Preferred download, from the sources
     * already on screen when they're this one's, else a fresh search — decided as soon as a pick as preferred
     * is in ([SeasonBatch.gather]), or after [QUICK_PICK_MS] with what has come.
     */
    fun quickDownload(ep: Episode?, external: Boolean = false) {
        val prefs = downloadPrefs()
        val what = ep?.let { "${title.title} · ${episodeTag(it.season, it.episode)}" } ?: title.title
        val loaded = hits?.takeIf { !busy && episode?.id == ep?.id }
        if (loaded == null) watch.notes.tryEmit("Finding the best ${prefs.quality.ifEmpty { "source" }} for $what…")
        // Not this page's job: leaving the page mustn't drop a download that was asked for.
        com.torrentviewer.core.AppScope.launch {
            val all = loaded ?: SeasonBatch.gather(QUICK_PICK_MS) { onHits ->
                com.torrentviewer.mobile.cs.CsStreams.withPlugins(title, ep, onHits) { fromAddons ->
                    if (ep != null) searchEpisode(title, ep.season, ep.episode, fromAddons)
                    else searchStreams(title.title, title.imdb, title.year?.take(4), onHits = fromAddons, type = title.type)
                }
            }
            val pick = preferredPick(allowedHits(all), prefs)
            if (pick == null) watch.notes.tryEmit("Nothing that can be saved was found for $what.")
            else if (external) {
                if (!openInDownloader(context, pick, what)) watch.notes.tryEmit("No app on this phone can take that link.")
            } else {
                Downloads.add(downloadJob(pick, title, ep))
                watch.notes.tryEmit("Downloading $what · ${describePick(pick)}")
            }
        }
    }

    /** An episode's ⬇: first, which downloader; then the release Settings → Preferred download picks. */
    fun downloadEpisode(ep: Episode) { askEpisode = ep }

    /**
     * A whole season in one go: the best pack that is that season, else a complete-series
     * pack — the download then takes only that season's episodes out of it.
     */
    /** A season pack is this season alone, or a complete series that holds it; the download takes only this season's episodes. */
    fun packsOf(hits: List<Hit>, s: Int) = hits.filter {
        !it.direct && ((it.scope == Scope.SEASON && parseEpisode(it.release) == null && s in seasonsIn(it.release)) || it.scope == Scope.COMPLETE)
    }

    /** The season's packs, listed to pick one from, as they arrive. */
    fun downloadSeason(s: Int) {
        seasonPacks = s to null
        scope.launch {
            val first = title.episodes.firstOrNull { it.season == s }?.episode ?: 1
            val all = runCatching {
                searchEpisode(title, s, first, { partial -> if (seasonPacks?.first == s) seasonPacks = s to packsOf(partial, s) }, searchLang())
            }.getOrDefault(emptyList())
            if (seasonPacks?.first == s) seasonPacks = s to packsOf(all, s)
        }
    }

    fun keepSeason(hit: Hit, s: Int) {
        seasonPacks = null
        Downloads.add(downloadJob(hit, title).copy(season = s))
        watch.notes.tryEmit("Downloading season $s of ${title.title} — see Downloads")
    }

    // Opening a series' page is seeing what is out: its new-episode count starts again from here.
    LaunchedEffect(title.imdb, title.episodes.size) { NewEpisodes.markSeen(title) }

    // A film has one thing to look for: look on arrival.
    // A series with no episode list anywhere is searched as a whole, as a film is.
    LaunchedEffect(title.imdb, title.kind, lang, episodesChecked) {
        if (!title.isSeries || (episodesChecked && title.episodes.isEmpty())) findSources(null)
    }
    // Once the whole-title search is in: a "film" whose releases are a series' becomes that series, and a
    // series with no episode list gets one from what its releases name — so either has episodes and a season download.
    LaunchedEffect(busy, title.imdb, title.kind) {
        val found = hits
        if (busy || found.isNullOrEmpty() || episode != null) return@LaunchedEffect
        val names = found.flatMap { listOf(it.release, it.name) }.filter { it.isNotBlank() }
        val t = title
        if (!t.isSeries) {
            if (!looksLikeSeries(found.map { it.release.ifBlank { it.name } })) return@LaunchedEffect
            val series = Meta.title(t.imdb, "tv")?.takeIf { it.isSeries } ?: t.copy(kind = "tv", type = "series")
            val eps = series.episodes.ifEmpty { com.torrentviewer.core.Tmdb.episodes(series.imdb) }.ifEmpty { episodesFromReleases(series.imdb, names) }
            if (eps.isEmpty()) return@LaunchedEffect
            hits = null
            title = series.copy(episodes = eps)
        } else if (t.episodes.isEmpty()) {
            val eps = episodesFromReleases(t.imdb, names)
            if (eps.isNotEmpty()) { hits = null; title = t.copy(episodes = eps) }
        }
    }

    /** What the big button plays: the episode last looked at, where the series was left, or the first of the season. */
    val upNext = episode ?: continueAt ?: if (through) numbered.firstOrNull()?.first else episodes.firstOrNull()

    fun playEpisode(ep: Episode) = scope.launch {
        val ready = if (episode?.id == ep.id && shown.isNotEmpty()) shown else findSources(ep).await()
        ready.firstOrNull()?.let { watch.stream(it, title, episodeTag(ep.season, ep.episode), ep) }
            ?: watch.notes.tryEmit(noneFound(episodeTag(ep.season, ep.episode)))
    }

    val wide = isWide()
    Box(Modifier.fillMaxSize()) {
        if (wide) Artwork(title.wide, title.title, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.9f).heroScrims(), fill = false)

        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                if (!wide) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        Artwork(title.wide, title.title, Modifier.fillMaxSize().bottomScrim(0.6f), fill = false)
                        // Centred under the art, which is cropped from its centre.
                        TitleMark(title, Modifier.align(Alignment.BottomCenter).padding(horizontal = EDGE).widthIn(max = 280.dp), maxHeight = 72.dp, style = Type.display, centred = true)
                    }
                }
                Column(
                    Modifier.padding(horizontal = EDGE).then(
                        if (wide) Modifier.statusBarsPadding().padding(top = 56.dp).widthIn(max = 460.dp) else Modifier.padding(top = 10.dp),
                    ),
                ) {
                    if (wide) TitleMark(title, Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp), maxHeight = 80.dp, style = Type.display)
                    if (wide) Facts(title.copy(genres = title.genres.take(2)))
                    else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Facts(title.copy(genres = title.genres.take(2))) }
                    // On any release here, or on a connected account from another device; a series shows it per episode.
                    val finished = history.filter { it.imdb == title.imdb && it.ratio >= com.torrentviewer.core.WATCHED_AT }.maxOfOrNull { it.at }
                    val seenIt = !title.isSeries && (finished != null || title.imdb in stremio.films)
                    // Centred on an upright phone, under the centred facts; from the start elsewhere.
                    if (seenIt || certificate != null) Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = if (isPortraitPhone()) Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        certificate?.let { CertificateBadge(it) }
                        if (seenIt) WatchedBadge(finished)
                    }
                    // The list first, as someone chose its order; TMDB for any other film. A series has its seasons.
                    val inOrder by produceState(com.torrentviewer.core.WatchOrders.of(title.imdb), title.imdb) {
                        if (value == null && !title.isSeries) value = com.torrentviewer.core.Tmdb.orderOf(title.imdb)
                    }
                    Spacer(Modifier.height(16.dp))
                    // Part-way through — whichever release or episode was last played — Resume leads, Start over under it.
                    val last = remember(history, title.imdb) { latestPerTitle(history.filter { it.imdb == title.imdb }).firstOrNull() }
                    when {
                        // An episode left at its end credits is watched: Play goes on to the next instead.
                        last != null && last.ratio > 0.02 && last.ratio < (if (title.isSeries) com.torrentviewer.core.WATCHED_AT else 0.95) -> {
                            Button(
                                "Resume" + (whereLeft(last)?.let { "  ·  $it" } ?: ""),
                                { watch.resume(last) },
                                Modifier.fillMaxWidth(),
                                icon = L.drawable.lucide_ic_play,
                                primary = true,
                            )
                            Button("Start over", {
                                val i = last.lastFileIndex ?: 0
                                History.setPosition(last.uri, i, 0.0)
                                watch.resume(History.find(last.uri) ?: last)
                            }, Modifier.fillMaxWidth().padding(top = 8.dp), icon = L.drawable.lucide_ic_rotate_ccw)
                        }
                        // Left part-way on Stremio, never here: still Resume — the release picked starts at that spot.
                        !title.isSeries -> Button(
                            label = when {
                                shown.isNotEmpty() -> com.torrentviewer.core.resumeLabel(title.imdb, history, stremio) ?: "Play"
                                busy -> "Finding sources…"
                                else -> "No sources"
                            },
                            onClick = { shown.firstOrNull()?.let { watch.stream(it, title) } ?: watch.notes.tryEmit(if (busy) "Still finding sources…" else noneFound(title.title)) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = L.drawable.lucide_ic_play,
                            primary = true,
                        )
                        upNext != null -> Button(
                            label = if (busy && episode?.id == upNext.id && shown.isEmpty()) "Finding sources…"
                                else {
                                    val which = if (through) "episode ${numbered.find { it.first.id == upNext.id }?.second ?: upNext.episode}" else "S${upNext.season}:E${upNext.episode}"
                                    com.torrentviewer.core.resumeLabel(title.imdb, history, stremio, com.torrentviewer.core.Ep(upNext.season, upNext.episode))
                                        ?.let { "Resume $which" + it.removePrefix("Resume") } ?: "Play $which"
                                },
                            onClick = { playEpisode(upNext) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = L.drawable.lucide_ic_play,
                            primary = true,
                        )
                    }
                    title.plot?.let { plot ->
                        Tap({ expanded = !expanded }, Modifier.padding(top = 14.dp), RoundedCornerShape(0.dp)) {
                            Text(plot,style = Type.body.copy(color = Ink.text.copy(alpha = 0.85f)), maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (title.directors.isNotEmpty()) Crew(if (title.isSeries) "Creators" else "Director", title.directors.take(2), tmdb?.directors.orEmpty(), onPerson.takeUnless { com.torrentviewer.core.Kids.on })
                    // However many actions there are, they share the width evenly.
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        val share = Modifier.weight(1f)
                        // Green tick for My List, red heart for Like — each fills its disc when on.
                        Action(if (inList) L.drawable.lucide_ic_check else L.drawable.lucide_ic_plus, if (inList) "In My List" else "My List", { SavedStore.toggle(title) }, modifier = share, on = inList, onColor = Ink.good)
                        Action(L.drawable.lucide_ic_heart, if (liked) "Liked" else "Like", { SavedStore.toggle(title, "favorite") }, modifier = share, on = liked, onColor = Ink.accent)
                        // No Download here: each source row has its own ⬇ (one-tap, by Preferred download, is the episodes').
                        // Always here: Cinemeta's trailer, else TMDB's, played in the card over the page.
                        Action(L.drawable.lucide_ic_film, "Trailer", {
                            val known = title.trailer
                            if (known != null) trailerId = known
                            else scope.launch {
                                val found = com.torrentviewer.core.Tmdb.trailer(title.imdb, title.kind)
                                if (found != null) trailerId = found else watch.notes.tryEmit("No trailer found for ${title.title}")
                            }
                        }, modifier = share)
                        Action(L.drawable.lucide_ic_message_square_text, "Reviews", { reviews = true }, modifier = share)
                    }
                    // No language chip: sources are searched in any language (the owner's choice); the batch sheet's filters narrow them.
                    inOrder?.let { order -> FranchiseRail(order, title.imdb, { onOrder(order) }, onOpen, Modifier.padding(top = 20.dp)) }
                    val people = tmdb?.cast.orEmpty()
                    if (people.isNotEmpty()) PeopleRow(people, onPerson.takeUnless { com.torrentviewer.core.Kids.on })
                    else if (title.cast.isNotEmpty()) CastRow(title.cast, onPerson.takeUnless { com.torrentviewer.core.Kids.on })
                    title.awards?.let { AwardsCard(it) }
                    // Not for kids: recommendations have no rating until they're opened.
                    val similar = tmdb?.similar.orEmpty()
                    if (similar.isNotEmpty() && !com.torrentviewer.core.Kids.on) SimilarRail(similar, onOpen, Modifier.padding(top = 20.dp))
                }
            }

            if (title.isSeries && through) {
                item(key = "numbered") {
                    Column(Modifier.padding(top = 22.dp).widthIn(max = 720.dp)) {
                        Text("Episodes", Modifier.padding(start = EDGE, bottom = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
                        LazyRow(contentPadding = PaddingValues(horizontal = EDGE), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(blocks.indices.toList()) { b ->
                                Chip("${blocks[b].first().second}–${blocks[b].last().second}", b == block && numberQuery.isEmpty(), onClick = { block = b; numberQuery = "" })
                            }
                        }
                        Field(
                            numberQuery, { numberQuery = it.filter(Char::isDigit).take(4) }, "Episode number",
                            Modifier.padding(horizontal = EDGE).padding(top = 10.dp, bottom = 6.dp).fillMaxWidth(),
                            digits = true, leading = L.drawable.lucide_ic_search,
                        )
                    }
                }
                val shown = if (numberQuery.isEmpty()) blocks.getOrNull(block).orEmpty() else numbered.filter { it.second.toString().startsWith(numberQuery) }.take(100)
                items(shown, key = { "ep-" + it.first.id }) { (ep, n) ->
                    EpisodeRow(
                        ep,
                        number = n,
                        fallback = title.wide,
                        busy = busy && episode?.id == ep.id,
                        selected = episode?.id == ep.id,
                        seen = progress[Ep(ep.season, ep.episode)] ?: 0.0,
                        modifier = Modifier.widthIn(max = 720.dp),
                        onPlay = { playEpisode(ep) },
                        onSources = {
                            if (episode?.id != ep.id || hits == null) findSources(ep)
                            sheet = true
                        },
                        onDownload = { downloadEpisode(ep) },
                    )
                }
            } else if (title.isSeries && seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Column(Modifier.padding(top = 22.dp)) {
                        // The heading, with the season's download at the far end of the same line.
                        Row(Modifier.fillMaxWidth().padding(start = EDGE, end = EDGE - 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Episodes", Modifier.weight(1f), style = Type.title.copy(fontWeight = FontWeight.Bold))
                            season?.let { s ->
                                // The whole season, episode by episode, with filters over what was found.
                                Tap({ askSeason = s }, shape = RoundedCornerShape(6.dp)) {
                                    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        // As big as the "Episodes" heading beside it; the stacked layers say "all of them" (a whole season).
                                        Lc(L.drawable.lucide_ic_layers, tint = Ink.download, size = 24.dp)
                                        Text("  Download Season $s", style = Type.title.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                        // Spread end to end, first season at the left edge and last at the right; with many, the
                        // gaps close to 8 dp and the row scrolls sideways.
                        SpreadRow(Modifier.padding(horizontal = EDGE)) {
                            run { seasons.forEach { s ->
                                Chip("Season $s", s == season, onClick = {
                                    season = s
                                    // Retire whatever search is out: it belongs to the season being left.
                                    ticket++
                                    busy = false
                                    hits = null
                                    episode = null
                                })
                            } }
                        }
                    }
                }
                // Ten at a time: a long season is a wall of rows otherwise.
                items(if (allEpisodes) episodes else episodes.take(FIRST_EPISODES), key = { "ep-" + it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        fallback = title.wide,
                        busy = busy && episode?.id == ep.id,
                        selected = episode?.id == ep.id,
                        seen = progress[Ep(ep.season, ep.episode)] ?: 0.0,
                        modifier = Modifier.widthIn(max = 720.dp),
                        onPlay = { playEpisode(ep) },
                        onSources = {
                            if (episode?.id != ep.id || hits == null) findSources(ep)
                            sheet = true
                        },
                        onDownload = { downloadEpisode(ep) },
                    )
                }
                if (!allEpisodes && episodes.size > FIRST_EPISODES) item(key = "all-episodes") {
                    Button(
                        "Show all ${episodes.size} episodes",
                        { allEpisodes = true },
                        Modifier.padding(horizontal = EDGE, vertical = 8.dp).fillMaxWidth().widthIn(max = 720.dp),
                        icon = L.drawable.lucide_ic_list_video,
                    )
                }
            } else {
                sources(title, episode, kept, shown, busy, quality, { quality = it }, Modifier.widthIn(max = 720.dp), hiddenFew = hiddenFew, onShowFew = { showFew = true }, language = searchLang()?.name, onDownload = { downloadFor = it to null }, onMore = { actions = it to null }, onReload = { reloadSources(episode) }) { hit -> watch.stream(hit, title) }
            }
        }
        // Over the art, a round back button; once the page scrolls under it, a solid bar with the name.
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }
        StatusIconsOverArt(!wide && !scrolled)
        if (scrolled) {
            Row(Modifier.fillMaxWidth().background(Ink.bg).grain().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
                Text(title.title, Modifier.padding(start = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            BackButton(onBack)
        }
    }

    // An episode's releases come up over the page, rather than at the foot of a long list.
    if (sheet) {
        val ep = episode
        BottomSheet(ep?.let { "S${it.season}:E${it.episode} · ${it.name}" } ?: "Sources", onDismiss = { sheet = false }) {
            LazyColumn {
                sources(title, ep, kept, shown, busy, quality, { quality = it }, Modifier, heading = false, hiddenFew = hiddenFew, onShowFew = { showFew = true }, language = searchLang()?.name, onDownload = { hit -> downloadFor = hit to ep }, onMore = { actions = it to ep }, onReload = { reloadSources(ep) }) { hit ->
                    sheet = false
                    watch.stream(hit, title, ep?.let { episodeTag(it.season, it.episode) }, ep)
                }
            }
        }
    }
    // Download lists the releases to pick from, rather than keeping whichever came first.
    if (downloadSheet) {
        val ep = episode
        BottomSheet(ep?.let { "Download ${episodeTag(it.season, it.episode)}: pick a release" } ?: "Download: pick a release", onDismiss = { downloadSheet = false }) {
            LazyColumn {
                sources(title, ep, kept, shown, busy, quality, { quality = it }, Modifier, heading = false, hiddenFew = hiddenFew, onShowFew = { showFew = true }, language = searchLang()?.name, onDownload = { hit ->
                    downloadSheet = false
                    keep(hit, ep)
                }) { hit ->
                    if (!canSave(hit)) watch.notes.tryEmit("That one can't be saved — pick another source.")
                    else {
                        downloadSheet = false
                        keep(hit, ep)
                    }
                }
            }
        }
    }

    // The trailer plays here, in a card over the page, rather than in YouTube.
    trailerId?.let { id -> TrailerSheet(id, title.title) { trailerId = null } }

    batchFor?.let { s -> BatchSheet(title, s, say = { watch.notes.tryEmit(it) }, onDismiss = { batchFor = null }, external = batchExternal) }

    // What one-tap downloads go by, for the choice's line: "720p · x265 · MKV".
    fun preferred(): String = com.torrentviewer.core.SettingsStore.value.value.let { st ->
        listOf(st.dlQuality, st.dlCodec, st.dlFormat, if (st.dlSubs) "subtitles" else "", if (st.dlSource == "Link") "direct link" else st.dlSource.lowercase())
            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "the best that can be saved" }
    }
    // Said in green on both choices: the resolution comes from Settings, and that's where to change it.
    fun resolutionNote(): String {
        val q = com.torrentviewer.core.SettingsStore.value.value.dlQuality
        return "The Resolution Picked By Default Is ${q.ifEmpty { "Any (The Best Found)" }}, As Set In Settings → Preferred Download. Change It There For A Different Resolution!"
    }
    askEpisode?.let { ep ->
        DownloadChoice(
            "${episodeTag(ep.season, ep.episode)} · picks your preferred ${preferred()}, or the nearest found",
            onInternal = { quickDownload(ep) },
            onExternal = { askEpisode = null; quickDownload(ep, external = true) },
            onDismiss = { askEpisode = null },
            disclaimer = resolutionNote(),
        )
    }
    askSeason?.let { s ->
        DownloadChoice(
            "Season $s · every episode gets your preferred ${preferred()}, or the nearest found",
            onInternal = { batchExternal = false; batchFor = s },
            onExternal = { askSeason = null; batchExternal = true; batchFor = s },
            onDismiss = { askSeason = null },
            internalNote = "Pick resolution, codec, format, subtitles and source, see what each episode gets, then everything goes to the Downloads tab.",
            externalNote = "The same filters, then every episode's link goes to 1DM, ADM or another downloader in one go.",
            disclaimer = resolutionNote(),
        )
    }

    downloadFor?.let { (hit, ep) ->
        DownloadChoice(
            hit, ep?.let { "${title.title} · ${episodeTag(it.season, it.episode)}" } ?: title.title,
            onInternal = { keep(hit, ep) }, say = { watch.notes.tryEmit(it) }, onDismiss = { downloadFor = null },
        )
    }

    // A source's actions, over the episode sheet if that is open.
    actions?.let { (hit, ep) ->
        SourceActions(
            hit = hit,
            heading = ep?.let { "${title.title} · ${episodeTag(it.season, it.episode)}" } ?: title.title,
            all = hits.orEmpty(),
            onPlay = { h ->
                sheet = false
                watch.stream(h, title, ep?.let { episodeTag(it.season, it.episode) }, ep)
            },
            onReload = { reloadSources(ep) },
            busy = busy,
            onDownload = { h -> Downloads.add(downloadJob(h, title, ep)) },
            say = { watch.notes.tryEmit(it) },
            onDismiss = { actions = null },
        )
    }

    // "Download season": the packs to choose from, best first; a tap keeps that one.
    seasonPacks?.let { (s, packs) ->
        BottomSheet("Season $s packs", onDismiss = { seasonPacks = null }) {
            when {
                packs == null -> Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 24.dp)
                    Text("   Asking your addons and plugins…", style = Type.body)
                }
                packs.isEmpty() -> Note(
                    if (lang.isEmpty()) "No season $s pack found yet — download the episodes one by one."
                    else "No season $s pack in ${langName(lang)} — change the language and try again, or download the episodes one by one.",
Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                else -> LazyColumn {
                    items(packs.distinctBy { it.id }.take(MAX_SOURCES), key = { "pack-" + it.id }) { hit ->
                        // Under the pack's own name, not the one episode file the addon pointed into it.
                        Source(hit.copy(name = hit.release), Modifier.padding(start = EDGE, end = EDGE, bottom = 8.dp), onDownload = { keepSeason(hit, s) }) { keepSeason(hit, s) }
                    }
                }
            }
        }
    }

    if (pickingLang) {
        Sheet(
            title = "Release language",
            options = listOf(Option("", "Any language")) + LANGS.map { Option(it.code, it.name) },
            selected = lang,
            onPick = {
                pickingLang = false
                lang = it
                // A film looks again by itself; a series, for the episode on show.
                if (title.isSeries) episode?.let(::findSources)
            },
            onDismiss = { pickingLang = false },
        )
    }

    if (reviews) {
        val links = reviewLinks(title)
        Sheet(
            title = "Reviews of ${title.title}",
            // Each site's own logo, by its domain, from Google's favicon service (PNG, one size for all).
            options = links.map { (label, url) -> Option(url, label, image = "https://www.google.com/s2/favicons?sz=64&domain=${android.net.Uri.parse(url).host}") },
            selected = null,
            onPick = {
                reviews = false
                openUrl(context, it)
            },
            onDismiss = { reviews = false },
        )
    }
}

/** The releases: a heading, the quality pills, then one row each. */
private fun LazyListScope.sources(
    title: Title,
    episode: Episode?,
    hits: List<Hit>?,
    shown: List<Hit>,
    busy: Boolean,
    quality: String?,
    onQuality: (String?) -> Unit,
    modifier: Modifier,
    heading: Boolean = true,
    /** The language picked, by name: an empty list then says to change it. */
    language: String? = null,
    onDownload: (Hit) -> Unit,
    /** The row's ⋯ / long-press: [SourceActions]. */
    onMore: ((Hit) -> Unit)? = null,
    /** Asks every addon and plugin again: the pill left of All. */
    onReload: (() -> Unit)? = null,
    /** Torrents left out for too few seeds, and how to show them. */
    hiddenFew: Int = 0,
    onShowFew: (() -> Unit)? = null,
    onPlay: (Hit) -> Unit,
) {
    val qualities = hits.orEmpty().mapNotNull { it.quality }.distinct().sortedBy { QUALITY_ORDER.indexOf(it).let { i -> if (i < 0) 9 else i } }
    item(key = "sources-head") {
        Column(modifier.padding(start = EDGE, end = EDGE, top = if (heading) 26.dp else 4.dp)) {
            // The heading, and at its far right a green reload: these links' tokens expire within minutes.
            if (heading) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sources", Modifier.weight(1f), style = Type.title.copy(fontWeight = FontWeight.Bold))
                if (onReload != null && hits != null) Tap(onReload, shape = RoundedCornerShape(6.dp)) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReloadArrow(busy, Ink.download, 18.dp)
                        Text("  Reload Fresh Links!", style = Type.label.copy(color = Ink.download, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            if (qualities.size > 1 || (!heading && onReload != null && hits != null)) {
                // Spread across the row like the season pills; with many, 8 dp gaps and a sideways scroll.
                SpreadRow(Modifier.padding(bottom = 12.dp)) {
                    // Reload first: never lit like a chosen pill; spinning while links are still coming in.
                    // Under a heading, the reload is the heading's; in the episode sheet (no heading), this pill.
                    if (onReload != null && !heading) ReloadPill(busy, onReload)
                    Chip("All", quality == null, onClick = { onQuality(null) })
                    qualities.forEach { q -> Chip(q, quality == q, onClick = { onQuality(q) }) }
                }
            }
            // The progress line: centred on an upright phone, from the start elsewhere.
            val centre = if (isPortraitPhone()) Alignment.Center else Alignment.CenterStart
            when {
                busy && shown.isEmpty() -> Box(Modifier.fillMaxWidth(), contentAlignment = centre) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner(size = 24.dp)
                        Text("   Asking your addons and plugins…", style = Type.body)
                    }
                }
                busy -> Box(Modifier.fillMaxWidth(), contentAlignment = centre) { Note("${shown.size} so far · finding more…", Modifier.padding(bottom = 8.dp)) }
                hits?.isEmpty() == true -> Note(
                    when {
                        !Addons.hasStreams && com.torrentviewer.mobile.cs.CsPlugins.providers().isEmpty() -> Addons.NONE_YET
                        language != null -> "Nothing found in $language. Change the language and try again."
                        else -> "Nothing found. Try another episode, or add another addon in the Addons tab."
                    },
                )
                hits == null && episode == null && title.isSeries -> Note("Choose an episode to see what is available.")
            }
            // What was left out, and a tap to bring it back.
            if (hiddenFew > 0 && onShowFew != null) Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = if (isPortraitPhone()) Alignment.Center else Alignment.CenterStart) {
                Text(
                    "$hiddenFew torrent${if (hiddenFew == 1) "" else "s"} under $MIN_SEEDS seeds hidden · Show",
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onShowFew).padding(horizontal = 6.dp, vertical = 4.dp),
                    style = Type.meta.copy(color = Ink.muted, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
    items(shown.take(MAX_SOURCES), key = { "src-" + it.id }) { hit ->
        Source(hit, modifier.padding(start = EDGE, end = EDGE, bottom = 8.dp), runtimeSeconds(title.runtime), onDownload = { onDownload(hit) }, onMore = onMore?.let { more -> { more(hit) } }) { onPlay(hit) }
    }
}

@Composable
private fun People(label: String, value: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text("$label: ", style = Type.meta.copy(color = Ink.faint))
        Text(value, style = Type.meta.copy(color = Ink.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Director: A, B", each name opening their page once TMDB has told us who they are. Long names wrap to a new line. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Crew(label: String, names: List<String>, known: List<com.torrentviewer.core.CastMember>, onPerson: ((com.torrentviewer.core.CastMember) -> Unit)?) {
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 2.dp)) {
        Text("$label: ", Modifier.padding(vertical = 6.dp), style = Type.meta.copy(color = Ink.faint))
        names.forEachIndexed { i, name ->
            if (i > 0) Text(", ", Modifier.padding(vertical = 6.dp), style = Type.meta.copy(color = Ink.faint))
            val person = known.firstOrNull { com.torrentviewer.core.sameName(it.name, name) }
            if (person != null && onPerson != null)
                Text(name, Modifier.clickable { onPerson(person) }.padding(vertical = 6.dp), style = Type.meta.copy(color = Ink.text, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
            else Text(name, Modifier.padding(vertical = 6.dp), style = Type.meta.copy(color = Ink.muted))
        }
    }
}

/** The cast as faces: a round photo over each name, scrolled sideways. Photos arrive one by one; initials until they do. */
@Composable
private fun CastRow(names: List<String>, onPerson: ((com.torrentviewer.core.CastMember) -> Unit)?) {
    Column(Modifier.padding(top = 18.dp)) {
        Text("Cast", Modifier.padding(bottom = 10.dp), style = Type.title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        // Spread across the row when few; 14 dp apart and scrolling when many.
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = spreadArrangement(14.dp)) {
            items(names.take(12)) { name ->
                // TMDB's person by that name (their photo, and their page on a tap), else Wikipedia's photo alone.
                val person by produceState<com.torrentviewer.core.CastMember?>(null, name) { value = com.torrentviewer.core.Tmdb.personNamed(name) }
                val wiki by produceState<String?>(null, name, person) { if (person?.photo == null) value = castPhoto(name) }
                val photo = person?.photo ?: wiki
                val open = person?.takeIf { onPerson != null }
                Column(
                    Modifier.width(72.dp).then(if (open != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPerson?.invoke(open) } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Ink.surface2), contentAlignment = Alignment.Center) {
                        Text(name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), style = Type.label.copy(fontWeight = FontWeight.Bold, color = Ink.muted))
                        photo?.let { Artwork(it, name, Modifier.fillMaxSize(), fill = false) }
                    }
                    Text(name, Modifier.padding(top = 6.dp), style = Type.meta.copy(fontSize = 12.sp, color = Ink.text, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Awards as a card: a trophy, the headline prize, and the tally as two pills. */
@Composable
private fun AwardsCard(text: String) {
    val a = remember(text) { awardsOf(text) }
    // An upright phone: the trophy and the words spaced around the card's middle; elsewhere side by side from the start.
    val spread = isPortraitPhone()
    Row(
        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.surface).grain().padding(14.dp),
        horizontalArrangement = if (spread) Arrangement.SpaceAround else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Ink.imdb.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_trophy, tint = Ink.imdb, size = 22.dp)
        }
        Column(Modifier.weight(1f, fill = !spread).padding(start = 12.dp)) {
            Text(a.headline ?: if (a.wins == null && a.nominations == null) text else "Awards", style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.wins != null || a.nominations != null) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.wins?.let { Pill("$it ${if (it == 1) "win" else "wins"}", Ink.imdb) }
                    a.nominations?.let { Pill("$it ${if (it == 1) "nomination" else "nominations"}", Ink.muted) }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, tint: Color) {
    Text(text, Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp), style = Type.meta.copy(fontSize = 12.sp, color = tint, fontWeight = FontWeight.SemiBold))
}

/** Netflix's phone episode row: still on the left, number and name beside it, the synopsis under both. */
@Composable
private fun EpisodeRow(ep: Episode, number: Int = ep.episode, fallback: String?, busy: Boolean, selected: Boolean, seen: Double, modifier: Modifier, onPlay: () -> Unit, onSources: () -> Unit, onDownload: () -> Unit) {
    Column(modifier.padding(horizontal = EDGE, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tap(onPlay, Modifier.width(132.dp).aspectRatio(16f / 9f), RoundedCornerShape(6.dp)) {
                Box {
                    Artwork(ep.thumbnail ?: fallback, ep.name, Modifier.fillMaxSize(), fallback = fallback)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (busy) 0.5f else 0.2f)), contentAlignment = Alignment.Center) {
                        if (busy) Spinner(size = 26.dp)
                        else Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(8.dp)) { Lc(L.drawable.lucide_ic_play, tint = Color.White, size = 16.dp) }
                    }
                    if (selected && !busy) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Ink.accent))
                    // How far it got, as on Continue watching; watched through says so beside the name instead.
                    else if (seen > 0.02 && seen < com.torrentviewer.core.WATCHED_AT) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(seen.toFloat()).fillMaxHeight().background(Ink.accent))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("$number. ${ep.name}", style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Watched as Stremio is told: an anime's ending song isn't worth waiting through for the tick.
                    if (seen >= com.torrentviewer.core.WATCHED_AT) {
                        Lc(L.drawable.lucide_ic_check, tint = Ink.good, size = 13.dp)
                        Text(" Watched" + if (ep.released != null) "  ·  " else "", style = Type.meta.copy(color = Ink.good, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    }
                    ep.released?.take(10)?.let { Text(it, style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp)) }
                }
            }
            // Every release for this episode, to pick one by hand.
            Tap(onSources, shape = CircleShape) { Lc(L.drawable.lucide_ic_list_video, Modifier.padding(10.dp), tint = Ink.muted, size = 22.dp) }
            Tap(onDownload, shape = CircleShape) { Lc(L.drawable.lucide_ic_download, Modifier.padding(10.dp), tint = Ink.download, size = 22.dp) }
        }
        ep.overview?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Past this a release outruns most home connections. */
private const val HEAVY_MBPS = 40.0
private const val TOO_HEAVY_MBPS = 80.0

/**
 * One release: what it is, how big, and how well it will stream — in words, not a seed count.
 * With [runtimeSeconds], a release too big to stream as it plays says what it needs.
 */
@Composable
fun Source(hit: Hit, modifier: Modifier = Modifier, runtimeSeconds: Double = 0.0, onDownload: (() -> Unit)? = null, onMore: (() -> Unit)? = null, onClick: () -> Unit) {
    val grade = health(hit.seeders)
    // Cached on TorBox: it plays from TorBox's servers, so the swarm's health says nothing.
    val torbox by TorBox.key.collectAsState()
    val cached by TorBox.cached.collectAsState()
    LaunchedEffect(hit.id, torbox) { if (!hit.direct) TorBox.check(hit.id) }
    val instant = !hit.direct && torbox.isNotEmpty() && hit.id.lowercase() in cached
    // A pack's size is many episodes; only a single file's size over the runtime is a bitrate.
    val mbps = if (runtimeSeconds > 0 && hit.size > 0 && (hit.scope == Scope.EPISODE || hit.perFileSize)) hit.size * 8 / runtimeSeconds / 1_000_000 else 0.0
    val tint = when (grade) {
        Health.FAST, Health.GOOD -> Ink.good
        Health.WEAK -> Ink.warn
        Health.DEAD -> Ink.danger
    }
    Tap(onClick, modifier.fillMaxWidth(), RoundedCornerShape(8.dp), onLongClick = onMore) { pressed ->
        Row(
            Modifier.background(if (pressed) Ink.surface2 else Ink.surface.copy(alpha = 0.92f)).padding(start = 14.dp, end = 0.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No resolution tag: the pills above already sort the rows by it.
            Column(Modifier.weight(1f)) {
                // The whole release name, on one line that scrolls through it — the codec and group at the end are what tell releases apart.
                // A short gap (28 dp) before it comes round again, not a third of the row.
                Text(
                    hit.name,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000, spacing = androidx.compose.foundation.MarqueeSpacing(28.dp), velocity = 40.dp),
                    style = Type.label.copy(fontSize = 14.sp),
                    maxLines = 1,
                )
                Text(
                    // A film's scope is "Episode" only by default — it says nothing there. Seeds are on the right, highlighted.
                    listOfNotNull(
                        hit.scope.label.takeIf { hit.kind == "tv" || hit.scope != Scope.EPISODE },
                        bytes(hit.size).takeIf { hit.size > 0 },
                        hit.addon.ifEmpty { null },
                    ).joinToString("  ·  "),
                    Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
                )
            }
            Spacer(Modifier.width(10.dp))
            // A direct link says nothing here: only what tells sources apart (a torrent's health, TorBox, another app).
            if (!hit.direct || hit.external || mbps >= HEAVY_MBPS) Column(horizontalAlignment = Alignment.End) {
                if (!hit.direct || hit.external) Text(
                    // A torrent: its seed count itself, coloured by how healthy that is — no "Weak" or "No seeds".
                    when { hit.external -> "Opens app"; instant -> "Instant"; else -> "${hit.seeders} seed${if (hit.seeders == 1) "" else "s"}" },
                    style = Type.meta.copy(color = if (hit.external) Ink.muted else if (instant) Ink.good else tint, fontWeight = FontWeight.Bold),
                )
                if (instant) Text("TorBox", style = Type.meta.copy(fontSize = 11.sp, color = Ink.faint))
                if (mbps >= HEAVY_MBPS) Text("Needs ${mbps.toInt()} Mb/s", style = Type.meta.copy(fontSize = 11.sp, color = if (mbps >= TOO_HEAVY_MBPS) Ink.danger else Ink.warn))
            }
            // Torrents, files and HLS playlists download; a DASH manifest or another app's link doesn't.
            if (onDownload != null && canSave(hit)) Tap(onDownload, Modifier.padding(start = 4.dp), CircleShape) { Lc(L.drawable.lucide_ic_download, Modifier.padding(8.dp), tint = Ink.download, size = 20.dp) }
            // Everything else that can be done with it: play elsewhere, mirrors, copy, download (also a long-press).
            if (onMore != null) Tap(onMore, Modifier.padding(start = 2.dp), CircleShape) { Lc(L.drawable.lucide_ic_ellipsis_vertical, Modifier.padding(8.dp), tint = Ink.muted, size = 20.dp) }
        }
    }
}


/** One-tap Download waits this long at most for every source to answer, then takes the best of what has. */
private const val QUICK_PICK_MS = 25_000L

/** The sources' reload pill: the plain (never chosen) pill look and one arrow, turning while links are still arriving. */
@Composable
private fun ReloadPill(busy: Boolean, onClick: () -> Unit) {
    Tap(onClick, shape = CircleShape) { pressed ->
        Row(
            Modifier.background(Ink.text.copy(alpha = if (pressed) 0.18f else 0.08f)).height(36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Just the arrow, no word: it turns while links are still coming in.
            ReloadArrow(busy, Ink.muted, 18.dp)
        }
    }
}

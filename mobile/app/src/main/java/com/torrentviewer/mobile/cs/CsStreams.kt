@file:OptIn(com.lagradost.cloudstream3.InternalAPI::class, com.lagradost.cloudstream3.Prerelease::class)

package com.torrentviewer.mobile.cs

import android.util.Log
import androidx.media3.common.MimeTypes
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Hit
import com.torrentviewer.core.LANGS
import com.torrentviewer.core.OnHits
import com.torrentviewer.core.Scope
import com.torrentviewer.core.Sub
import com.torrentviewer.core.Title
import com.torrentviewer.core.absoluteNumber
import com.torrentviewer.core.magnetHash
import com.torrentviewer.core.quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A title's streams from the installed CloudStream plugins, for the sources list beside the Stremio
 * addons'. Every provider that serves films (or series) runs at once: find the title on its site — by
 * IMDb id where the plugin can, else by searching the name and matching name and year — load it, pick
 * the episode, and turn each link it finds into a [Hit]. Rows arrive as each provider answers; one
 * provider failing or being slow is the normal case.
 */
object CsStreams {
    private const val TAG = "CsStreams"

    /**
     * Search, load and loadLinks together, per provider, unless the plugin says it needs longer. Aggregators
     * like StreamPlay ask dozens of sites in turn and keep finding links for a minute or two (CloudStream waits
     * for all of them); links show as they come, so a long wait costs nothing.
     */
    private const val PROVIDER_MS = 150_000L

    /** The sources list hears of new links at most this often. */
    private const val TELL_MS = 350L

    /**
     * Providers searching at once, across every search under way (a season scan runs several): each holds pages,
     * parsers and connections in memory, and dozens together are what ran the phone out of it.
     */
    private val providerSlots = kotlinx.coroutines.sync.Semaphore(10)

    /** How each provider's last search went, by its name, for the plugin list: "38 links in 64s", "didn't find this title"… */
    private val _outcomes = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val outcomes: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _outcomes

    private fun report(provider: String, outcome: String) {
        synchronized(_outcomes) { _outcomes.value = _outcomes.value + (provider to outcome) }
    }

    private val SERIES_TYPES = setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.OVA, TvType.Cartoon, TvType.Documentary)
    private val FILM_TYPES = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Torrent, TvType.Cartoon, TvType.Documentary)

    /** Subtitles that came with each link, by the link: the player offers them first. Shared lists, filled as they arrive. */
    private val subs = ConcurrentHashMap<String, MutableList<Sub>>()

    fun subtitlesFor(uri: String?): List<Sub> = uri?.let { subs[it] }?.let { synchronized(it) { it.toList() } }.orEmpty()

    /** Any plugin able to find streams for this kind of title. */
    fun any(series: Boolean) = CsPlugins.providers().any { serves(it, series) }

    private fun serves(p: MainAPI, series: Boolean) = p.supportedTypes.any { it in (if (series) SERIES_TYPES else FILM_TYPES) }

    /** A search result's kind, when its type says; Cartoon and Documentary can be either. */
    private fun seriesOf(t: TvType?): Boolean? = when (t) {
        TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.OVA -> true
        TvType.Movie, TvType.AnimeMovie -> false
        else -> null
    }

    private fun yearOf(r: SearchResponse): Int? = when (r) {
        is MovieSearchResponse -> r.year
        is TvSeriesSearchResponse -> r.year
        is AnimeSearchResponse -> r.year
        else -> null
    }

    /**
     * The addons' streams ([addons], as Detail asks them) and the plugins' together: plugin links first —
     * they play at once, with no swarm to wait on — each list in its own order. [onHits] hears every update.
     */
    suspend fun withPlugins(title: Title, ep: Episode?, onHits: OnHits, addons: suspend (OnHits) -> List<Hit>): List<Hit> = coroutineScope {
        val lock = Any()
        var fromAddons = emptyList<Hit>()
        var fromPlugins = emptyList<Hit>()
        fun now() = synchronized(lock) { fromPlugins + fromAddons }
        val plugins = async { find(title, ep) { list -> synchronized(lock) { fromPlugins = list }; onHits(now()) } }
        val got = addons { list -> synchronized(lock) { fromAddons = list }; onHits(now()) }
        synchronized(lock) { fromAddons = got }
        val mine = plugins.await()
        synchronized(lock) { fromPlugins = mine }
        now()
    }

    /** Every installed provider's links for [title] ([ep] for an episode), best quality first. */
    suspend fun find(title: Title, ep: Episode?, onHits: OnHits = {}): List<Hit> {
        val series = ep != null
        val providers = CsPlugins.providers().filter { serves(it, series) }
        if (providers.isEmpty()) return emptyList()
        val absolute = ep?.let { absoluteNumber(title.episodes, it.season, it.episode) }
        val found = mutableListOf<Hit>()
        fun now() = synchronized(found) { found.sortedByDescending { rankOf(it.quality) } }
        // The list is redrawn at most a few times a second, not once per link: StreamPlay alone sends hundreds.
        val lastTold = java.util.concurrent.atomic.AtomicLong(0)
        fun tell() {
            val at = System.currentTimeMillis()
            val last = lastTold.get()
            if (at - last >= TELL_MS && lastTold.compareAndSet(last, at)) onHits(now())
        }
        coroutineScope {
            for (p in providers) launch(Dispatchers.IO) { providerSlots.withPermit {
                val start = System.currentTimeMillis()
                val limit = maxOf(PROVIDER_MS, (p.loadLinksTimeoutMs ?: 0L) + 15_000L)
                var links = 0
                val done = withTimeoutOrNull(limit) {
                    runCatching {
                        one(p, title, ep, absolute) { hit ->
                            val added = synchronized(found) { found.none { it.uri == hit.uri } && found.add(hit) }
                            if (added) {
                                links++
                                tell()
                            }
                        }
                    }.onFailure { Log.w(TAG, "${p.name}: ${it::class.java.simpleName} ${it.message}") }
                }
                val secs = (System.currentTimeMillis() - start) / 1000
                val what = ep?.let { com.torrentviewer.core.episodeTag(it.season, it.episode) } ?: title.title
                report(
                    p.name,
                    when {
                        done == null -> "$what: $links links, still looking after ${secs}s (stopped)"
                        done.isFailure -> "$what: $links links, then failed — ${done.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }}"
                        links == 0 -> "$what: ${done.getOrNull() ?: "no links"} (${secs}s)"
                        else -> "$what: $links links in ${secs}s"
                    },
                )
                Log.i("releases", "${p.name} (plugin) ${secs}s, $links links${if (done == null) " timed out" else ""}")
                // Each provider's last links, whatever the throttle skipped.
                onHits(now())
            } }
        }
        return now().also(onHits)
    }

    /** One provider: its page for the title, the episode on it, and every link found there. Null, or why nothing came. */
    private suspend fun one(p: MainAPI, title: Title, ep: Episode?, absolute: Int?, emit: (Hit) -> Unit): String? {
        val series = ep != null
        val url = when (val at = loadUrl(p, title, series)) {
            is Found.Url -> at.url
            is Found.Not -> return at.why
        }
        val load = p.load(url) ?: return "its page didn't load"
        // A plugin that knows the title's IMDb id and gives another found a different title.
        val said = load.syncData["imdb"]?.takeIf { it.startsWith("tt") }
        if (said != null && title.imdb.startsWith("tt") && said != title.imdb) return "found a different title"
        val targets: List<Pair<String, String?>> = when (load) {
            is MovieLoadResponse -> if (series) emptyList() else listOf(load.dataUrl to null)
            is LiveStreamLoadResponse -> listOf(load.dataUrl to null)
            is TvSeriesLoadResponse -> if (ep == null) emptyList() else
                pickEpisode(load.episodes.map { it.season to it.episode }, ep.season, ep.episode, absolute)?.let { listOf(load.episodes[it].data to null) }.orEmpty()
            is AnimeLoadResponse -> load.episodes.flatMap { (dub, eps) ->
                val at = if (ep == null) eps.indices.firstOrNull() else pickEpisode(eps.map { it.season to it.episode }, ep.season, ep.episode, absolute)
                at?.let { listOf(eps[it].data to dubLabel(dub)) }.orEmpty()
            }
            is TorrentLoadResponse -> {
                (load.magnet ?: load.torrent)?.let { emit(hitOf(p, it, load.name, null, ExtractorLinkType.MAGNET, emptyMap(), title, series)) }
                return null
            }
            else -> emptyList()
        }
        if (targets.isEmpty()) return if (series) "this episode isn't on its page" else "its page has no film to play"
        for ((data, label) in targets) {
            val found = mutableListOf<Sub>()
            p.loadLinks(
                data, false,
                { s: SubtitleFile -> synchronized(found) { found += subOf(s, title, ep) } },
                { link: ExtractorLink ->
                    keepSubs(link.url, found)
                    emit(hitOf(p, link.url, listOfNotNull(link.name.ifBlank { link.source }, label).joinToString(" · "), link.quality, link.type, headersOf(link), title, series))
                },
            )
        }
        return null
    }

    /** Where a provider's page for a title is, or why it isn't. */
    private sealed interface Found {
        data class Url(val url: String) : Found
        data class Not(val why: String) : Found
    }

    /**
     * The provider's own page for the title: straight from the IMDb id where it can, else its search's best match.
     * When nothing matches, the reason names what the search did return, so the "Last search" line shows a mismatch.
     */
    private suspend fun loadUrl(p: MainAPI, title: Title, series: Boolean): Found {
        if (title.imdb.startsWith("tt") && SyncIdName.Imdb in p.supportedSyncNames) {
            runCatching { p.getLoadUrl(SyncIdName.Imdb, title.imdb) }.getOrNull()?.let { return Found.Url(it) }
        }
        val results = p.search(title.title, 1)?.items.orEmpty()
        if (results.isEmpty()) return Found.Not("its search for \"${title.title}\" came back empty")
        val year = title.year?.take(4)?.toIntOrNull()
        val candidates = results.map { Candidate(it.name, yearOf(it), seriesOf(it.type)) }
        val at = bestMatch(title.title, year, series, candidates)
            ?: return Found.Not("none of its ${results.size} results matched — first: " +
                candidates.take(3).joinToString(", ") { c -> "${c.name.take(40)}${c.year?.let { " ($it)" } ?: ""}${c.series?.let { if (it) " series" else " film" } ?: ""}" })
        return Found.Url(results[at].url)
    }

    private fun dubLabel(d: DubStatus) = when (d) {
        DubStatus.Dubbed -> "Dub"
        DubStatus.Subbed -> "Sub"
        else -> null
    }

    /** What the link's server wants: its headers, and its Referer when not among them. */
    private fun headersOf(link: ExtractorLink): Map<String, String> {
        val referer = link.referer.takeIf { it.isNotBlank() && link.headers.keys.none { k -> k.equals("referer", true) } }
        return link.headers + (referer?.let { mapOf("Referer" to it) } ?: emptyMap())
    }

    private fun hitOf(p: MainAPI, url: String, name: String, q: Int?, type: ExtractorLinkType, headers: Map<String, String>, title: Title, series: Boolean): Hit {
        val torrent = url.startsWith("magnet:", true) || type == ExtractorLinkType.MAGNET || type == ExtractorLinkType.TORRENT
        return Hit(
            // A torrent by its hash, so the same one from an addon is one row; a link by itself.
            id = (if (torrent) magnetHash(url) else null) ?: url,
            name = com.torrentviewer.core.oneLineName(name).ifBlank { p.name },
            uri = url,
            seeders = 0, leechers = 0, size = com.torrentviewer.core.sizeInName(name),
            quality = q?.let(::qualityLabel) ?: quality(name),
            kind = if (series) "tv" else "movie",
            scope = Scope.EPISODE,
            imdb = title.imdb.takeIf { it.startsWith("tt") },
            addon = p.name,
            headers = if (torrent) emptyMap() else headers,
            mime = when (type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> null
            },
        )
    }

    private fun subOf(s: SubtitleFile, title: Title, ep: Episode?) = Sub(
        release = title.title,
        name = s.lang,
        language = subtitleLang(s.lang, LANGS.map { it.code to it.match }),
        url = s.url,
        season = ep?.season,
        episode = ep?.episode,
    )

    /** Every link of one page shares its subtitle list, which can still be filling. Kept for the last few hundred links. */
    private fun keepSubs(uri: String, list: MutableList<Sub>) {
        if (subs.size > 500) subs.clear()
        subs[uri] = list
    }

    /** "4K" before "1080p" before "720p"…; unknown last. */
    private fun rankOf(q: String?): Int = when {
        q == null -> 0
        q == "4K" -> 2160
        else -> q.removeSuffix("p").toIntOrNull() ?: 0
    }
}

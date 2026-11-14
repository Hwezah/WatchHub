package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Meta
import com.torrentviewer.core.forgetStreams
import com.torrentviewer.core.searchStreams
import com.torrentviewer.mobile.cs.CsStreams
import kotlinx.coroutines.launch

/**
 * The same source again, from a fresh search: plugin links (and many debrid ones) carry a token that
 * expires within hours, so a download that failed on its link asks the sources again and carries on.
 * Never the link that just failed. In order: the same source and link name, then the same source at the
 * same resolution, then the best that can be saved at that resolution ([batchPick]). Never one in [tried]
 * either — the links that already failed for this download — so each retry moves on to the next source.
 */
fun refreshPick(hits: List<Hit>, oldUri: String, source: String?, name: String?, quality: String?, tried: Set<String> = emptySet()): Hit? {
    val ok = hits.filter { it.uri != oldUri && it.uri !in tried && batchable(it) }
    if (source != null) {
        ok.firstOrNull { it.addon == source && it.name == name }?.let { return it }
        ok.filter { it.addon == source && it.quality == quality }.let { batchPick(it, BatchFilter()) }?.let { return it }
    }
    return batchPick(ok, BatchFilter(quality = quality))
}

object LinkRefresh {
    /** The longest a fresh link is looked for: plugins like StreamPlay keep answering for minutes, and a download can't wait that long. */
    private const val FRESH_MS = 60_000L

    /**
     * A fresh source for [job]'s film or episode, or null when it can't be placed or nothing is found. Returned
     * the moment a fitting one turns up among the answers so far — the same source again, else another at the
     * same resolution — not after every source has finished. If the search ends (or [FRESH_MS] passes) with
     * nothing at that resolution, what Settings → Preferred download would pick from the rest.
     */
    suspend fun fresh(job: DownloadJob, tried: Set<String> = emptySet(), allowSame: Boolean = false): Hit? {
        val imdb = job.imdb ?: return null
        val title = Meta.title(imdb, job.kind ?: "movie") ?: return null
        // The addons' answers are kept for the session; this needs new ones.
        forgetStreams()
        val season = job.epSeason
        val number = job.epNumber
        val ep = if (season != null && number != null) title.episodes.find { it.season == season && it.episode == number } ?: return null else null
        val st = com.torrentviewer.core.SettingsStore.value.value
        val prefs = downloadPrefs()
        // [allowSame]: renewing a link that hasn't failed, so the same address (when it doesn't expire) is fine.
        val old = if (allowSame) "" else job.uri
        val quality = job.quality ?: prefs.quality.ifEmpty { null }
        fun pick(hits: List<Hit>) = refreshPick(allowedHits(hits), old, job.source, job.linkName, quality, tried)
        var latest = emptyList<Hit>()
        val early = kotlinx.coroutines.CompletableDeferred<Hit?>()
        val found = kotlinx.coroutines.withTimeoutOrNull(FRESH_MS) {
            kotlinx.coroutines.coroutineScope {
                val search = launch {
                    val all = runCatching {
                        CsStreams.withPlugins(title, ep, { partial -> latest = partial; pick(partial)?.let { early.complete(it) } }) { onHits ->
                            if (ep != null) com.torrentviewer.core.searchEpisode(title, ep.season, ep.episode, onHits)
                            else searchStreams(title.title, title.imdb, title.year?.take(4), type = title.type, onHits = onHits)
                        }
                    }.getOrDefault(latest)
                    latest = all
                    early.complete(pick(all))
                }
                early.await().also { search.cancel() }
            }
        }
        // Nothing at that resolution: the nearest the preferences allow, from whatever was found.
        return found ?: preferredPick(allowedHits(latest).filter { it.uri != old && it.uri !in tried }, prefs)
    }
}

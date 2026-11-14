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
 * Never the link that just failed, nor one in [tried] (every link that already failed this download).
 * In order: the same source and link name (the same file, renewed); else, at the same resolution, the
 * smallest that will work ([preferredPick] without codec: MKV first, then the lightest, a link or a quick swarm
 * among the near-lightest), from the same source first — but among releases no more than [GROWTH] times [oldSize], when any are,
 * so a 450 MB episode isn't swapped for a 1.7 GB one.
 */
fun refreshPick(
    hits: List<Hit>, oldUri: String, source: String?, name: String?, quality: String?, tried: Set<String> = emptySet(),
    oldSize: Long = 0, prefs: DownloadPrefs = DownloadPrefs(),
): Hit? {
    val ok = prefs.usable(hits).filter { it.uri != oldUri && it.uri !in tried && batchable(it) }
    renewal(ok, source, name)?.let { return it }
    val atQuality = ok.filter { quality == null || it.quality == quality }
    if (atQuality.isEmpty()) return null
    val near = if (oldSize <= 0) atQuality else
        atQuality.filter { it.size in 1..(oldSize * GROWTH).toLong() }.ifEmpty { atQuality.filter { it.size <= (oldSize * GROWTH).toLong() } }.ifEmpty { atQuality }
    // The smallest that will work at that resolution, MKV first: codec and the rest of the preferences don't come into a rescue.
    val pick = prefs.copy(quality = quality ?: prefs.quality, codec = "", subs = false, source = "")
    return near.filter { source != null && it.addon == source }.let { preferredPick(it, pick) } ?: preferredPick(near, pick)
}

/** The same source's link of the same name: the very file that failed, under a new address. */
fun renewal(hits: List<Hit>, source: String?, name: String?): Hit? =
    if (source == null) null else hits.firstOrNull { it.addon == source && it.name == name }

/** A fresh link may be up to this many times the size of the one it replaces, when anything that size is found. */
const val GROWTH = 1.3

object LinkRefresh {
    /** The longest a fresh link is looked for: plugins like StreamPlay keep answering for minutes, and a download can't wait that long. */
    private const val FRESH_MS = 45_000L

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
        com.torrentviewer.mobile.cs.CsPlugins.forgetPages()
        val season = job.epSeason
        val number = job.epNumber
        val ep = if (season != null && number != null) title.episodes.find { it.season == season && it.episode == number } ?: return null else null
        val st = com.torrentviewer.core.SettingsStore.value.value
        val prefs = downloadPrefs()
        // [allowSame]: renewing a link that hasn't failed, so the same address (when it doesn't expire) is fine.
        val old = if (allowSame) "" else job.uri
        val quality = job.quality ?: prefs.quality.ifEmpty { null }
        fun pick(hits: List<Hit>) = refreshPick(allowedHits(hits), old, job.source, job.linkName, quality, tried, job.size, prefs)
        // Only the same file renewed ends the search early: anything else waits for every source (or FRESH_MS), so a fast
        // source's first answer — often a far bigger torrent — doesn't win over a better one still on its way.
        fun sameFile(hits: List<Hit>) = renewal(prefs.usable(allowedHits(hits)).filter { it.uri != old && it.uri !in tried && batchable(it) }, job.source, job.linkName)
        var latest = emptyList<Hit>()
        val early = kotlinx.coroutines.CompletableDeferred<Hit?>()
        val found = kotlinx.coroutines.withTimeoutOrNull(FRESH_MS) {
            kotlinx.coroutines.coroutineScope {
                val search = launch {
                    val all = runCatching {
                        CsStreams.withPlugins(title, ep, { partial -> latest = partial; sameFile(partial)?.let { early.complete(it) } }) { onHits ->
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
        // Out of time with sources still answering: the best of what came. Nothing at that resolution: the nearest the
        // preferences allow, from whatever was found.
        return found ?: pick(latest) ?: preferredPick(allowedHits(latest).filter { it.uri != old && it.uri !in tried }, prefs)
    }
}

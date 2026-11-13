package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Meta
import com.torrentviewer.core.forgetStreams
import com.torrentviewer.core.searchStreams
import com.torrentviewer.mobile.cs.CsStreams

/**
 * The same source again, from a fresh search: plugin links (and many debrid ones) carry a token that
 * expires within hours, so a download that failed on its link asks the sources again and carries on.
 * Never the link that just failed. In order: the same source and link name, then the same source at the
 * same resolution, then the best that can be saved at that resolution ([batchPick]).
 */
fun refreshPick(hits: List<Hit>, oldUri: String, source: String?, name: String?, quality: String?): Hit? {
    val ok = hits.filter { it.uri != oldUri && batchable(it) }
    if (source != null) {
        ok.firstOrNull { it.addon == source && it.name == name }?.let { return it }
        ok.filter { it.addon == source && it.quality == quality }.let { batchPick(it, BatchFilter()) }?.let { return it }
    }
    return batchPick(ok, BatchFilter(quality = quality))
}

object LinkRefresh {
    /** A fresh source for [job]'s film or episode, or null when it can't be placed or nothing is found. */
    suspend fun fresh(job: DownloadJob): Hit? {
        val imdb = job.imdb ?: return null
        val title = Meta.title(imdb, job.kind ?: "movie") ?: return null
        // The addons' answers are kept for the session; this needs new ones.
        forgetStreams()
        val season = job.epSeason
        val number = job.epNumber
        val hits = if (season != null && number != null) {
            val ep = title.episodes.find { it.season == season && it.episode == number } ?: return null
            SeasonBatch.sources(title, ep)
        } else {
            runCatching {
                CsStreams.withPlugins(title, null, {}) { onHits ->
                    searchStreams(title.title, title.imdb, title.year?.take(4), type = title.type, onHits = onHits)
                }
            }.getOrDefault(emptyList())
        }
        return refreshPick(hits, job.uri, job.source, job.linkName, job.quality)
    }
}

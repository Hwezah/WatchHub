package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Meta
import com.torrentviewer.core.Title
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
    oldSize: Long = 0, prefs: DownloadPrefs = DownloadPrefs(), rejected: Set<String> = emptySet(),
): Hit? {
    val ok = prefs.usable(hits).filter { it.uri != oldUri && it.uri !in tried && rejectKey(it.addon, it.name) !in rejected && batchable(it) }
    renewal(ok, source, name)?.let { return it }
    val atQuality = ok.filter { quality == null || it.quality == quality }
    if (atQuality.isEmpty()) return null
    val near = if (oldSize <= 0) atQuality else
        // Not far smaller either: a 1.2 GB film swapped for a 300 MB one is usually a worse copy, not a better codec.
        atQuality.filter { it.size in (oldSize * SHRINK).toLong()..(oldSize * GROWTH).toLong() }
            .ifEmpty { atQuality.filter { it.size in 1..(oldSize * GROWTH).toLong() } }
            .ifEmpty { atQuality.filter { it.size <= (oldSize * GROWTH).toLong() } }.ifEmpty { atQuality }
    // The smallest that will work at that resolution, MKV first: codec and the rest of the preferences don't come into a rescue.
    val pick = prefs.copy(quality = quality ?: prefs.quality, codec = "", subs = false, source = "")
    return near.filter { source != null && it.addon == source }.let { preferredPick(it, pick) } ?: preferredPick(near, pick)
}

/** The same source's link of the same name: the very file that failed, under a new address. */
fun renewal(hits: List<Hit>, source: String?, name: String?): Hit? =
    if (source == null) null else hits.firstOrNull { it.addon == source && it.name == name }

/**
 * The resolution a download is kept at: the one it was asked for ([DownloadJob.wanted]); for a download from before
 * that was kept, the preferred download resolution ([preferred], Settings), else the file it has. A fallback that
 * once landed on another resolution doesn't make that the target.
 */
fun wantedQuality(job: DownloadJob, preferred: String): String? = job.wanted ?: preferred.ifEmpty { null } ?: job.quality

/** A fresh link may be up to this many times the size of the one it replaces, when anything that size is found. */
const val GROWTH = 1.3

/** …and, when anything that size is found, no less than this share of it. */
const val SHRINK = 0.5

/** How a source turned down by hand is remembered: its addon and link name, so the same file under a new address stays out too. */
fun rejectKey(addon: String?, name: String?) = "${addon.orEmpty()}|${name.orEmpty()}"

object LinkRefresh {
    /** The longest a fresh link is looked for: plugins like StreamPlay keep answering for minutes, and a download can't wait that long. */
    private const val FRESH_MS = 45_000L

    /** …and when asked by hand (↻, Different Source): the viewer is watching it, and the slow sources are often the good ones. */
    private const val WIDE_MS = 90_000L

    /**
     * A fresh source for [job]'s film or episode, or null when it can't be placed or nothing is found. Returned
     * the moment a fitting one turns up among the answers so far — the same source again, else another at the
     * same resolution — not after every source has finished. Strictly that resolution (the one the download was
     * asked for, [wantedQuality]): with nothing at it, null — the download waits and asks again later rather than
     * drifting to another resolution, which then stuck for every refresh after.
     */
    suspend fun fresh(job: DownloadJob, tried: Set<String> = emptySet(), allowSame: Boolean = false, wide: Boolean = false): Hit? {
        val imdb = job.imdb ?: return null
        // The title's details if they come; else enough to search by (its id, kind and name) — a slow or missing
        // listing never stops the search.
        val name = job.titleName?.substringBefore(" · ") ?: job.title
        val title = runCatching { Meta.title(imdb, job.kind ?: "movie") }.getOrNull() ?: Title(imdb, job.kind ?: "movie", name)
        // The addons' answers are kept for the session; this needs new ones.
        forgetStreams()
        com.torrentviewer.mobile.cs.CsPlugins.forgetPages()
        val season = job.epSeason
        val number = job.epNumber
        // The episode as the listing has it, or — not listed (yet, or at all) — made from its own numbers: the sources
        // are asked for "S01E04" all the same, rather than the search giving up before it starts.
        val ep = if (season != null && number != null) title.episodes.find { it.season == season && it.episode == number }
            ?: com.torrentviewer.core.Episode("$imdb:$season:$number", season, number, "", null, null, null) else null
        val st = com.torrentviewer.core.SettingsStore.value.value
        val prefs = downloadPrefs()
        // [allowSame]: renewing a link that hasn't failed, so the same address (when it doesn't expire) is fine.
        val old = if (allowSame) "" else job.uri
        val quality = wantedQuality(job, prefs.quality)
        // Asked at another resolution than the file it has: that file's size says nothing about the new one.
        val oldSize = if (quality == job.quality) job.size else 0L
        // Sources turned down by hand ("Different Source") never come back for this download, under any address.
        val rejected = job.rejected.toSet()
        fun allowed(hits: List<Hit>) = allowedHits(hits).filter { rejectKey(it.addon, it.name) !in rejected && it.uri !in rejected }
        fun pick(hits: List<Hit>) = refreshPick(allowed(hits), old, job.source, job.linkName, quality, tried, oldSize, prefs)
        // Only the same file renewed ends the search early: anything else waits for every source (or FRESH_MS), so a fast
        // source's first answer — often a far bigger torrent — doesn't win over a better one still on its way.
        fun sameFile(hits: List<Hit>) = renewal(prefs.usable(allowed(hits)).filter { it.uri != old && it.uri !in tried && batchable(it) }, job.source, job.linkName)
        var latest = emptyList<Hit>()
        val early = kotlinx.coroutines.CompletableDeferred<Hit?>()
        // By hand ([wide]): longer, so the slow plugins (StreamPlay, SuperStream) that often hold the small files have answered.
        val found = kotlinx.coroutines.withTimeoutOrNull(if (wide) WIDE_MS else FRESH_MS) {
            kotlinx.coroutines.coroutineScope {
                val search = launch {
                    val all = runCatching {
                        CsStreams.withPlugins(title, ep, { partial -> latest = partial; sameFile(partial)?.let { early.complete(it) } }) { onHits ->
                            if (ep != null) com.torrentviewer.core.searchEpisode(title, ep.season, ep.episode, onHits)
                            else searchStreams(title.title, title.imdb, title.year?.take(4), type = title.type, onHits = onHits)
                        }
                    }.getOrDefault(latest)
                    // Unlabelled links asked for their file names, so a fresh link is judged by what it really is.
                    latest = LinkProbe.describe(all)
                    early.complete(pick(latest))
                }
                early.await().also { search.cancel() }
            }
        }
        // Out of time with sources still answering: the best of what came, at that resolution. Nothing at it: by hand, a
        // pack holding the episode at it; else nothing — never another resolution.
        val atQuality = allowed(latest).filter { quality == null || it.quality == quality }
        return found ?: pick(latest) ?: preferredPick(atQuality.filter { it.uri != old && it.uri !in tried }, prefs)
            ?: if (wide) packPick(atQuality, old, tried, season, prefs) else null
    }

    /**
     * ↻ pressed by hand and no single episode anywhere ([wide]): a season (or complete) pack holding the episode, as
     * its torrent — the job's episode tag then takes just that file out of it. Still within the download rules
     * ([DownloadPrefs.usable]); the preferred resolution first, then the best-seeded.
     */
    private fun packPick(hits: List<Hit>, old: String, tried: Set<String>, season: Int?, prefs: DownloadPrefs): Hit? {
        val packs = allowedHits(hits).filter { h ->
            h.scope != com.torrentviewer.core.Scope.EPISODE && h.uri != old && h.uri !in tried &&
                (season == null || com.torrentviewer.core.seasonsIn(h.release).let { s -> s.isEmpty() || season in s })
        }.mapNotNull { h -> if (h.uri.startsWith("magnet:", true)) h else magnetOf(h)?.let { m -> h.copy(uri = m) } }
        return prefs.usable(packs).sortedWith(
            compareBy<Hit> { if (prefs.quality.isNotEmpty() && it.quality != prefs.quality) 1 else 0 }.thenByDescending { it.seeders },
        ).firstOrNull()
    }
}

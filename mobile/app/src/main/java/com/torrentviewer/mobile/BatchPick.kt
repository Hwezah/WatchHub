package com.torrentviewer.mobile

import androidx.media3.common.MimeTypes
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope

/*
 * Choosing what a batch season download keeps, episode by episode. Pure, so it is tested on the JVM.
 */

/** Under this, a "release" is a trailer, a sample or a fake, not an episode. */
const val MIN_EPISODE_BYTES = 25L * 1024 * 1024

private val HEVC = Regex("(?i)(?<![a-z0-9])(x\\.?265|h\\.?265|hevc)(?![a-z0-9])")

/** HEVC (x265): the same picture in roughly half the bytes of x264. */
fun isHevc(hit: Hit) = HEVC.containsMatchIn(hit.name) || HEVC.containsMatchIn(hit.release)

/** A link that is an HLS or DASH playlist rather than one file: it plays anywhere, but can't be saved as one download. */
fun isPlaylist(hit: Hit) = hit.mime == MimeTypes.APPLICATION_M3U8 || hit.mime == MimeTypes.APPLICATION_MPD ||
    Regex("\\.(m3u8|mpd)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(hit.uri)

/** Something that can be saved as a file: a torrent, or a direct link to one file. */
fun canSave(hit: Hit) = !hit.external && !(hit.direct && isPlaylist(hit))

/** A torrent nobody seeds never finishes; a direct link has no swarm to need. */
private fun alive(hit: Hit) = hit.direct || hit.seeders > 0

/** The episode's candidates at [quality]: single episodes that can be saved and aren't too small to be one. */
fun batchCandidates(hits: List<Hit>, quality: String): List<Hit> = hits.filter {
    it.quality == quality && it.scope == Scope.EPISODE && canSave(it) && (it.size == 0L || it.size >= MIN_EPISODE_BYTES)
}

/**
 * What a batch keeps for one episode at [quality]: one that can download at all first, then HEVC,
 * then the smallest known size (an unknown size after every known one), then the bigger swarm.
 */
fun batchPick(hits: List<Hit>, quality: String): Hit? = batchCandidates(hits, quality).sortedWith(
    compareByDescending<Hit> { alive(it) }
        .thenByDescending { isHevc(it) }
        .thenBy { if (it.size > 0) 0 else 1 }
        .thenBy { it.size }
        .thenByDescending { it.seeders },
).firstOrNull()

/** The resolutions a batch can offer, best first, each with how many sources it has. */
fun batchQualities(hits: List<Hit>): List<Pair<String, Int>> = hits.mapNotNull { it.quality }.distinct()
    .map { q -> q to batchCandidates(hits, q).size }
    .filter { it.second > 0 }
    .sortedByDescending { (q, _) -> if (q == "4K") 2160 else q.removeSuffix("p").toIntOrNull() ?: 0 }

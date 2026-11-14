package com.torrentviewer.mobile

import androidx.media3.common.MimeTypes
import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope

/*
 * Choosing what a batch season download keeps, episode by episode: each release's traits read from
 * its name and link, the viewer's filters over them, and the pick. Pure, so it is tested on the JVM.
 */

/** Under this, a "release" is a trailer, a sample or a fake, not an episode. */
const val MIN_EPISODE_BYTES = 25L * 1024 * 1024

private val HEVC = Regex("(?i)(?<![a-z0-9])(x\\.?265|h\\.?265|hevc)(?![a-z0-9])")
private val AVC = Regex("(?i)(?<![a-z0-9])(x\\.?264|h\\.?264|avc)(?![a-z0-9])")
private val AV1 = Regex("(?i)(?<![a-z0-9])av1(?![a-z0-9])")
private val SUBS = Regex("(?i)(?<![a-z0-9])(e-?subs?|m-?subs?|multi-?subs?|subbed|subs?|subtitles?|vostfr)(?![a-z0-9])")
private val FORMAT = Regex("(?i)\\.(mkv|mp4|avi|webm|m4v|mov|ts)(?![a-z0-9])")

/** HEVC (x265): the same picture in roughly half the bytes of x264. */
fun isHevc(hit: Hit) = HEVC.containsMatchIn(hit.name) || HEVC.containsMatchIn(hit.release)

// Compiled once: these run for every source row, on every list update.
private val M3U8 = Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE)
private val MPD = Regex("\\.mpd(\\?|$)", RegexOption.IGNORE_CASE)

/** A link that is an HLS playlist (.m3u8): saved by joining its segments ([LinkFetch]). */
fun isPlaylist(hit: Hit) = hit.mime == MimeTypes.APPLICATION_M3U8 || M3U8.containsMatchIn(hit.uri)

/** A DASH manifest (.mpd): it plays, but separate video and audio tracks can't be saved as one file yet. */
fun isDash(hit: Hit) = hit.mime == MimeTypes.APPLICATION_MPD || MPD.containsMatchIn(hit.uri)

/** Something that can be saved as a file: a torrent, a direct file, or an HLS playlist. */
fun canSave(hit: Hit) = !hit.external && !(hit.direct && isDash(hit))

/** What the batch filters go by, read from a release's name and link. [UNKNOWN] when it doesn't say. */
data class Traits(val quality: String?, val codec: String, val format: String, val subs: Boolean, val source: String)

const val UNKNOWN = "Unknown"

fun traitsOf(hit: Hit): Traits {
    val text = "${hit.name} ${hit.release}"
    val codec = when {
        HEVC.containsMatchIn(text) -> "x265"
        AV1.containsMatchIn(text) -> "AV1"
        AVC.containsMatchIn(text) -> "x264"
        else -> UNKNOWN
    }
    val format = when {
        hit.direct && isPlaylist(hit) -> "HLS"
        else -> (FORMAT.find(text) ?: if (hit.direct) FORMAT.find(hit.uri.substringBefore('?')) else null)
            ?.groupValues?.get(1)?.uppercase() ?: UNKNOWN
    }
    return Traits(hit.quality, codec, format, SUBS.containsMatchIn(text), if (hit.direct) "Link" else "Torrent")
}

/** The viewer's choices; null (or false) is "any". */
data class BatchFilter(
    val quality: String? = null,
    val codec: String? = null,
    val format: String? = null,
    val subsOnly: Boolean = false,
    val source: String? = null,
) {
    fun accepts(t: Traits) = (quality == null || t.quality == quality) && (codec == null || t.codec == codec) &&
        (format == null || t.format == format) && (!subsOnly || t.subs) && (source == null || t.source == source)
}

/** A torrent nobody seeds never finishes; a direct link has no swarm to need. */
private fun alive(hit: Hit) = hit.direct || hit.seeders > 0

/** What a batch may take at all: single episodes that can be saved and aren't too small to be one. */
fun batchable(hit: Hit) = hit.scope == Scope.EPISODE && canSave(hit) && (hit.size == 0L || hit.size >= MIN_EPISODE_BYTES)

/** The episode's candidates under [filter]. */
fun batchCandidates(hits: List<Hit>, filter: BatchFilter): List<Hit> = hits.filter { batchable(it) && filter.accepts(traitsOf(it)) }

fun batchCandidates(hits: List<Hit>, quality: String): List<Hit> = batchCandidates(hits, BatchFilter(quality = quality))

/**
 * What a batch keeps for one episode: one that can download at all first, then a single file before an
 * HLS playlist (which some download apps handle badly), then — when no codec was picked — HEVC, then
 * the smallest known size (an unknown size after every known one), then the bigger swarm.
 */
/** A direct link may be up to this many times the lightest torrent at its resolution and still be preferred. */
const val HEAVY_RATIO = 1.5

/**
 * The direct links in [hits] worth preferring to a torrent: they download at once, with no swarm to wait on, but
 * some are re-encodes several times the size. One is preferred when its size is known and at most [HEAVY_RATIO]
 * times the lightest live torrent of the same resolution, or when there's no such torrent to compare with.
 */
fun preferableLinks(hits: List<Hit>): Set<Hit> {
    val lightest = hits.filter { !it.direct && !it.external && alive(it) && it.size > 0 }
        .groupBy { it.quality }.mapValues { (_, list) -> list.minOf { it.size } }
    return hits.filter { h ->
        h.direct && run {
            val t = lightest[h.quality]
            t == null || (h.size > 0 && h.size <= t * HEAVY_RATIO)
        }
    }.toSet()
}

fun batchPick(hits: List<Hit>, filter: BatchFilter, preferDirect: Boolean = false): Hit? = batchCandidates(hits, filter).let { list ->
    val links = if (preferDirect) preferableLinks(list) else emptySet()
    list.sortedWith(
    compareByDescending<Hit> { alive(it) }
        .thenByDescending { it in links }
        .thenByDescending { !(it.direct && isPlaylist(it)) }
        .thenByDescending { filter.codec == null && isHevc(it) }
        .thenBy { if (it.size > 0) 0 else 1 }
        .thenBy { it.size }
        .thenByDescending { it.seeders },
    ).firstOrNull()
}

fun batchPick(hits: List<Hit>, quality: String): Hit? = batchPick(hits, BatchFilter(quality = quality))

private fun qualityRank(q: String) = if (q == "4K") 2160 else q.removeSuffix("p").toIntOrNull() ?: 0

/** The resolutions a batch can offer, best first, each with how many sources it has. */
fun batchQualities(hits: List<Hit>): List<Pair<String, Int>> = hits.filter(::batchable).mapNotNull { it.quality }
    .groupingBy { it }.eachCount().toList().sortedByDescending { qualityRank(it.first) }

/**
 * The choices a filter row offers, from what was found: each value with how many releases have it, with
 * the other filters applied (pass [filter] with this row's own choice cleared), so a row never offers a
 * dead end. Best resolution first, then the most common, Unknown last.
 */
fun filterOptions(all: List<Hit>, filter: BatchFilter, only: (Hit) -> Boolean = ::batchable, of: (Traits) -> String?): List<Pair<String, Int>> =
    all.filter(only).map(::traitsOf).filter(filter::accepts).mapNotNull(of)
        .groupingBy { it }.eachCount().toList()
        .sortedWith(compareBy<Pair<String, Int>> { it.first == UNKNOWN }.thenByDescending { qualityRank(it.first) }.thenByDescending { it.second })

/** For each episode, its pick under [filter], or null where nothing matches. */
fun <K> batchPlan(byEpisode: Map<K, List<Hit>>, filter: BatchFilter): Map<K, Hit?> = byEpisode.mapValues { (_, hits) -> batchPick(hits, filter) }

/** One-tap Download's preferences (Settings → Preferred download). Empty strings, and false, are "any". */
data class DownloadPrefs(
    val quality: String = "720p", val codec: String = "x265", val format: String = "MKV", val subs: Boolean = false, val source: String = "",
    /** Direct links before torrents, unless much heavier ([preferableLinks]). */
    val direct: Boolean = false,
) {
    /** As a batch filter, for the batch sheet to open with — every preference a hard choice there. */
    fun asFilter() = BatchFilter(quality.ifEmpty { null }, codec.ifEmpty { null }, format.ifEmpty { null }, subs, source.ifEmpty { null })
}

/**
 * How far [quality] is from the preferred [wanted]: 0 for it, then moving up — the nearest above, then the
 * next — and only then the nearest below; unknown last. With no preference every resolution is equal.
 */
fun resolutionDistance(quality: String?, wanted: String): Int {
    if (wanted.isEmpty()) return 0
    val w = heightOf(wanted) ?: return 0
    val h = heightOf(quality) ?: return 100_000
    return if (h >= w) h - w else 10_000 + (w - h)
}

/**
 * One-tap Download: never fails to pick while anything can be saved. Ranked, in order: the preferred
 * resolution, else the next one up; can download at all; a direct link not much heavier than the torrents (when
 * [DownloadPrefs.direct]); a single file before an HLS playlist; codec, format, subtitles and
 * source as preferred; the lightest known size (unknown after every known one); the bigger swarm.
 */
fun preferredPick(hits: List<Hit>, prefs: DownloadPrefs): Hit? {
    val candidates = hits.filter(::batchable)
    if (candidates.isEmpty()) return null
    val traits = candidates.associateWith(::traitsOf)
    fun t(h: Hit) = traits.getValue(h)
    val links = if (prefs.direct) preferableLinks(candidates) else emptySet()
    return candidates.sortedWith(
        compareBy<Hit> { resolutionDistance(it.quality, prefs.quality) }
            .thenByDescending { alive(it) }
            .thenByDescending { it in links }
            .thenByDescending { !(it.direct && isPlaylist(it)) }
            .thenByDescending { prefs.codec.isNotEmpty() && t(it).codec == prefs.codec }
            .thenByDescending { prefs.format.isNotEmpty() && t(it).format == prefs.format }
            .thenByDescending { prefs.subs && t(it).subs }
            .thenByDescending { prefs.source.isNotEmpty() && t(it).source == prefs.source }
            .thenBy { if (it.size > 0) 0 else 1 }
            .thenBy { it.size }
            .thenByDescending { it.seeders },
    ).first()
}

/** What a pick was, for the one-line message: "720p · x265 · MKV · 412 MB · HdHub". */
fun describePick(hit: Hit): String {
    val t = traitsOf(hit)
    return listOfNotNull(
        hit.quality, t.codec.takeIf { it != UNKNOWN }, t.format.takeIf { it != UNKNOWN },
        com.torrentviewer.core.bytes(hit.size).takeIf { hit.size > 0 }, hit.addon.ifEmpty { null },
    ).joinToString(" · ")
}

/** Fewer seeds than this and a torrent rarely finishes: left out of the sources lists by default. */
const val MIN_SEEDS = 5

/**
 * A torrent with too few seeds to bother with: not a direct link or another app's, and not cached on TorBox
 * ([cached]: lower-case ids), where it plays from TorBox's servers whatever the swarm.
 */
fun fewSeeds(hit: Hit, cached: Set<String> = emptySet()) = !hit.direct && !hit.external && hit.seeders < MIN_SEEDS && hit.id.lowercase() !in cached

/** What Settings → Streaming offers to leave out: a heading, then each choice's key and label. */
val LEAVE_OUT = listOf(
    "Resolution" to listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "CAM").map { "q:$it" to it } + ("q:Auto" to "Auto / variable"),
    "Codec" to listOf("c:x265" to "x265 (HEVC)", "c:x264" to "x264 (AVC)", "c:AV1" to "AV1", "c:$UNKNOWN" to "Not stated"),
    "Format" to listOf("f:MKV" to "MKV", "f:MP4" to "MP4", "f:HLS" to "HLS stream", "f:Other" to "Other / not stated"),
)

/**
 * The keys a release answers to in [LEAVE_OUT]: its resolution ("Auto" when it has none — adaptive or
 * unlabelled), codec, and format (MKV, MP4, HLS, or "Other" for anything else or unknown).
 */
fun leaveOutKeys(hit: Hit): List<String> {
    val t = traitsOf(hit)
    val format = if (t.format in setOf("MKV", "MP4", "HLS")) t.format else "Other"
    return listOf("q:${hit.quality ?: "Auto"}", "c:${t.codec}", "f:$format")
}

/** Whether [hit] may show at all: none of its keys is among those left out. */
fun allowed(hit: Hit, leaveOut: Collection<String>) = leaveOut.isEmpty() || leaveOutKeys(hit).none { it in leaveOut }

/** The sources the viewer allows: every list and every download pick goes through this. */
fun allowedHits(hits: List<Hit>): List<Hit> {
    val out = com.torrentviewer.core.SettingsStore.value.value.leaveOut
    return if (out.isEmpty()) hits else hits.filter { allowed(it, out) }
}

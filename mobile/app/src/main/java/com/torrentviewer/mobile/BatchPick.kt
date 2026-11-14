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

/**
 * Kept per release: every list, filter row and download pick asks for the same few hundred releases' traits again
 * on each redraw, and each answer is four regexes over a long name. Bounded, oldest out first.
 */
private val traitsMemo = object : LinkedHashMap<Hit, Traits>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Hit, Traits>?) = size > 4000
}

fun traitsOf(hit: Hit): Traits {
    // The whole release is the key: two with one name can differ in their link (an .mp4 or an HLS playlist).
    synchronized(traitsMemo) { traitsMemo[hit] }?.let { return it }
    return readTraits(hit).also { synchronized(traitsMemo) { traitsMemo[hit] = it } }
}

private fun readTraits(hit: Hit): Traits {
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
/** Torrents with at least this many seeds normally download quickly: picks prefer them to thinner swarms. */
const val GOOD_SEEDS = 20

/** Quick to download: a direct link, or a torrent with [GOOD_SEEDS] or more. */
fun quick(hit: Hit) = hit.direct || hit.seeders >= GOOD_SEEDS

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

fun batchPick(hits: List<Hit>, filter: BatchFilter, preferDirect: Boolean = false, prefs: DownloadPrefs = DownloadPrefs()): Hit? = batchCandidates(prefs.usable(hits), filter).let { list ->
    val links = if (preferDirect) preferableLinks(list) else emptySet()
    list.sortedWith(
    compareByDescending<Hit> { alive(it) }
        .thenByDescending { it in links }
        .thenByDescending { quick(it) }
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
    /** Torrents under [MIN_SEEDS] never picked ([fewSeeds]), as the lists hide them — except those [cached] on TorBox. */
    val skipFewSeeds: Boolean = false,
    /** TorBox's cached torrents (lower-case ids): they download from TorBox's servers, whatever the swarm. */
    val cached: Set<String> = emptySet(),
) {
    /** What a pick may take at all: [hits] without the thin torrents when those are skipped. */
    fun usable(hits: List<Hit>): List<Hit> = if (skipFewSeeds) hits.filterNot { fewSeeds(it, cached) } else hits

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
 * One-tap Download, the season sheet and every automatic rescue: never fails to pick while anything can be saved.
 * The owner's rule: the smallest 720p MKV, torrents and links alike; with no 720p MKV anywhere, up the chain
 * (1080p, 1440p, 4K MKV), and only then other formats the same way. In order:
 *  1. the preferred format (MKV) before any other;
 *  2. the preferred resolution, else the next one up, lower only last ([resolutionDistance]);
 *  3. can download at all (a live swarm, or a link);
 *  4. the lightest known size — though among those within [NEAR_SIZE] of the lightest, a direct link (when
 *     [DownloadPrefs.direct]), a well-seeded torrent, a single file before a playlist and the preferred codec go first.
 * Unknown sizes come after every known one.
 */
fun preferredPick(hits: List<Hit>, prefs: DownloadPrefs): Hit? {
    val candidates = prefs.usable(hits).filter(::batchable)
    if (candidates.isEmpty()) return null
    val traits = candidates.associateWith(::traitsOf)
    fun t(h: Hit) = traits.getValue(h)
    val wrongFormat = { h: Hit -> if (prefs.format.isNotEmpty() && t(h).format != prefs.format) 1 else 0 }
    val tier = compareBy<Hit> { wrongFormat(it) }.thenBy { resolutionDistance(it.quality, prefs.quality) }.thenByDescending { alive(it) }
    val best = candidates.minWith(tier)
    val top = candidates.filter { tier.compare(it, best) == 0 }
    // Two steps, not one comparator: "about as light" isn't transitive, and a sort needs it to be.
    val lightest = top.filter { it.size > 0 }.minOfOrNull { it.size }
    val near = if (lightest == null) top else top.filter { it.size > 0 && it.size <= (lightest * NEAR_SIZE).toLong() }
    return near.sortedWith(
        compareByDescending<Hit> { prefs.direct && it.direct }
            .thenByDescending { quick(it) }
            .thenByDescending { !(it.direct && isPlaylist(it)) }
            .thenByDescending { prefs.codec.isNotEmpty() && t(it).codec == prefs.codec }
            .thenByDescending { prefs.subs && t(it).subs }
            .thenBy { it.size }
            .thenByDescending { it.seeders },
    ).first()
}

/** Files within this of the lightest count as "the smallest" too: then a link, a quick swarm or the preferred codec decide. */
const val NEAR_SIZE = 1.1

/**
 * Whether a season scan can stop asking for this episode: what [preferredPick] would take already is the preferred
 * format at the preferred resolution, quick to download. Slow plugins are then not waited on.
 * ponytail: a smaller file may still be on its way from a slow plugin; the scan trades that for not waiting minutes.
 */
fun goodEnough(hits: List<Hit>, prefs: DownloadPrefs): Boolean {
    val pick = preferredPick(hits, prefs) ?: return false
    return resolutionDistance(pick.quality, prefs.quality) == 0 && pick.quality != null && quick(pick) &&
        (prefs.format.isEmpty() || traitsOf(pick).format == prefs.format)
}

/** What a pick was, for the one-line message: "720p · x265 · MKV · 412 MB · HdHub". */
fun describePick(hit: Hit): String {
    val t = traitsOf(hit)
    return listOfNotNull(
        hit.quality, t.codec.takeIf { it != UNKNOWN }, t.format.takeIf { it != UNKNOWN },
        com.torrentviewer.core.bytes(hit.size).takeIf { hit.size > 0 }, hit.addon.ifEmpty { null },
    ).joinToString(" · ")
}

/** Fewer seeds than this and a torrent rarely finishes: hidden from the lists (a tap shows them) and never picked, by default. */
const val MIN_SEEDS = 10

/**
 * A torrent with too few seeds to bother with: not a direct link or another app's, and not cached on TorBox
 * ([cached]: lower-case ids), where it plays from TorBox's servers whatever the swarm.
 */
fun fewSeeds(hit: Hit, cached: Set<String> = emptySet()) = !hit.direct && !hit.external && hit.seeders < MIN_SEEDS && hit.id.lowercase() !in cached

/** Fewer seeds than this and a torrent is ruled out altogether: never listed, never picked, whatever the settings. */
const val DEAD_SEEDS = 5

/** A torrent under [DEAD_SEEDS] (and not cached on TorBox): gone from every list and pick. Those from 5 to 9 are only [fewSeeds]. */
fun deadTorrent(hit: Hit, cached: Set<String> = emptySet()) = !hit.direct && !hit.external && hit.seeders < DEAD_SEEDS && hit.id.lowercase() !in cached

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

/** Whether [hit] may show at all: not a [deadTorrent], and none of its keys among those left out. */
fun allowed(hit: Hit, leaveOut: Collection<String>, cached: Set<String> = emptySet()) =
    !deadTorrent(hit, cached) && (leaveOut.isEmpty() || leaveOutKeys(hit).none { it in leaveOut })

/** TorBox's cached torrents, lower-case: they download whatever the swarm, so no seed rule rules them out. */
fun torboxCached(): Set<String> = com.torrentviewer.core.TorBox.cached.value.mapTo(HashSet()) { it.lowercase() }

/** The sources the viewer allows: every list and every download pick goes through this. */
fun allowedHits(hits: List<Hit>): List<Hit> {
    val out = com.torrentviewer.core.SettingsStore.value.value.leaveOut
    val cached = torboxCached()
    return hits.filter { allowed(it, out, cached) }
}

/** The viewer's download preferences from Settings, with TorBox's cached torrents: what every pick goes by. */
fun downloadPrefs(): DownloadPrefs = com.torrentviewer.core.SettingsStore.value.value.let {
    DownloadPrefs(
        it.dlQuality, it.dlCodec, it.dlFormat, it.dlSubs, it.dlSource, it.dlDirect,
        skipFewSeeds = it.hideFewSeeds,
        cached = torboxCached(),
    )
}

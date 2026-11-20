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

/**
 * Up to three short tags for a download's card — resolution, container, codec ("720p", "MKV", "x265") — read from its
 * link's name, release and address, so a file that looks too small for its resolution can be judged at a glance.
 * What the name doesn't say is left out rather than guessed.
 */
fun downloadTags(quality: String?, text: String, uri: String, playlist: Boolean): List<String> {
    val codec = when {
        HEVC.containsMatchIn(text) -> "x265"
        AV1.containsMatchIn(text) -> "AV1"
        AVC.containsMatchIn(text) -> "x264"
        else -> null
    }
    val format = if (playlist) "HLS" else (FORMAT.find(text) ?: FORMAT.find(uri.substringBefore('?')))?.groupValues?.get(1)?.uppercase()
    return listOfNotNull(quality, format, codec)
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
        // A link before a torrent (the owner's rule: torrents aren't the priority).
        .thenByDescending { it.direct }
        // Then MoviesMod, MoviesDrive, HDHub, in that order (the owner's trusted sites), before any size.
        .thenByDescending { trustRank(it) }
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

/**
 * [filterOptions] for a season: each value with how many episodes have at least one release with it (the others'
 * filters applied), not how many releases — "720p · 8" is eight episodes that can get a 720p, so choosing it never
 * promises more episodes than the plan then finds.
 */
fun episodeOptions(episodes: Collection<List<Hit>>, filter: BatchFilter, only: (Hit) -> Boolean = ::batchable, of: (Traits) -> String?): List<Pair<String, Int>> =
    episodes.flatMap { hits -> hits.filter(only).map(::traitsOf).filter(filter::accepts).mapNotNull(of).distinct() }
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
fun preferredPick(hits: List<Hit>, prefs: DownloadPrefs): Hit? = linksFirst(hits, prefs.quality) { preferredAmong(it, prefs) }

/**
 * The owner's rule for a film or an episode: torrents aren't the priority. [pick] runs on the direct links and on the
 * torrents apart, and the link is taken unless the torrent is a closer match to the [wanted] resolution (or there is
 * no link) — whatever their formats or sizes, as a link's name often doesn't say MKV and an addon's seed count is often
 * stale. (A whole season's pack is chosen elsewhere: a well-seeded one is still worth it.)
 */
fun linksFirst(hits: List<Hit>, wanted: String, pick: (List<Hit>) -> Hit?): Hit? {
    val (links, torrents) = hits.partition { it.direct }
    // Among links, the trusted sites first, in the owner's order ([trustRank]: MoviesMod, MoviesDrive, HDHub), when one has
    // the resolution as near as any; within a site, [pick]'s own order (the lightest).
    val any = pick(links)
    val trusted = (3 downTo 1).firstNotNullOfOrNull { r ->
        pick(links.filter { trustRank(it) == r })?.takeIf { t -> any != null && resolutionDistance(t.quality, wanted) <= resolutionDistance(any.quality, wanted) }
    }
    val link = trusted ?: any
    val torrent = pick(torrents)
    return when {
        link == null -> torrent
        torrent == null -> link
        resolutionDistance(torrent.quality, wanted) < resolutionDistance(link.quality, wanted) -> torrent
        else -> link
    }
}

/** Sites whose links are good small 720p files with good codecs, from experience (the owner's), in the owner's order. */
private val MOVIESMOD = Regex("(?i)movies?\\W?mod")
private val MOVIESDRIVE = Regex("(?i)movies?\\W?drive")
private val HDHUB = Regex("(?i)hd\\W?hub")

/**
 * How trusted a link's site is, by its name, release or the addon or plugin it came through: MoviesMod 3, MoviesDrive 2,
 * HDHub 1 (the owner's order of preference), any other site or a torrent 0.
 */
fun trustRank(hit: Hit): Int {
    if (!hit.direct) return 0
    fun says(r: Regex) = r.containsMatchIn(hit.name) || r.containsMatchIn(hit.release) || r.containsMatchIn(hit.addon)
    return when {
        says(MOVIESMOD) -> 3
        says(MOVIESDRIVE) -> 2
        says(HDHUB) -> 1
        else -> 0
    }
}

/** A link from one of the trusted sites ([trustRank]). */
fun trustedSource(hit: Hit) = trustRank(hit) > 0

/**
 * Whether a season scan should ask about an episode once more: its pick ([seasonPick]) isn't from MoviesMod or
 * MoviesDrive (the owner's first two sites), or there's none. Those come through StreamPlay, whose links hop through
 * several sites and miss the time limit when the scan is busy; an episode then fell to HDHub's 515 MB over MoviesDrive's
 * 394 MB, which a second, quieter look finds.
 */
fun wantsTrustedAgain(hits: List<Hit>, prefs: DownloadPrefs): Boolean = seasonPick(hits, prefs)?.let { trustRank(it) < 2 } ?: true

/**
 * An episode's links so far, with [newer] ones added: every link it has been seen with is kept (a later list — a plugin
 * out of time on a second look — can lack one an earlier list had), and a newer reading of the same link (its file name
 * learned) takes the older's place.
 */
fun mergedHits(older: List<Hit>, newer: List<Hit>): List<Hit> = (newer + older).distinctBy { it.id }

private fun preferredAmong(hits: List<Hit>, prefs: DownloadPrefs): Hit? {
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

/**
 * A season download's pick, by the owner's rule: the preferred format (MKV) first, then the preferred resolution (720p,
 * else the next up), then a direct link before a torrent or a playlist — and among those the fewest MB, strictly. An
 * unknown size after every known one; a dead torrent never before a live one.
 */
fun seasonPick(hits: List<Hit>, prefs: DownloadPrefs): Hit? = linksFirst(hits, prefs.quality) { seasonAmong(it, prefs) }

private fun seasonAmong(hits: List<Hit>, prefs: DownloadPrefs): Hit? {
    val candidates = prefs.usable(hits).filter(::batchable)
    if (candidates.isEmpty()) return null
    val traits = candidates.associateWith(::traitsOf)
    fun t(h: Hit) = traits.getValue(h)
    return candidates.minWithOrNull(
        compareBy<Hit> { if (prefs.format.isNotEmpty() && t(it).format != prefs.format) 1 else 0 }
            .thenBy { resolutionDistance(it.quality, prefs.quality) }
            // A file, link or torrent, before a stream (no size to go by, and joined from pieces).
            .thenByDescending { !(it.direct && isPlaylist(it)) }
            // Ready to download: a link, or a torrent with [MIN_SEEDS] or more — the owner's floor — or cached on TorBox.
            .thenByDescending { ready(it, prefs.cached) }
            .thenBy { if (it.size > 0) 0 else 1 }
            // Then the fewest MB, links and torrents alike; a link only breaks a tie.
            .thenBy { it.size }
            .thenByDescending { it.direct }
            .thenByDescending { prefs.codec.isNotEmpty() && t(it).codec == prefs.codec }
            .thenByDescending { it.seeders },
    )
}

/** Downloads without waiting on a thin swarm: a direct link, a torrent of [MIN_SEEDS] seeds or more, or one cached on TorBox. */
fun ready(hit: Hit, cached: Set<String> = emptySet()) = hit.direct || !fewSeeds(hit, cached)

/**
 * The season pack worth taking instead of the episodes one by one: season [season] alone (not a complete series),
 * at the preferred resolution, ready ([ready]), and light — no more than [perEpisode] bytes an episode for its
 * [episodes] on average. The lightest such, x265 first among near sizes; null when there is none.
 */
fun lightPack(hits: List<Hit>, season: Int, episodes: Int, prefs: DownloadPrefs, perEpisode: Long = 450L * 1024 * 1024): Hit? {
    if (episodes <= 0) return null
    return hits.filter {
        !it.direct && it.scope == Scope.SEASON && com.torrentviewer.core.parseEpisode(it.release) == null && season in com.torrentviewer.core.seasonsIn(it.release) &&
            ready(it, prefs.cached) && !deadTorrent(it, prefs.cached) &&
            (prefs.quality.isEmpty() || it.quality == prefs.quality) && it.size > 0 && it.size <= perEpisode * episodes
    }.distinctBy { it.id }.minWithOrNull(
        compareBy<Hit> { it.size / (64L * 1024 * 1024) }
            .thenByDescending { prefs.codec.isNotEmpty() && traitsOf(it).codec == prefs.codec }
            .thenBy { it.size },
    )
}

/** Files within this of the lightest count as "the smallest" too: then a link, a quick swarm or the preferred codec decide. */
const val NEAR_SIZE = 1.1

/**
 * Whether a season scan can stop asking for this episode: what [preferredPick] would take already is the preferred
 * format at the preferred resolution, as a direct link. Slow plugins are then not waited on.
 * ponytail: a smaller file may still be on its way from a slow plugin; the scan trades that for not waiting minutes.
 */
fun goodEnough(hits: List<Hit>, prefs: DownloadPrefs): Boolean {
    val pick = preferredPick(hits, prefs) ?: return false
    // A link: a torrent isn't the priority, so a scan holding only torrents waits for the slow plugins' links.
    return pick.direct && resolutionDistance(pick.quality, prefs.quality) == 0 && pick.quality != null &&
        (prefs.format.isEmpty() || traitsOf(pick).format == prefs.format)
}

/**
 * Whether what [preferredPick] takes from [hits] is what was asked for: the preferred resolution and format (720p MKV).
 * Unlike [goodEnough], a slow torrent still counts: this is about which file, not how fast.
 */
fun onTarget(hits: List<Hit>, prefs: DownloadPrefs, pickBy: (List<Hit>, DownloadPrefs) -> Hit? = ::preferredPick): Boolean {
    val pick = pickBy(hits, prefs) ?: return false
    return pick.quality != null && resolutionDistance(pick.quality, prefs.quality) == 0 && (prefs.format.isEmpty() || traitsOf(pick).format == prefs.format)
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

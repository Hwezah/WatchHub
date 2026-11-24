package com.torrentviewer.core

/*
 * What Play streams: the viewer's preferred streaming resolution (Settings → Playback), apart from the download one.
 * Pure, tested on the JVM.
 */

/** The streaming resolutions offered: "" is Auto. */
val STREAM_QUALITIES = listOf("", "4K", "1080p", "720p", "480p")

/** A resolution's height: "4K" and "2160p" 2160, "720p" 720; null for one not known ("SD", "CAM", none). */
fun resolutionOf(quality: String?): Int? = when {
    quality == null -> null
    quality.equals("4K", ignoreCase = true) || quality.equals("UHD", ignoreCase = true) -> 2160
    else -> Regex("""^(\d{3,4})p$""", RegexOption.IGNORE_CASE).matchEntire(quality.trim())?.groupValues?.get(1)?.toIntOrNull()
}

/** The resolution Play aims for: the one chosen, or for Auto ("") 1080p on Wi-Fi and 720p on mobile data, as YouTube does. */
fun streamTarget(wanted: String, onMobileData: Boolean): String = wanted.ifEmpty { if (onMobileData) "720p" else "1080p" }

/**
 * [hits] in the order Play tries them for [target] ([streamTarget]): that resolution first, then the nearest below it
 * (lighter, so it streams), then the nearest above; those whose resolution isn't known last. Within a resolution the
 * order found is kept: the search's own ranking.
 */
fun streamOrder(hits: List<Hit>, target: String): List<Hit> {
    val aim = resolutionOf(target) ?: return hits
    // A stable sort, by tier (match, below, above, unknown) and then how far off.
    return hits.sortedBy { h ->
        val r = resolutionOf(h.quality)
        when {
            r == null -> 3 * 10_000
            r == aim -> 0
            r < aim -> 10_000 + (aim - r)
            else -> 2 * 10_000 + (r - aim)
        }
    }
}

/** What to tell the viewer when Play couldn't find [target] and plays [playing]'s resolution instead; null when it's a match. */
fun streamNote(playing: Hit?, target: String): String? {
    val aim = resolutionOf(target) ?: return null
    val got = playing?.quality ?: return null
    return if (resolutionOf(got) == aim) null else "No $target source — playing $got"
}

/** The resolutions among [hits], highest first, one label each ("4K" and "2160p" alike), for the player's Sources panel to filter by. */
fun sourceResolutions(hits: List<Hit>): List<String> =
    hits.mapNotNull { h -> h.quality?.takeIf { resolutionOf(it) != null } }.distinctBy { resolutionOf(it) }.sortedByDescending { resolutionOf(it) }

/**
 * [hits] as the player's Sources panel lists them, at [quality] only when one is chosen (null or "": every one):
 * links before torrents (the owner's rule), then the higher resolution, then a link's lighter file (less to wait on;
 * an unknown size after) or a torrent's bigger swarm. Another app's links are left out: the player can't play them.
 */
fun sourceOrder(hits: List<Hit>, quality: String?): List<Hit> {
    val want = quality?.takeIf { it.isNotEmpty() }?.let(::resolutionOf)
    return hits.filter { !it.external && (want == null || resolutionOf(it.quality) == want) }.sortedWith(
        compareByDescending<Hit> { it.direct }
            .thenByDescending { resolutionOf(it.quality) ?: 0 }
            .thenBy { if (it.direct && it.size <= 0) 1 else 0 }
            .thenBy { if (it.direct) it.size else -it.seeders.toLong() },
    )
}

/** A source that says it can only be downloaded: "[10Gbps Download Only]", "Direct Download". */
private val DOWNLOAD_ONLY = Regex("""(?i)download[\s._-]*only|direct[\s._-]*download""")

/** Whether [hit] is for saving, not playing: its row opens the download sheet rather than the player (the owner's choice). */
fun downloadOnly(hit: Hit): Boolean = DOWNLOAD_ONLY.containsMatchIn(hit.name)

/** Sizes within this share of each other are taken for one file, as sites state them ("1.4GB", "1.40 GB", "1433 MB"). */
private const val SAME_SIZE = 0.006

/** Whether two links are one file ([foldCopies]): both direct, one resolution, 50 MB or more, sizes within [SAME_SIZE]. */
fun sameFile(a: Hit, b: Hit): Boolean = a.direct && b.direct && a.quality == b.quality &&
    a.size >= 50L * 1024 * 1024 && b.size >= 50L * 1024 * 1024 && kotlin.math.abs(a.size - b.size) <= a.size * SAME_SIZE

/**
 * One row per file (the owner's choice): direct links of the same resolution and, within [SAME_SIZE], the same size are
 * one file served by several sites, servers or addons (HDHub's 1.40 GB four times, CineStream's 514.73 MB three times).
 * Each group in the order its first copy came, as (the copy shown, every copy); the copy shown is the one [rank] puts
 * highest (a trusted site), else the first. Torrents (each its own swarm), links of no size or under 50 MB are their own.
 */
fun foldCopies(hits: List<Hit>, rank: (Hit) -> Int = { 0 }): List<Pair<Hit, List<Hit>>> {
    val groups = mutableListOf<MutableList<Hit>>()
    for (h in hits) {
        val into = groups.firstOrNull { g -> sameFile(g.first(), h) }
        if (into != null) into += h else groups += mutableListOf(h)
    }
    return groups.map { g -> (g.maxByOrNull { rank(it) }?.takeIf { rank(it) > rank(g.first()) } ?: g.first()) to g.toList() }
}

/** Tube's resolutions (Settings → Playback): "" is Auto, YouTube's own adaptive stream. */
val TUBE_QUALITIES = listOf("", "1080p", "720p", "480p", "360p")

/**
 * Which of [heights] (a video's offered resolutions) Tube opens at for [wanted] ("720p"): that one, else the nearest below
 * (it plays sooner), else the nearest above; null for Auto or nothing offered.
 */
fun tubeHeight(heights: List<Int>, wanted: String): Int? {
    val w = resolutionOf(wanted) ?: return null
    val known = heights.filter { it > 0 }.distinct()
    return known.filter { it == w }.firstOrNull() ?: known.filter { it < w }.maxOrNull() ?: known.filter { it > w }.minOrNull()
}

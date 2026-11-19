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

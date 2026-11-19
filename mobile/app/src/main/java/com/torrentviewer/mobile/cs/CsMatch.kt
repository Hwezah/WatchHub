package com.torrentviewer.mobile.cs

/*
 * Matching a title as this app knows it (Cinemeta: an IMDb id, a name, a year) to a plugin's own
 * search results and episode lists. Pure, so it is tested on the JVM.
 */

private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
private val YEAR_IN_NAME = Regex("\\b(19|20)\\d{2}\\b")

/** "The Dark Knight (2008)" → "dark knight": case, punctuation, a leading "the" and a year in brackets don't count. */
fun normTitle(s: String): String = s.lowercase()
    .replace(Regex("\\((19|20)\\d{2}\\)|\\[(19|20)\\d{2}]"), " ")
    .replace(NOT_WORD, " ").trim()
    .removePrefix("the ").trim()

/** A plugin's search result, as far as matching needs it. [series] is null when the plugin didn't say. */
data class Candidate(val name: String, val year: Int?, val series: Boolean?)

/**
 * How well [c] is the title asked for; null rules it out. The name must be the same once normalised
 * (or the same with a year left in it); a known year must be within one (sites differ on release vs
 * premiere); a known kind must agree.
 */
fun matchScore(title: String, year: Int?, series: Boolean, c: Candidate): Int? {
    if (c.series != null && c.series != series) return null
    val want = normTitle(title)
    if (want.isEmpty()) return null
    val got = normTitle(c.name)
    val name = when {
        got == want -> 100
        // "Dune Part Two 2024" for "Dune: Part Two".
        normTitle(c.name.replace(YEAR_IN_NAME, " ")) == want -> 90
        else -> return null
    }
    val when_ = when {
        year == null || c.year == null -> 0
        c.year == year -> 30
        kotlin.math.abs(c.year - year) == 1 -> 10
        else -> return null
    }
    return name + when_
}

/**
 * The index in [candidates] of the best match, or null when none matches. Some plugins (StreamPlay) label every
 * search result a movie, series included: when all of them claim the same kind, that kind says nothing, so a
 * second pass ignores it rather than miss the show.
 */
fun bestMatch(title: String, year: Int?, series: Boolean, candidates: List<Candidate>): Int? {
    fun best(list: List<Candidate>) = list.withIndex().mapNotNull { (i, c) -> matchScore(title, year, series, c)?.let { i to it } }.maxByOrNull { it.second }?.first
    best(candidates)?.let { return it }
    val kinds = candidates.map { it.series }.distinct()
    return if (kinds.size == 1 && kinds[0] != null && kinds[0] != series) best(candidates.map { it.copy(series = null) }) else null
}

/**
 * The index in [episodes] (each a plugin episode's season and number, either possibly unknown) of
 * [season]x[episode]. A list with no seasons at all is one season, or numbered through the show
 * ([absolute], as anime sites number it).
 */
fun pickEpisode(episodes: List<Pair<Int?, Int?>>, season: Int, episode: Int, absolute: Int? = null): Int? {
    episodes.indexOfFirst { (s, e) -> s == season && e == episode }.takeIf { it >= 0 }?.let { return it }
    if (episodes.all { it.first == null || it.first == 1 }) {
        if (season == 1) episodes.indexOfFirst { it.second == episode }.takeIf { it >= 0 }?.let { return it }
        if (absolute != null) episodes.indexOfFirst { it.second == absolute }.takeIf { it >= 0 }?.let { return it }
    }
    return null
}

/** CloudStream's Qualities.Unknown. */
private const val UNKNOWN_QUALITY = 400

/** The usual heights: taken as they are. */
private val STANDARD_HEIGHTS = setOf(2160, 1440, 1080, 720, 576, 480, 360, 240, 144)

/**
 * A plugin's quality number (2160, 1080…; CloudStream's Qualities) as the label the sources list sorts by.
 * Some plugins report a widescreen film's real height, its black bars cut off (1536×640, 1920×800, 900×376): an
 * odd height is placed by the width it implies at the cinema shape (2.4:1), so 640 files under 720p and 800 under
 * 1080p rather than tabs of their own.
 */
fun qualityLabel(q: Int): String? {
    if (q == UNKNOWN_QUALITY || q < 100) return null
    if (q in STANDARD_HEIGHTS || q > 2160) return bucket(q)
    val width = q * 2.4
    return when {
        width >= 3600 -> "4K"
        width >= 1800 -> "1080p"
        width >= 1200 -> "720p"
        width >= 800 -> "480p"
        width >= 560 -> "360p"
        else -> "${q}p"
    }
}

/** A standard height's label: 4K at 2160 and up. */
private fun bucket(q: Int) = when {
    q >= 2160 -> "4K"
    q >= 1440 -> "1440p"
    q >= 1080 -> "1080p"
    q >= 720 -> "720p"
    else -> "${q}p"
}

/** A plugin subtitle's language ("English", "en", "eng") as this app's two-letter code, else as given. */
fun subtitleLang(lang: String, known: List<Pair<String, Regex>>): String {
    val l = lang.trim()
    known.firstOrNull { it.second.containsMatchIn(l) }?.let { return it.first }
    return if (l.length == 2) l.uppercase() else l
}

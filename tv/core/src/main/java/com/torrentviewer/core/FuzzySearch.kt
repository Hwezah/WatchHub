package com.torrentviewer.core

import java.text.Normalizer

/*
 * Title search that forgives: a misspelt word, a missing or extra "the", "and" for "&", a number written
 * as a word or a numeral, a year typed after the name, accents, run-together words. The catalogues only
 * match what's typed, so [searchVariants] says what to ask them, and [fuzzyScore] ranks what comes back
 * by how close it is to what was meant. Pure, so it is tested on the JVM.
 */

private val ROMAN = listOf("i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")
private val WORDS = listOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
private val ARTICLES = setOf("the", "a", "an")
private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")

/** Lower case, accents off ("Amélie" → "amelie"), "&" as "and", everything but letters and digits a single space. */
fun simplify(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The words of [s], simplified, each number written one way ("ii", "two" → "2"), without "the"/"a"/"an". */
private fun canonicalWords(s: String): List<String> {
    val numbers = (ROMAN + WORDS).toMap()
    return simplify(s).split(' ').filter { it.isNotEmpty() && it !in ARTICLES }.map { numbers[it] ?: it }
}

/** A year typed with the name ("dune 2021"), to rank that year's release first. */
fun yearIn(query: String): String? = YEAR.find(query)?.value

/**
 * What to ask the catalogues for [query], most likely first, no repeats: as typed; without a year; without
 * "the"; numbers the other way ("rocky 2" ↔ "rocky ii"); "and" ↔ "&". At most six.
 */
fun searchVariants(query: String): List<String> {
    val typed = query.trim().replace(Regex("\\s+"), " ")
    if (typed.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    out += typed
    val noYear = YEAR.replace(typed, "").replace(Regex("\\s+"), " ").trim()
    if (noYear.length >= 2) out += noYear
    val base = noYear.ifEmpty { typed }
    val words = simplify(base).split(' ').filter { it.isNotEmpty() }
    if (words.isNotEmpty()) {
        out += words.joinToString(" ")
        if (words.first() in ARTICLES && words.size > 1) out += words.drop(1).joinToString(" ")
        val last = words.last()
        val swapped = (ROMAN + WORDS).firstNotNullOfOrNull { (w, n) -> if (last == w) n else if (last == n && (w.length > 1 || words.size > 1)) w else null }
        if (swapped != null) out += (words.dropLast(1) + swapped).joinToString(" ")
        if ("and" in words) out += words.joinToString(" ").replace(" and ", " & ")
    }
    return out.filter { it.isNotBlank() }.take(6)
}

/**
 * The second round, when the first found nothing close (a typo in a word is the usual reason): each word
 * of four letters or more on its own, then each pair of neighbouring words — a typo in one word leaves the
 * others to find the title by. Longest words first; at most five.
 */
fun partialQueries(query: String): List<String> {
    val words = canonicalWords(YEAR.replace(query, "")).filter { it.isNotEmpty() }
    if (words.size < 2) {
        // One word, perhaps misspelt: its first part ("intersteller" → "interst") still finds it by prefix.
        val w = words.firstOrNull() ?: return emptyList()
        return if (w.length >= 6) listOf(w.take((w.length * 0.6).toInt())) else emptyList()
    }
    val singles = words.filter { it.length >= 4 }.sortedByDescending { it.length }
    val pairs = words.zipWithNext { a, b -> "$a $b" }
    return (pairs + singles).distinct().take(5)
}

/** Edits (insert, delete, change, swap neighbours) between [a] and [b]. */
fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}

/** How alike two words are, 0–1: the same is 1, a typo or two in a long word still close. */
private fun wordLikeness(a: String, b: String): Double {
    if (a == b) return 1.0
    // A word typed only in part ("interst" for "interstellar") counts nearly as the whole.
    if (a.length >= 3 && b.startsWith(a)) return 0.9
    val longest = maxOf(a.length, b.length)
    return (1.0 - editDistance(a, b).toDouble() / longest).coerceAtLeast(0.0)
}

/**
 * How well [title] answers [query], 0–1. Each typed word is matched to the title's closest word (typos
 * forgiven); words typed that the title lacks count against it, title words not typed count a little.
 * Run-together words are allowed for ("spiderman" for "Spider-Man"). The year typed, when it is the title's,
 * adds a little; an exact name tops everything.
 */
fun fuzzyScore(title: String, query: String, year: String? = null): Double {
    val q = canonicalWords(YEAR.replace(query, ""))
    val t = canonicalWords(title)
    if (q.isEmpty() || t.isEmpty()) return 0.0
    if (q == t) return 1.0 + if (year != null && yearIn(query) == year) 0.05 else 0.0
    val joinedQ = q.joinToString("")
    val joinedT = t.joinToString("")
    // "spiderman" / "spider man", "xmen" / "x-men": the same letters, spaced differently.
    val glued = if (joinedQ == joinedT) 0.97 else if (joinedT.startsWith(joinedQ) && joinedQ.length >= 4) 0.85 else 0.0
    val perWord = q.map { w -> t.maxOf { wordLikeness(w, it) } }
    val covered = perWord.average()
    // Typed words that matched nothing well, and the title's own words nobody typed (a sequel's subtitle).
    val missing = perWord.count { it < 0.6 }.toDouble() / q.size
    val extra = (t.size - q.size).coerceAtLeast(0).toDouble() / t.size
    var score = covered - 0.35 * missing - 0.15 * extra
    // The words in the order typed, from the start: "dark knight" for "The Dark Knight Rises" beats "Knight of the Dark".
    if (t.take(q.size).zip(q).all { (a, b) -> wordLikeness(b, a) >= 0.8 }) score += 0.1
    score = maxOf(score, glued)
    val typedYear = yearIn(query)
    if (typedYear != null && year != null) score += if (year == typedYear) 0.08 else -0.05
    return score.coerceIn(0.0, 1.0)
}

/** Close enough to show at all; below this a result is noise from a partial query. */
const val FUZZY_SHOW = 0.45

/** So close that a second round of searching won't find anything better. */
const val FUZZY_SURE = 0.85

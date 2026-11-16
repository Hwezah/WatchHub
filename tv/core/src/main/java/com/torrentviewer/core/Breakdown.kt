package com.torrentviewer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/*
 * A film's or episode's full story, to read before watching: Wikipedia's plot for a film, the show's Wikipedia episode
 * list (TVmaze's summary without one) for an episode. No AI and no subtitles (the owner's choice: faster, lighter);
 * the last paragraph — the ending — is kept apart, shown only when asked for. Reading the articles is pure, tested on
 * the JVM. ponytail: a timed, scene-by-scene version made by the viewer's AI from subtitles was tried and dropped for
 * speed (commit 91cd516); revisit if times are wanted again.
 */

/** One paragraph of the story. [from]/[to] are times, empty for a plot summary (all of them, now). */
data class Scene(val from: String, val to: String, val text: String)

/** A title's story in order. [ending] is shown only when asked for; [source] says where it came from, for the sheet's foot. */
data class Breakdown(val scenes: List<Scene>, val ending: List<Scene>, val source: String)

/** The stored form, scenes and ending as JSON; null when it holds no scenes. */
fun parseBreakdown(json: String): Pair<List<Scene>, List<Scene>>? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    fun scenes(name: String) = o.optJSONArray(name)?.objects().orEmpty().mapNotNull { s ->
        val text = s.optString("text").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Scene(s.optString("from").trim(), s.optString("to").trim(), text)
    }
    return scenes("scenes").takeIf { it.isNotEmpty() }?.let { it to scenes("ending") }
}

private val HEADING = Regex("""^(=+)\s*(.*?)\s*=+\s*$""")
private val PLOT = Regex("""^(plot|plot summary|synopsis|story|premise|summary)$""", RegexOption.IGNORE_CASE)

/**
 * The plot section of a Wikipedia article's plain text (its headings as "== Plot =="), its paragraphs in order;
 * empty when it has none.
 */
fun plotParagraphs(article: String): List<String> {
    val lines = article.lines()
    val from = lines.indexOfFirst { l -> HEADING.matchEntire(l.trim())?.let { PLOT.matches(it.groupValues[2]) } == true }
    if (from < 0) return emptyList()
    val level = HEADING.matchEntire(lines[from].trim())!!.groupValues[1].length
    val body = lines.drop(from + 1).takeWhile { l -> HEADING.matchEntire(l.trim())?.let { it.groupValues[1].length <= level } != true }
    return body.filter { it.isNotBlank() && HEADING.matchEntire(it.trim()) == null }.map { it.trim() }
}

/** A story's paragraphs as scenes, the last held back as the ending when there are two or more. */
fun plotBreakdown(paragraphs: List<String>): Pair<List<Scene>, List<Scene>>? {
    if (paragraphs.isEmpty()) return null
    val all = paragraphs.map { Scene("", "", it) }
    return if (all.size >= 2) all.dropLast(1) to all.takeLast(1) else all to emptyList()
}

/** Where the `{{…}}` starting at [start] ends (just past its closing braces), nested ones counted. */
private fun templateEnd(text: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < text.length - 1) {
        when {
            text.startsWith("{{", i) -> {
                depth++
                i += 2
            }
            text.startsWith("}}", i) -> {
                depth--
                i += 2
                if (depth == 0) return i
            }
            else -> i++
        }
    }
    return text.length
}

/** A template's body split at its own "|"s, not those inside a nested template or link; names in lower case. */
private fun templateParams(body: String): Map<String, String> {
    val parts = ArrayList<String>()
    var depth = 0
    val part = StringBuilder()
    var i = 0
    while (i < body.length) {
        val two = if (i + 1 < body.length) body.substring(i, i + 2) else ""
        when {
            two == "{{" || two == "[[" -> {
                depth++
                part.append(two)
                i += 2
            }
            two == "}}" || two == "]]" -> {
                depth--
                part.append(two)
                i += 2
            }
            body[i] == '|' && depth == 0 -> {
                parts += part.toString()
                part.clear()
                i++
            }
            else -> {
                part.append(body[i])
                i++
            }
        }
    }
    parts += part.toString()
    return parts.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).trim().lowercase() to p.substring(it + 1).trim() } }.toMap()
}

private val EPISODE_LIST = Regex("""\{\{\s*Episode list""", RegexOption.IGNORE_CASE)
private val SEASON_HEADING = Regex("""^=+\s*(?:Season|Series)\s+(\d+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

/**
 * An episode's summary from a Wikipedia page's wikitext: the `{{Episode list}}` row numbered [episode] within its
 * season, under a "Season N" (or "Series N") heading for [season] — or on a page of one season ([seasonPage]), any
 * row. Empty when it isn't there or has no summary.
 */
fun episodeSummary(wikitext: String, season: Int, episode: Int, seasonPage: Boolean): List<String> {
    val headings = SEASON_HEADING.findAll(wikitext).mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { m.range.first to it } }.toList()
    var at = 0
    while (true) {
        val start = EPISODE_LIST.find(wikitext, at)?.range?.first ?: return emptyList()
        val end = templateEnd(wikitext, start)
        at = end
        val params = templateParams(wikitext.substring(start + 2, (end - 2).coerceAtLeast(start + 2)))
        val number = params["episodenumber2"]?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: continue
        if (number != episode) continue
        val under = headings.lastOrNull { it.first < start }?.second
        if (!seasonPage && (under ?: 1) != season) continue
        return cleanWikitext(params["shortsummary"].orEmpty())
    }
}

/** Wikitext (or a little HTML) as plain paragraphs: links to their words, templates, references, comments and markup gone. */
fun cleanWikitext(text: String): List<String> {
    var s = text
        .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<ref[^>]*/>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""<ref[^>]*>.*?</ref>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    // Innermost templates first, until none are left.
    val template = Regex("""\{\{[^{}]*\}\}""")
    while (template.containsMatchIn(s)) s = s.replace(template, "")
    s = s.replace(Regex("""\[\[(?:[^|\]]*\|)?([^\]]*)\]\]"""), "$1")
        .replace(Regex("""\[https?://\S+\s([^\]]*)\]"""), "$1")
        .replace(Regex("""<br\s*/?>|</p>""", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("'''", "").replace("''", "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&ndash;", "–").replace("&mdash;", "—").replace("&#39;", "'").replace("&quot;", "\"")
    return s.split(Regex("""\n\s*\n""")).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
}

/** Stories read and kept a fortnight: a film's plot is long and seldom changes, and a reopen should be instant. */
object Breakdowns {
    private const val STORE = "story:v1:"
    private const val KEEP_MS = 14 * 24 * 60 * 60 * 1000L
    private const val WIKI = "https://en.wikipedia.org/w/api.php"

    /** What each story being read is doing, by [keyOf], for the sheet to say. */
    private val _steps = MutableStateFlow<Map<String, String>>(emptyMap())
    val steps: StateFlow<Map<String, String>> = _steps

    /** The key a title's (an episode's) story is kept and followed under. */
    fun keyOf(title: Title, episode: Episode?) = STORE + title.imdb + (episode?.let { ":${it.season}:${it.episode}" }.orEmpty())

    private fun step(key: String, what: String?) = _steps.update { if (what == null) it - key else it + (key to what) }

    /** What the sheet says when there's no story to be had. */
    const val NOTHING = "No plot summary on Wikipedia for this one yet."

    /** [title]'s story ([episode]'s, for a series). Throws with a message to show. */
    suspend fun of(title: Title, episode: Episode?): Breakdown {
        val key = keyOf(title, episode)
        Kv.cached(key)?.let { saved -> runCatching { read(saved) }.getOrNull()?.let { return it } }
        try {
            step(key, "Reading Wikipedia…")
            val made = (if (episode == null) film(title) else episode(title, episode)) ?: throw IllegalStateException(NOTHING)
            Kv.cache(key, write(made))
            return made
        } finally {
            step(key, null)
        }
    }

    /** A film's plot: its article by its IMDb id through Wikidata (that film's, no namesake's), else Wikipedia's search. */
    private suspend fun film(title: Title): Breakdown? {
        val page = articleOf(title.imdb) ?: runCatching {
            val q = "${title.title} ${title.year?.take(4).orEmpty()} film".trim()
            JSONObject(Net.get("$WIKI?action=query&list=search&srlimit=1&format=json&srsearch=${enc(q)}", quiet = true))
                .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return null
        val (scenes, ending) = plotBreakdown(plotParagraphs(extract(page) ?: return null)) ?: return null
        return Breakdown(scenes, ending, "Plot from Wikipedia")
    }

    /**
     * An episode's summary: from the show's Wikipedia pages (its season's page, its episode list, the show's own
     * article, in that order), else TVmaze's.
     */
    private suspend fun episode(title: Title, ep: Episode): Breakdown? {
        val show = articleOf(title.imdb)
        if (show != null) {
            val base = show.replace(Regex("""\s*\([^)]*series\)$""", RegexOption.IGNORE_CASE), "")
            val pages = listOf("$show season ${ep.season}" to true, "$base season ${ep.season}" to true, "List of $base episodes" to false, show to false).distinct()
            for ((page, seasonPage) in pages) {
                val (text, moved) = wikitext(page) ?: continue
                // A season's name that leads to the whole list (no page of its own) is read as the list: by its headings.
                val paragraphs = episodeSummary(text, ep.season, ep.episode, seasonPage && !moved)
                if (paragraphs.isNotEmpty()) return plotBreakdown(paragraphs)?.let { (s, e) -> Breakdown(s, e, "Summary from Wikipedia") }
            }
        }
        return tvmaze(title, ep)
    }

    /** TVmaze's few sentences on the episode, by the show's IMDb id. */
    private suspend fun tvmaze(title: Title, ep: Episode): Breakdown? {
        val imdb = title.imdb.takeIf { it.startsWith("tt") } ?: return null
        return runCatching {
            val show = JSONObject(Net.get("https://api.tvmaze.com/lookup/shows?imdb=$imdb", quiet = true)).getInt("id")
            val summary = JSONObject(Net.get("https://api.tvmaze.com/shows/$show/episodebynumber?season=${ep.season}&number=${ep.episode}", quiet = true)).optString("summary")
            cleanWikitext(summary).takeIf { it.isNotEmpty() }?.let { Breakdown(it.map { p -> Scene("", "", p) }, emptyList(), "Summary from TVmaze") }
        }.getOrNull()
    }

    /** The English Wikipedia article for an IMDb id, through Wikidata; null without one. */
    private suspend fun articleOf(imdb: String): String? {
        if (!imdb.startsWith("tt")) return null
        return runCatching {
            val item = JSONObject(Net.get("https://www.wikidata.org/w/api.php?action=query&list=search&format=json&srsearch=${enc("haswbstatement:P345=$imdb")}", quiet = true))
                .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.takeIf { it.startsWith("Q") }
            item?.let {
                JSONObject(Net.get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$it&props=sitelinks&sitefilter=enwiki&format=json", quiet = true))
                    .optJSONObject("entities")?.optJSONObject(it)?.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optString("title")?.takeIf { t -> t.isNotBlank() }
            }
        }.getOrNull()
    }

    /** An article as plain text, headings as "== Plot ==". */
    private suspend fun extract(page: String): String? = runCatching {
        val pages = JSONObject(Net.get("$WIKI?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles=${enc(page)}", quiet = true))
            .optJSONObject("query")?.optJSONObject("pages")
        pages?.keys()?.asSequence()?.mapNotNull { pages.optJSONObject(it)?.optString("extract") }?.firstOrNull { it.isNotBlank() }
    }.getOrNull()

    /**
     * A page's wikitext (its templates, where episode lists keep their summaries), and whether the name led on to another
     * page; null when there's no such page.
     */
    private suspend fun wikitext(page: String): Pair<String, Boolean>? = runCatching {
        val parse = JSONObject(Net.get("$WIKI?action=parse&prop=wikitext&redirects=1&format=json&formatversion=2&page=${enc(page)}", quiet = true))
            .optJSONObject("parse")
        parse?.optString("wikitext")?.takeIf { it.isNotBlank() }?.let { it to ((parse?.optJSONArray("redirects")?.length() ?: 0) > 0) }
    }.getOrNull()

    private fun scenesJson(s: List<Scene>) = JSONArray(s.map { JSONObject().put("from", it.from).put("to", it.to).put("text", it.text) })

    private fun write(b: Breakdown) =
        JSONObject().put("scenes", scenesJson(b.scenes)).put("ending", scenesJson(b.ending)).put("source", b.source).put("at", System.currentTimeMillis()).toString()

    /** A kept story, while it's under [KEEP_MS] old. */
    private fun read(raw: String): Breakdown {
        val o = JSONObject(raw)
        check(System.currentTimeMillis() - o.optLong("at") < KEEP_MS) { "old" }
        val (scenes, ending) = parseBreakdown(raw) ?: error("empty")
        return Breakdown(scenes, ending, o.optString("source"))
    }
}

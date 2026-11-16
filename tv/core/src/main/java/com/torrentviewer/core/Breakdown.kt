package com.torrentviewer.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/*
 * A film's or episode's story, scene by scene with times, to read before watching. The times come from its subtitles
 * (each line says when it's spoken), which the viewer's AI reads and retells; the final act is kept apart so the
 * ending shows only when asked for. Without an AI or subtitles, Wikipedia's plot section stands in, untimed.
 * Parsing and the prompt are pure functions, tested on the JVM.
 */

/** One stretch of the story: where it starts and ends ("08:40", "1:02:15"; empty for an untimed summary) and what happens. */
data class Scene(val from: String, val to: String, val text: String)

/**
 * A title's story told in order. [ending] is the final act, shown only when asked for; [timed] is false for a plot
 * summary with no times. [source] says where it came from, for the sheet's foot.
 */
data class Breakdown(val scenes: List<Scene>, val ending: List<Scene>, val timed: Boolean, val source: String)

/** One subtitle line: when it starts, in milliseconds, and what's said. */
data class Cue(val at: Long, val text: String)

private val SRT_TIME = Regex("""^\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->""")
private val ASS_TIME = Regex("""^(\d+):(\d{2}):(\d{2})[.](\d{1,2})$""")
private val TAGS = Regex("""<[^>]*>|\{[^}]*\}""")

/**
 * The lines of a subtitle file — SubRip, WebVTT or SSA/ASS — with their start times, in order. Styling tags,
 * cue numbers and notes are left out, and so are lines with nothing said (♪ alone).
 */
fun subtitleCues(file: String): List<Cue> {
    val text = file.trimStart('﻿').replace("\r\n", "\n").replace('\r', '\n')
    val cues = ArrayList<Cue>()
    fun add(at: Long, said: String) {
        val clean = said.replace(TAGS, "").replace("\\N", " ").replace("\\n", " ").replace(Regex("\\s+"), " ").trim()
        if (clean.any { it.isLetterOrDigit() }) cues += Cue(at, clean)
    }
    if (text.contains("[Events]", ignoreCase = true) || text.lineSequence().any { it.startsWith("Dialogue:") }) {
        for (line in text.lineSequence()) {
            if (!line.startsWith("Dialogue:")) continue
            // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text — the text may hold commas.
            val parts = line.removePrefix("Dialogue:").split(',', limit = 10)
            if (parts.size < 10) continue
            val m = ASS_TIME.matchEntire(parts[1].trim()) ?: continue
            val (h, mi, se, cs) = m.destructured
            add(((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + cs.padEnd(2, '0').toLong() * 10, parts[9])
        }
    } else {
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val at = lines.indexOfFirst { "-->" in it }
            if (at < 0) continue
            val m = SRT_TIME.find(lines[at]) ?: continue
            val (h, mi, se, ms) = m.destructured
            val start = ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
            add(start, lines.drop(at + 1).joinToString(" "))
        }
    }
    return cues.sortedBy { it.at }
}

/** "08:40" under an hour, "1:02:15" past it. */
fun stamp(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, sec) else "%02d:%02d".format(java.util.Locale.ROOT, m, sec)
}

/**
 * The dialogue as the AI reads it: a line per half minute, starting with its time ("[12:30] …"). Past [maxChars]
 * each half minute is cut short alike, so the whole running time is still there to retell — and a film's worth stays
 * short enough to be read in a few seconds (60,000 characters is most of a film's dialogue).
 */
fun transcript(cues: List<Cue>, maxChars: Int = 60_000): String {
    val windows = cues.groupBy { it.at / 30_000 }.toSortedMap().map { (w, c) -> stamp(w * 30_000) to c.joinToString(" ") { it.text } }
    if (windows.isEmpty()) return ""
    val each = (maxChars / windows.size - 10).coerceAtLeast(40)
    return windows.joinToString("\n") { (at, said) -> "[$at] ${if (said.length > each) said.take(each) + "…" else said}" }
}

/**
 * What the AI is asked: the story retold in [parts] stretches with their times, plain sentences, everything up to the
 * final act in "scenes" and the final act and ending in "ending" only, so the ending can stay hidden. [name] is the
 * film ("Interstellar (2014)") or episode ("Severance S1:E3 · In Perpetuity").
 */
fun breakdownPrompt(name: String, said: String, parts: IntRange): String =
    "Below is the dialogue of $name, from its subtitles: each line starts with the time it is spoken.\n\n" +
        "In English, retell the story for someone deciding whether to watch it, as ${parts.first} to ${parts.last} parts covering the whole running " +
        "time in order. Each part has the time it starts and ends (as in the dialogue's times) and two or three plain sentences on what " +
        "happens, naming the characters as the story does. No markdown. Put everything before the final act in \"scenes\", and the final " +
        "act — the climax, how it ends, and any twist revealed there — in \"ending\" only, so it can be kept hidden.\n\n" +
        "Reply with only JSON: {\"scenes\": [{\"from\": \"00:00\", \"to\": \"08:40\", \"text\": \"...\"}], \"ending\": [{\"from\": \"...\", \"to\": \"...\", \"text\": \"...\"}]}\n\n" +
        "Dialogue:\n$said"

/** The AI's answer as scenes and ending; null when it holds no scenes (the reply wasn't the JSON asked for). */
fun parseBreakdown(reply: String): Pair<List<Scene>, List<Scene>>? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val o = runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull() ?: return null
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

/** A plot summary's paragraphs as untimed scenes, the last held back as the ending when there are two or more. */
fun plotBreakdown(paragraphs: List<String>): Pair<List<Scene>, List<Scene>>? {
    if (paragraphs.isEmpty()) return null
    val all = paragraphs.map { Scene("", "", it) }
    return if (all.size >= 2) all.dropLast(1) to all.takeLast(1) else all to emptyList()
}

/** Breakdowns made and kept: one AI request per film or episode, ever. */
object Breakdowns {
    private const val STORE = "breakdown:v1:"

    /** What each breakdown being made is doing ("Finding subtitles…"), by [keyOf], for the sheet to say. */
    private val _steps = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val steps: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _steps

    /** The key a title's (an episode's) breakdown is kept and followed under. */
    fun keyOf(title: Title, episode: Episode?) = STORE + title.imdb + (episode?.let { ":${it.season}:${it.episode}" }.orEmpty())

    private fun step(key: String, what: String?) = _steps.update { if (what == null) it - key else it + (key to what) }

    /** Past this, it's said to have taken too long rather than left spinning. */
    private const val MOST_MS = 150_000L

    /** What the sheet says when there's nothing to tell from. */
    const val NOTHING = "No subtitles or plot summary found for this one."

    /**
     * [title]'s story ([episode]'s, for a series), kept once made. From its subtitles through the viewer's AI when one
     * is set up; else, for a film, Wikipedia's plot. [subdlKey] widens the subtitle search. Throws with a message to show.
     */
    suspend fun of(title: Title, episode: Episode?, subdlKey: String): Breakdown {
        val key = keyOf(title, episode)
        Kv.cached(key)?.let { saved -> runCatching { read(saved) }.getOrNull()?.let { return it } }
        // Made outside the sheet that asked: closed before it's done, it's still kept, and asking again waits for the same one.
        val job = making.getOrPut(key) {
            // Off the main thread: a subtitle file is unzipped and read through here.
            AppScope.async(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    // Only the AI's is kept: Wikipedia's stands in when the AI is missing or failed, and is cheap to read again.
                    kotlinx.coroutines.withTimeoutOrNull(MOST_MS) { make(key, title, episode, subdlKey) }
                        ?.also { if (it.timed) Kv.cache(key, write(it)) }
                        ?: throw IllegalStateException("That took too long — try again")
                } finally {
                    making.remove(key)
                    step(key, null)
                }
            }
        }
        job.start()
        return job.await()
    }

    /** Breakdowns being made, by key. */
    private val making = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Breakdown>>()

    /** From the subtitles through the AI, else Wikipedia's plot; throws with a message to show. */
    private suspend fun make(key: String, title: Title, episode: Episode?, subdlKey: String): Breakdown {
        var why: Throwable? = null
        // A timeout or a closed app is no failure to explain: it goes straight up.
        fun unlessCancelled(e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        val made = (if (AiSearch.ready.value) runCatching { fromSubtitles(key, title, episode, subdlKey) }.onFailure { unlessCancelled(it); why = it }.getOrNull() else null)
            ?: if (episode == null) runCatching { step(key, "Reading Wikipedia's plot…"); fromWikipedia(title) }.onFailure(::unlessCancelled).getOrNull() else null
        return made ?: throw IllegalStateException(
            why?.message ?: if (AiSearch.ready.value) NOTHING else "$NOTHING Add an AI in Settings → AI Search for a scene-by-scene breakdown from subtitles.",
        )
    }

    /** The subtitles found for it, the first few tried until one reads, retold by the viewer's AI. Null with no subtitles. */
    private suspend fun fromSubtitles(key: String, title: Title, episode: Episode?, subdlKey: String): Breakdown? {
        step(key, "Finding subtitles…")
        val subs = findSubtitles(
            query = title.title, imdb = title.imdb.takeIf { it.startsWith("tt") }, season = episode?.season, episode = episode?.episode,
            type = if (episode != null) "tv" else "movie", language = "EN", subdlKey = subdlKey, fileName = "",
        )
        // English first: it's what the retelling is written in, and the language most subtitles exist in. The first
        // few fetched at once, the first in that order that reads kept: one slow server doesn't hold up the rest.
        step(key, "Reading the subtitles…")
        val english = setOf("en", "eng", "english")
        val cues = kotlinx.coroutines.coroutineScope {
            subs.sortedBy { if (it.language.lowercase() in english) 0 else 1 }.take(3)
                .map { sub -> async { runCatching { cuesFrom(sub.url) }.getOrNull()?.takeIf { it.size >= 40 } } }.awaitAll()
        }.firstOrNull { it != null } ?: return null
        val name = if (episode != null) "${title.title} S${episode.season}:E${episode.episode} · ${episode.name}" else "${title.title}${title.year?.let { " (${it.take(4)})" }.orEmpty()}"
        step(key, "${AiSearch.provider()?.label ?: "The AI"} is writing it up…")
        val reply = AiSearch.ask(breakdownPrompt(name, transcript(cues), if (episode != null) 4..8 else 8..12), timeoutMs = 90_000, quick = true)
        val (scenes, ending) = parseBreakdown(reply) ?: throw IllegalStateException("The AI's answer wasn't a breakdown — try again")
        return Breakdown(scenes, ending, timed = true, source = "From the subtitles, retold by ${AiSearch.provider()?.label ?: "AI"}")
    }

    /** A subtitle link's lines: the file itself, or the first subtitle inside a zip (SubDL's). */
    private suspend fun cuesFrom(url: String): List<Cue>? {
        val bytes = Net.bytes(url) ?: return null
        val text = if (bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.take(50)
                    .firstOrNull { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("srt", "vtt", "ass", "ssa") }
                    ?.let { capped(zip)?.toString(Charsets.UTF_8) }
            }
        } else bytes.toString(Charsets.UTF_8)
        return text?.let(::subtitleCues)
    }

    /** A zip entry's bytes, at most 4 MB: a subtitle is far smaller, and a bigger one isn't one. */
    private fun capped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > 4 * 1024 * 1024) return null
        }
    }

    /** A film's Wikipedia plot, found by its IMDb id through Wikidata, so it's that film's article and no namesake's. */
    private suspend fun fromWikipedia(title: Title): Breakdown? {
        val imdb = title.imdb.takeIf { it.startsWith("tt") } ?: return null
        val item = JSONObject(Net.get("https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=${enc("haswbstatement:P345=$imdb")}&format=json", quiet = true))
            .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title")?.takeIf { it.startsWith("Q") } ?: return null
        val page = JSONObject(Net.get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$item&props=sitelinks&sitefilter=enwiki&format=json", quiet = true))
            .optJSONObject("entities")?.optJSONObject(item)?.optJSONObject("sitelinks")?.optJSONObject("enwiki")?.optString("title")?.takeIf { it.isNotBlank() } ?: return null
        val pages = JSONObject(Net.get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles=${enc(page)}", quiet = true))
            .optJSONObject("query")?.optJSONObject("pages") ?: return null
        val article = pages.keys().asSequence().mapNotNull { pages.optJSONObject(it)?.optString("extract") }.firstOrNull { it.isNotBlank() } ?: return null
        val (scenes, ending) = plotBreakdown(plotParagraphs(article)) ?: return null
        return Breakdown(scenes, ending, timed = false, source = "Plot from Wikipedia")
    }

    private fun scenesJson(s: List<Scene>) = JSONArray(s.map { JSONObject().put("from", it.from).put("to", it.to).put("text", it.text) })

    private fun write(b: Breakdown) = JSONObject().put("scenes", scenesJson(b.scenes)).put("ending", scenesJson(b.ending)).put("timed", b.timed).put("source", b.source).toString()

    private fun read(raw: String): Breakdown {
        val o = JSONObject(raw)
        val (scenes, ending) = parseBreakdown(raw) ?: error("empty")
        return Breakdown(scenes, ending, o.optBoolean("timed", true), o.optString("source"))
    }
}

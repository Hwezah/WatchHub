package com.torrentviewer.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One YouTube video as a list shows it: enough for a card (thumbnail, duration, title, channel, views, age) and to
 * open it again from History or Watch Later without asking YouTube first. [at]: when it was uploaded (ms; 0 unknown),
 * which orders the Subscriptions feed. [seconds]: its length, 0 unknown or live.
 */
data class TubeVideo(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelUrl: String? = null,
    val avatar: String? = null,
    val thumb: String? = null,
    val seconds: Long = 0,
    val views: Long = -1,
    val ago: String? = null,
    val at: Long = 0,
    val live: Boolean = false,
) {
    val url get() = "https://www.youtube.com/watch?v=$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("channel", channel).put("channelUrl", channelUrl)
        .put("avatar", avatar).put("thumb", thumb).put("seconds", seconds).put("views", views).put("ago", ago).put("at", at).put("live", live)

    companion object {
        /** Null for a stored entry missing its id (written by a newer or broken build): skipped rather than shown blank. */
        fun from(o: JSONObject): TubeVideo? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            return TubeVideo(
                id, o.optString("title"), o.optString("channel"), o.str("channelUrl"), o.str("avatar"), o.str("thumb"),
                o.optLong("seconds"), o.optLong("views", -1), o.str("ago"), o.optLong("at"), o.optBoolean("live"),
            )
        }
    }
}

/** A channel subscribed to, kept on the phone: its page link, its name, and its picture once seen. */
data class TubeChannel(val url: String, val name: String, val avatar: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("name", name).put("avatar", avatar)

    companion object {
        fun from(o: JSONObject): TubeChannel? = o.optString("url").takeIf { it.isNotBlank() }?.let { TubeChannel(it, o.optString("name").ifBlank { "Channel" }, o.str("avatar")) }
    }
}

/** Videos to and from the JSON array kept in [Kv]; unreadable entries dropped. */
fun tubeVideos(json: String?): List<TubeVideo> =
    runCatching { JSONArray(json ?: return emptyList()) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TubeVideo::from) } }.orEmpty()

fun tubeVideosJson(videos: List<TubeVideo>): String = JSONArray(videos.map { it.toJson() }).toString()

/** Channels to and from the JSON array kept in [Kv]. */
fun tubeChannels(json: String?): List<TubeChannel> =
    runCatching { JSONArray(json ?: return emptyList()) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(TubeChannel::from) } }.orEmpty()

fun tubeChannelsJson(channels: List<TubeChannel>): String = JSONArray(channels.map { it.toJson() }).toString()

/**
 * The 11-character id in any link YouTube uses for a video: watch?v=, youtu.be/, /shorts/, /live/, /embed/.
 * Null when there is none (a channel or playlist link).
 */
fun youtubeId(url: String): String? =
    Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/)([\w-]{11})""").find(url)?.groupValues?.get(1)

/**
 * The channel's lasting address, so the same channel added twice (by its /channel/UC… link and by an @handle that
 * redirects there, or with a trailing slash) is one subscription: the UC id when the link has it, else the link
 * without its query and trailing slash, lower-cased.
 */
fun channelKey(url: String): String =
    Regex("""/channel/(UC[\w-]{22})""").find(url)?.groupValues?.get(1) ?: url.substringBefore('?').trimEnd('/').lowercase(Locale.ROOT)

/** "74k", "1.2M", "999": a count the way YouTube writes it under a video. */
fun compactCount(n: Long): String = when {
    n < 0 -> ""
    n < 1_000 -> n.toString()
    n < 10_000 -> trimmed(n / 1_000.0) + "k"
    n < 1_000_000 -> "${n / 1_000}k"
    n < 10_000_000 -> trimmed(n / 1_000_000.0) + "M"
    n < 1_000_000_000 -> "${n / 1_000_000}M"
    else -> trimmed(n / 1_000_000_000.0) + "B"
}

/** One decimal, rounded down (1.27 → "1.2", as YouTube does), and none when it's 0 ("3", not "3.0"). */
private fun trimmed(x: Double): String {
    val tenths = (x * 10).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}

/** "1:53:09", "4:05", "0:42": a video's length on its thumbnail. Empty for 0 (unknown, or live). */
fun clockText(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** The grey line under a video's title: "Blue Rock TV • 74k views • 13 days ago", leaving out what isn't known. */
fun tubeLine(v: TubeVideo): String = listOfNotNull(
    v.channel.takeIf { it.isNotBlank() },
    if (v.live) "Live" else v.views.takeIf { it >= 0 }?.let { "${compactCount(it)} ${if (it == 1L) "view" else "views"}" },
    v.ago?.takeIf { it.isNotBlank() },
).joinToString(" • ")

/**
 * Subscriptions from Google Takeout's subscriptions.csv ("Channel Id,Channel Url,Channel Title" — the header is
 * in the account's language, so it is recognised by its UC id column, not by its words). Quoted titles with commas
 * are kept whole. Rows without a channel link are skipped.
 */
fun parseTakeoutSubscriptions(csv: String): List<TubeChannel> =
    csv.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.mapNotNull { line ->
        val cells = csvCells(line)
        val id = cells.firstOrNull { Regex("""^UC[\w-]{22}$""").matches(it) }
        val url = cells.firstOrNull { it.startsWith("http") && "youtube.com" in it } ?: id?.let { "https://www.youtube.com/channel/$it" } ?: return@mapNotNull null
        val name = cells.lastOrNull { it != id && it != url && it.isNotBlank() } ?: "Channel"
        TubeChannel(url.replace("http://", "https://"), name)
    }.distinctBy { channelKey(it.url) }.toList()

/** One CSV line's cells: commas inside quotes kept, doubled quotes ("") read as one. */
private fun csvCells(line: String): List<String> {
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { cells += cell.toString().trim(); cell.clear() }
            else -> cell.append(c)
        }
        i++
    }
    cells += cell.toString().trim()
    return cells
}

/**
 * Subscriptions from NewPipe's export ({"subscriptions": [{"service_id": 0, "url": …, "name": …}]}): YouTube's
 * (service 0) only. Empty when the text isn't one.
 */
fun parseNewPipeSubscriptions(json: String): List<TubeChannel> {
    val list = runCatching { JSONObject(json).optJSONArray("subscriptions") }.getOrNull() ?: return emptyList()
    return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        .filter { it.optInt("service_id", 0) == 0 }
        .mapNotNull { o -> o.optString("url").takeIf { "youtube.com" in it }?.let { TubeChannel(it, o.optString("name").ifBlank { "Channel" }) } }
        .distinctBy { channelKey(it.url) }
}

/** Either export: NewPipe's JSON when the file is JSON, Takeout's CSV otherwise. */
fun parseSubscriptions(text: String): List<TubeChannel> =
    if (text.trimStart().startsWith("{")) parseNewPipeSubscriptions(text) else parseTakeoutSubscriptions(text)

/**
 * The Home feed: the newest videos from subscriptions (newest first, at most [perChannel] from any one channel so a
 * channel that uploads ten a day doesn't fill it), then trending ones not already there. Either part can be empty —
 * no subscriptions yet: trending alone.
 */
fun homeFeed(fromSubs: List<TubeVideo>, trending: List<TubeVideo>, perChannel: Int = 3): List<TubeVideo> {
    val counts = HashMap<String, Int>()
    val subs = fromSubs.sortedByDescending { it.at }.filter { v ->
        val key = v.channelUrl?.let(::channelKey) ?: v.channel
        val n = counts.merge(key, 1, Int::plus) ?: 1
        n <= perChannel
    }
    val seen = subs.mapTo(HashSet()) { it.id }
    return subs + trending.filter { seen.add(it.id) }
}

/** Several lists woven together one from each in turn, duplicates (by id) kept only the first time. */
fun interleave(lists: List<List<TubeVideo>>): List<TubeVideo> {
    val seen = HashSet<String>()
    val out = ArrayList<TubeVideo>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.id)) out += it }
    return out
}

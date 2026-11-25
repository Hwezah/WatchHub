package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a download has learnt from its failures, per download (by its name): the links that failed it, the hosts that
 * kept answering "too busy", and how many waiting rounds it has had. Kept on disk with the queue, so a restarted app
 * doesn't go back to dead links or start the quick rounds over (the owner asked for no endless reloads).
 */
data class RetryMemory(
    val failed: Map<String, Set<String>> = emptyMap(),
    val busy: Map<String, Set<String>> = emptyMap(),
    val rounds: Map<String, Int> = emptyMap(),
)

/**
 * [memory] as JSON, only for the downloads in [keep] (the queue's names): a finished or cancelled download's history
 * goes with it. At most [MAX_REMEMBERED] links per download, the latest, so a download failing for days stays small.
 */
fun encodeRetryMemory(memory: RetryMemory, keep: Set<String>): String {
    val out = JSONObject()
    for (label in (memory.failed.keys + memory.busy.keys + memory.rounds.keys).filter { it in keep }.toSortedSet()) {
        val entry = JSONObject()
        memory.failed[label]?.takeIf { it.isNotEmpty() }?.let { entry.put("failed", JSONArray(it.toList().takeLast(MAX_REMEMBERED))) }
        memory.busy[label]?.takeIf { it.isNotEmpty() }?.let { entry.put("busy", JSONArray(it.toList())) }
        memory.rounds[label]?.takeIf { it > 0 }?.let { entry.put("rounds", it) }
        if (entry.length() > 0) out.put(label, entry)
    }
    return out.toString()
}

/** [raw] read back; anything unreadable is an empty memory, never a crash at launch. */
fun decodeRetryMemory(raw: String?): RetryMemory = runCatching {
    val o = JSONObject(raw ?: return RetryMemory())
    val failed = HashMap<String, Set<String>>()
    val busy = HashMap<String, Set<String>>()
    val rounds = HashMap<String, Int>()
    for (label in o.keys()) {
        val e = o.optJSONObject(label) ?: continue
        e.optJSONArray("failed")?.let { a -> failed[label] = (0 until a.length()).map(a::optString).filter(String::isNotEmpty).toCollection(LinkedHashSet()) }
        e.optJSONArray("busy")?.let { a -> busy[label] = (0 until a.length()).map(a::optString).filter(String::isNotEmpty).toSet() }
        e.optInt("rounds", 0).takeIf { it > 0 }?.let { rounds[label] = it }
    }
    RetryMemory(failed, busy, rounds)
}.getOrDefault(RetryMemory())

/** The most failed links kept per download: a download failing for days doesn't grow without end. */
const val MAX_REMEMBERED = 50

/** What a quick look at a link found: dead (or a web page), the whole file only, or a file it can be resumed in. */
enum class LinkCheck { DEAD, WHOLE, RESUMABLE }

/**
 * The fresh link to switch to, checked first rather than found out by downloading it: [next] gives the best link not in
 * the set it's handed (null when none is left), [check] looks at it. A dead one or a web page is skipped; a playlist,
 * a torrent or a link that can be resumed is taken at once; one that only sends the whole file is kept back while a
 * resumable one is looked for, and taken if none turns up. At most [limit] links are looked at.
 */
suspend fun checkedPick(limit: Int, next: (Set<String>) -> Hit?, check: suspend (Hit) -> LinkCheck): Hit? {
    val skip = HashSet<String>()
    var whole: Hit? = null
    repeat(limit) {
        val hit = next(skip) ?: return whole
        when (check(hit)) {
            LinkCheck.RESUMABLE -> return hit
            LinkCheck.WHOLE -> if (whole == null) whole = hit
            LinkCheck.DEAD -> {}
        }
        skip += hit.uri
    }
    return whole
}

/** How many stall restarts in a row, with nothing downloaded between them, before a link counts as dead. */
const val STALL_RESTARTS = 3

/**
 * Whether a download sat still for its [restarts]-th time with no progress between: then the link is given up on and a
 * fresh one is looked for, instead of the same hung link started again every 90 seconds for ever.
 */
fun stalledOut(restarts: Int) = restarts >= STALL_RESTARTS

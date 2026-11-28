package com.torrentviewer.mobile

import com.torrentviewer.core.Kv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A server-side resolver (the Cobalt API shape) for social videos: the page's address is sent to a helper that fetches
 * it on its own servers and hands back a direct media URL, which the app then downloads fast with its own connections.
 *
 * Why, the owner's ask: Instagram and the like demand a signed-in session and block VPN/phone IPs, so yt-dlp on the
 * phone is bounced to a login page even when signed in. The resolver does the fetching from its servers, so no login
 * and no VPN-IP wall reach this phone. A public post is all it's used for; it falls back to yt-dlp when it can't.
 *
 * Public instances come and go — the Cobalt project no longer runs one of its own, and community instances run out of
 * bandwidth or cookies and go dark. So the resolver isn't one address but a list ([INSTANCES]): each is tried in turn
 * until one answers with a direct URL, and the one that worked is remembered ([OK_KEY]) and tried first next time, so
 * later grabs are instant. The owner can still pin a single instance ([setEndpoint], Settings → their own self-hosted
 * one is the surest), which then overrides the list. Only the page address leaves the phone — nothing of the user's.
 */
object Resolve {
    /** The owner's pinned instance, if any (Settings): it overrides the built-in list entirely. */
    private const val KEY = "grab-resolver"

    /** The last instance in the list that actually resolved something: tried first next time. */
    private const val OK_KEY = "grab-resolver-ok"

    /**
     * Known Cobalt instances, tried in order. These rotate — some will be dead on any given day, which is why there's a
     * list and why the one that works is remembered. The owner's own self-hosted instance (set in Settings) is far more
     * reliable than any of these for Instagram, which needs the instance to carry a signed-in session.
     *
     * ponytail: a fixed list goes stale as instances come and go. A live directory (instances.cobalt.best) would keep it
     * fresh, but its exact JSON shape couldn't be pinned down here; revisit if grabs start failing across all of these.
     */
    private val INSTANCES = listOf(
        "https://cobalt-api.kwiatekmiki.com",
        "https://cobalt-api.meowing.de",
        "https://cobalt.255x.ru",
        "https://capi.3kh0.net",
        "https://cobalt.cjs.nz",
    )

    /** A real browser's identity: some instances turn away anything that doesn't look like a browser. */
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    /** The owner's pinned instance, or null for the built-in list. */
    fun endpoint(): String? = Kv.global(KEY)?.trim()?.ifBlank { null }

    fun setEndpoint(url: String?) = Kv.setGlobal(KEY, url?.trim())

    /** The instances to try, in order: the pinned one alone if set, else the last that worked followed by the rest. */
    private fun candidates(): List<String> {
        endpoint()?.let { return listOf(it) }
        val ok = Kv.global(OK_KEY)?.trim()?.ifBlank { null }
        return (listOfNotNull(ok) + INSTANCES).distinct()
    }

    /** A direct media URL the resolver returned, with the headers (if any) to fetch it with and a name when it gave one. */
    data class Resolved(val url: String, val filename: String?, val headers: Map<String, String>)

    /**
     * [page] resolved to a direct video URL at about [height] (null for best): each instance is tried until one answers,
     * and that instance is remembered. Null when none can resolve it — the caller then falls back to yt-dlp. Never throws.
     */
    suspend fun resolve(page: String, height: Int?): Resolved? = withContext(Dispatchers.IO) {
        for (base in candidates()) {
            val r = runCatching { ask(base, page, height) }.getOrNull()
            if (r != null) {
                // The working instance to the front of the line for next time (unless the owner pinned one).
                if (endpoint() == null) runCatching { Kv.setGlobal(OK_KEY, base) }
                return@withContext r
            }
        }
        null
    }

    /** One instance asked. Returns the resolved file, or null when this instance can't (dead, needs a key, won't resolve). */
    private fun ask(base: String, page: String, height: Int?): Resolved? {
        val body = JSONObject().put("url", page).put("downloadMode", "auto")
            .apply { if (height != null && height > 0) put("videoQuality", height.toString()) }.toString()
        val conn = (URL(base).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (text.isBlank()) return null
        val o = JSONObject(text)
        return when (o.optString("status")) {
            // One file to fetch: through the resolver's tunnel, or straight from the source it points to.
            "tunnel", "redirect", "stream" -> o.optString("url").takeIf { it.isNotBlank() }
                ?.let { Resolved(it, o.optString("filename").ifBlank { null }, emptyMap()) }
            // Several parts (a carousel): the first video.
            "picker" -> {
                val arr = o.optJSONArray("picker")
                val n = arr?.length() ?: 0
                val pick = (0 until n).map { arr!!.getJSONObject(it) }.firstOrNull { it.optString("type") == "video" } ?: arr?.optJSONObject(0)
                pick?.optString("url")?.takeIf { it.isNotBlank() }?.let { Resolved(it, null, emptyMap()) }
            }
            // "error" (needs a key, rate-limited, can't resolve) or anything else: this instance is no good, try the next.
            else -> null
        }
    }
}

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
 * The instance is configurable ([endpoint]); cleared, the resolver is off and yt-dlp runs as before. Only the address
 * leaves the phone — nothing of the user's — and only for a public post the user chose to grab.
 */
object Resolve {
    private const val KEY = "grab-resolver"

    /** A community Cobalt instance by default; the owner can point it elsewhere, or clear it to turn the resolver off. */
    private const val DEFAULT = "https://cobalt-api.kwiatekmiki.com"

    fun endpoint(): String? = (Kv.global(KEY) ?: DEFAULT).trim().ifBlank { null }

    fun setEndpoint(url: String?) = Kv.setGlobal(KEY, url?.trim())

    /** A direct media URL the resolver returned, with the headers (if any) to fetch it with and a name when it gave one. */
    data class Resolved(val url: String, val filename: String?, val headers: Map<String, String>)

    /**
     * [page] resolved to a direct video URL at about [height] (null for best), or null when the resolver is off, can't
     * be reached, or won't resolve it — the caller then falls back to yt-dlp. Never throws.
     */
    suspend fun resolve(page: String, height: Int?): Resolved? = withContext(Dispatchers.IO) {
        val base = endpoint() ?: return@withContext null
        runCatching {
            val body = JSONObject().put("url", page).put("downloadMode", "auto")
                .apply { if (height != null && height > 0) put("videoQuality", height.toString()) }.toString()
            val conn = (URL(base).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (text.isBlank()) return@runCatching null
            val o = JSONObject(text)
            when (o.optString("status")) {
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
                else -> null
            }
        }.getOrNull()
    }
}

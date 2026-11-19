package com.torrentviewer.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.torrentviewer.core.Kv
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Something playable or downloadable a page loaded: a video file, a stream, audio, a document. */
data class Sniffed(
    val url: String, val kind: String, val page: String, val title: String,
    /** The headers the page itself sent for it (Referer, Origin): a player in an embed checks its own. */
    val headers: Map<String, String> = emptyMap(),
    /** Second line, as 1DM shows it: resolution, length, size; or what it is ("Preview clip"). */
    val detail: String = "",
)

/**
 * One browser tab: its own page, history and finds. [incognito]: its pages aren't kept in history, and its cookies,
 * sign-ins, site storage and cache live apart from the other tabs' and are forgotten when the last such tab closes.
 */
class BTab(val id: Long, val incognito: Boolean = false) {
    @SuppressLint("StaticFieldLeak")
    var web: WebView? = null
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)
    val found = mutableStateListOf<Sniffed>()
    /** A stream's quality playlists already listed under it as "1080p", so they aren't listed again bare. */
    val children: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile var ua: String = ""
    /** A jump to another site that was stopped, offered with "Open anyway". */
    var stopped by mutableStateOf<String?>(null)
    /** One address let through after "Open anyway". */
    var pass: String? = null
}

/** A page bookmarked or visited. */
data class Place(val url: String, val title: String, val time: Long)

/** Something to download that the page offered or a link pointed at: the Browser's sheet asks how. */
data class Offer(val url: String, val title: String, val headers: Map<String, String>, val direct: Boolean, val kind: String)

/**
 * The Browser tab — the Grabber app's browser (same owner), ported: tabs, each a WebView kept for the app's life (a
 * tab switch doesn't reload); every request a page makes is watched for videos, streams, audio and files, and
 * requests to ad and tracker networks get an empty answer. Pop-ups and jumps to betting and ad sites are stopped.
 * Bookmarks and history are kept app-wide in [Kv].
 */
object Browser {
    private val main = Handler(Looper.getMainLooper())

    val tabs = mutableStateListOf(BTab(1))
    var current by mutableIntStateOf(0)
    val tab: BTab get() = tabs[current.coerceIn(0, tabs.lastIndex)]

    val url: String get() = tab.url
    val title: String get() = tab.title
    val loading: Boolean get() = tab.loading
    val progress: Int get() = tab.progress
    val canBack: Boolean get() = tab.canBack
    val found get() = tab.found

    /** Count up as a page scrolls down or up: the app's floating tab bar hides and comes back with them. */
    var scrolledDown by mutableIntStateOf(0)
    var scrolledUp by mutableIntStateOf(0)

    /** A page's video is full screen; [exitFull] leaves it (Back does). */
    var fullScreen by mutableStateOf(false)
    var exitFull: (() -> Unit)? = null

    /** Goes up when a tab's page had to be rebuilt (its renderer crashed): the screen puts the new one in. */
    var rebuilt by mutableIntStateOf(0)

    /* ------------------------------------------------------------ settings (Settings → Browser, as Grabber's) */

    /** Search engines: name to the address words are added to. */
    val ENGINES = listOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Startpage" to "https://www.startpage.com/do/search?q=",
    )

    private fun flag(key: String, default: Boolean) = Kv.global(key)?.let { it == "1" } ?: default
    private fun setFlag(key: String, on: Boolean) { Kv.setGlobal(key, if (on) "1" else "0"); applySettings() }

    /** Where typed words are searched: Google unless changed in Settings. */
    var engine: String
        get() = Kv.global("browserEngine")?.takeIf { e -> ENGINES.any { it.first == e } } ?: "Google"
        set(v) = Kv.setGlobal("browserEngine", v)
    var blockAds: Boolean get() = flag("browserBlockAds", true); set(v) = setFlag("browserBlockAds", v)
    var darkPages: Boolean get() = flag("browserDarkPages", true); set(v) = setFlag("browserDarkPages", v)
    var blockPopups: Boolean get() = flag("browserBlockPopups", true); set(v) = setFlag("browserBlockPopups", v)
    var blockRedirects: Boolean get() = flag("browserBlockRedirects", true); set(v) = setFlag("browserBlockRedirects", v)
    var sniffMedia: Boolean get() = flag("browserSniff", true); set(v) = setFlag("browserSniff", v)

    /** Settings changed: every open tab takes them at once. */
    private fun applySettings() = tabs.forEach { t ->
        t.web?.settings?.let { s ->
            s.javaScriptCanOpenWindowsAutomatically = true // windows are caught and judged ([catchPopup]), not refused
            darken(s)
        }
    }

    /* ------------------------------------------------------------ incognito */

    /** The WebView profile incognito tabs share: its own cookies, storage and cache, deleted with the last such tab. */
    private const val PRIVATE = "watchhub-incognito"

    /**
     * Whether this phone's WebView keeps incognito tabs' cookies and storage apart (its "profiles", recent WebViews).
     * Without it an incognito tab still keeps no history or cache, but shares sign-ins with the other tabs.
     */
    val trulyPrivate: Boolean by lazy { runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }

    /** The cookies [t] sees: the incognito profile's for an incognito tab (where kept apart), else everyone's. */
    private fun cookiesOf(t: BTab): CookieManager =
        if (t.incognito && trulyPrivate) runCatching { androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE).cookieManager }.getOrNull() ?: CookieManager.getInstance()
        else CookieManager.getInstance()

    /** The last incognito tab closed: everything it kept goes ([PRIVATE] deleted once its pages are gone). */
    private fun forgetIncognito() {
        if (!trulyPrivate) return
        main.postDelayed({ runCatching { if (tabs.none { it.incognito }) androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE) } }, 1500)
    }

    /** Settings: forget cookies, history and the cache, and go back to a blank start. */
    fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.forEach { t ->
            t.web?.apply {
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
            }
            t.url = ""
            t.title = ""
            t.found.clear()
            t.canBack = false
            t.canForward = false
        }
        clearHistory()
    }

    /** Counts up each time the page is touched: the tab bar goes away. */
    var touched by mutableIntStateOf(0)

    /** The one file the page was just asked for, so [BlobSaver] takes nothing a page sends it unasked. */
    @Volatile private var pending: String? = null

    /** A blob: or data: link's file, read inside the page (only it can) and handed to [BlobSaver]. */
    private fun saveMade(w: WebView, url: String, name: String, mime: String?) {
        val token = java.util.UUID.randomUUID().toString()
        pending = token
        val q = org.json.JSONObject.quote(url)
        val n = org.json.JSONObject.quote(name)
        val m = org.json.JSONObject.quote(mime ?: "")
        w.evaluateJavascript(
            "(function(){fetch($q).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();" +
                "f.onloadend=function(){WatchHubBlob.save('$token',$n,$m,f.result)};f.readAsDataURL(b)})" +
                ".catch(function(e){WatchHubBlob.save('$token',$n,$m,'')})})();",
            null,
        )
    }

    /**
     * What a page made itself, back from [saveMade]: a WireGuard config goes straight into the VPN (Settings → VPN);
     * anything else is saved to Grabber! by its kind.
     */
    private class BlobSaver {
        @android.webkit.JavascriptInterface
        fun save(token: String, name: String, mime: String, dataUrl: String) {
            if (token != pending) return
            pending = null
            val bytes = runCatching { android.util.Base64.decode(dataUrl.substringAfter("base64,", ""), android.util.Base64.DEFAULT) }.getOrNull()
            main.post {
                if (bytes == null || bytes.isEmpty()) { note = "Couldn't save $name"; return@post }
                val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                if (name.endsWith(".conf", true) && "[Interface]" in text && "PrivateKey" in text) {
                    val problem = Vpn.import(text)
                    offer = null
                    note = problem ?: "VPN config added. Turn the VPN on with the switch here or in Settings → VPN."
                    vpnAdded++
                } else {
                    runCatching { Downloads.saveFile(name, bytes) }
                        .onSuccess { note = "Saved $name to Grabber!/$it" }.onFailure { note = "Couldn't save $name" }
                }
            }
        }
    }

    /** Counts up when a page's VPN config was taken in: the Browser opens its sheet to show the switch. */
    var vpnAdded by mutableIntStateOf(0)

    /** A line the Browser's sheet shows once (no VPN config yet…). */
    var note by mutableStateOf<String?>(null)

    /** What the sheet should offer to download now (a tapped file link, a site's download, a find). */
    var offer by mutableStateOf<Offer?>(null)

    /** Ad and tracker networks: a request to any of these hosts (or their subdomains) gets an empty answer. */
    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com", "amazon-adsystem.com", "adnxs.com",
        "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net", "taboola.com", "outbrain.com",
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "moatads.com", "adsrvr.org", "bidswitch.net", "casalemedia.com",
        "smartadserver.com", "yieldmo.com", "media.net", "zedo.com", "popads.net", "popcash.net", "propellerads.com",
        "propellerclick.com", "exoclick.com", "exosrv.com", "adsterra.com", "adsterratech.com", "hilltopads.net", "juicyads.com",
        "trafficjunky.net", "trafficstars.com", "clickadu.com", "adcash.com", "admaven.com", "ad-maven.com", "a-ads.com",
        "onclickads.net", "onclkds.com", "mgid.com", "revcontent.com", "adskeeper.com", "yandexadexchange.net", "adform.net",
        "teads.tv", "spotxchange.com", "springserve.com", "lijit.com", "sharethrough.com", "33across.com", "indexww.com",
        "3lift.com", "contextweb.com", "advertising.com", "adtech.de", "serving-sys.com", "flashtalking.com", "zemanta.com",
        "facebook.net", "connect.facebook.net", "analytics.tiktok.com", "ads.tiktok.com", "ads-twitter.com", "static.ads-twitter.com",
        "adtrafficquality.google", "fundingchoicesmessages.google.com", "imasdk.googleapis.com", "ads.yahoo.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru", "an.yandex.ru", "realsrv.com", "tsyndicate.com", "dtscout.com",
        "acint.net", "bebi.com", "pemsrv.com", "a.realsrv.com", "syndication.realsrv.com", "displayvertising.com",
        "highperformanceformat.com", "profitablegatecpm.com", "effectivegatecpm.com", "whos.amung.us", "histats.com",
    )

    private val MEDIA = Regex("""\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mp3|m4a|aac|ogg|opus|flac|wav|zip|rar|7z|apk|pdf|torrent)(\?|#|$)""", RegexOption.IGNORE_CASE)

    /** Sites whose redirects are allowed (from the menu), for this run of the app. */
    val allowJumps = mutableStateListOf<String>()

    /** Where pop-under and betting jumps go: stopped even after a tap, since a tap on a player is often hijacked. */
    private val BAD_JUMP = Regex(
        """(^|[.-])(1x[a-z0-9-]*|bet(way|win|365|ika|pawa|winner|king)|sportybet|melbet|mostbet|22bet|linebet|parimatch|pin-?up|[a-z]*casino[a-z]*|""" +
            """adsterra|popads|propeller[a-z]*|clickadu|hilltopads|exoclick|trafficjunky|juicyads|popcash|galaksion|monetag|onclick[a-z]*|""" +
            """[a-z]*push[a-z]*notif[a-z]*)([.-]|$)""",
    )

    /** The site part of a host: "www.movies2watch.tv" → "movies2watch.tv". */
    fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val two = parts.takeLast(2)
        // co.uk, com.ng and the like keep one more part.
        return if (two[0].length <= 3 && two[1].length == 2) parts.takeLast(3).joinToString(".") else two.joinToString(".")
    }

    /** Whether this page jump should be stopped: to another site, and either untapped or to an ad or betting site. */
    private fun stopJump(t: BTab, request: WebResourceRequest): Boolean {
        if (!blockRedirects || !request.isForMainFrame) return false
        val to = request.url.toString()
        if (t.pass == to) { t.pass = null; return false }
        val from = siteOf(android.net.Uri.parse(t.url).host)
        val dest = siteOf(request.url.host)
        if (from.isEmpty() || from == dest || from in allowJumps) return false
        val bad = BAD_JUMP.containsMatchIn(request.url.host.orEmpty().lowercase())
        // A server's own redirect (sign-ins, short links) and a real tap go through, unless it's to an ad or betting site.
        return bad || (!request.hasGesture() && !request.isRedirect)
    }

    /** Opens a jump that was stopped. */
    fun openStopped() {
        val t = tab
        val u = t.stopped ?: return
        t.stopped = null
        t.pass = u
        t.web?.loadUrl(u)
    }

    private fun blockedHost(host: String): Boolean {
        // The big weekly list first (about 100,000 sites), then the built-in short list.
        if (AdBlock.blocks(host)) return true
        var h = host.lowercase()
        while (true) {
            if (h in AD_HOSTS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** "Video", "Stream (HLS)", "Audio"… for a link that is one; null for a page or a picture. */
    fun kindOf(url: String): String? {
        val u = url.lowercase()
        // YouTube's pieces: "Grab this page" does YouTube.
        if ("googlevideo.com/videoplayback" in u) return null
        val ext = MEDIA.find(u)?.groupValues?.get(1) ?: return null
        return when (ext) {
            "m3u8" -> "Stream (HLS)"
            "mpd" -> "Stream (DASH)"
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "flv" -> "Video"
            "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav" -> "Audio"
            "torrent" -> "Torrent"
            else -> "File"
        }
    }

    private fun sniff(t0: BTab, url: String, sent: Map<String, String> = emptyMap()) {
        if (!sniffMedia) return
        val kind = kindOf(url) ?: return
        // Pieces of a stream aren't worth listing; the stream itself is. "init-…mp4" is a stream's header (a few KB),
        // not a video: as 1DM, it's left out.
        val file = url.substringBefore('?').substringAfterLast('/').lowercase()
        if (kind != "Stream (HLS)" && (Regex("""(seg|chunk|frag|part)[-_]?\d+""").containsMatchIn(file) || Regex("""^init[-_.]|[-_.]init[-_.]""").containsMatchIn(file) || file.endsWith(".m4s"))) return
        if (url in t0.children) return
        val s = Sniffed(url, kind, t0.url, t0.title, sent)
        main.post { if (t0.found.none { it.url == url } && url !in t0.children) t0.found.add(0, s) }
        when {
            kind == "Stream (HLS)" && expanded.add(url) -> describeStream(t0, s)
            kind == "Video" && expanded.add(url) -> describeFile(t0, s)
        }
    }

    private val expanded = java.util.Collections.synchronizedSet(HashSet<String>())

    /** One HTTP client for the Browser's look-ups, short timeouts: a slow site never holds a thread for long. */
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** [url]'s text with the page's headers; null when it won't come. */
    private fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        val b = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    /** [url]'s size by a HEAD request; -1 unknown. */
    private fun sizeOf(url: String, headers: Map<String, String>): Long = runCatching {
        val b = okhttp3.Request.Builder().url(url).head()
        headers.forEach { (k, v) -> runCatching { b.header(k, v) } }
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1 else -1 }
    }.getOrDefault(-1)

    private fun lengthOf(sec: Double): String {
        val m = (sec / 60).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m > 0) "$m min" else "${sec.toInt()} s"
    }

    private fun replace(t0: BTab, url: String, rows: List<Sniffed>) = main.post {
        // Gone meanwhile (another page opened, the list cleared): nothing is put back.
        val at = t0.found.indexOfFirst { it.url == url }
        if (at < 0) return@post
        t0.found.removeAll { it.url == url || it.url in t0.children }
        t0.found.addAll(at.coerceAtMost(t0.found.size), rows)
    }

    /**
     * A stream read as 1DM reads it: a master playlist becomes one row per quality ("1080p · HLS", its size from
     * bitrate × length) and the quality playlists it names aren't listed again; a single quality gets its length.
     */
    private fun describeStream(t0: BTab, s: Sniffed) = kotlin.concurrent.thread(name = "browser-hls") {
        // Whatever a site answers, a look-up never takes the app down: it just leaves the row as it was.
        runCatching { describeStreamNow(t0, s) }
    }

    private fun describeStreamNow(t0: BTab, s: Sniffed) {
        val headers = headersFor(s, t0)
        val text = fetchText(s.url, headers) ?: return
        val durationOf = { body: String -> Regex("""#EXTINF:\s*([\d.]+)""").findAll(body).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 } }
        if (!isMasterPlaylist(text)) {
            val sec = durationOf(text)
            if (sec > 0) replace(t0, s.url, listOf(s.copy(detail = "One quality · " + lengthOf(sec))))
            return
        }
        val master = parseMaster(text, s.url)
        val variants = master.variants.sortedByDescending { it.height ?: (it.bandwidth / 1000).toInt() }
        // Sound in a track of its own: a quality's playlist alone is picture only. Said on its row; "Grab This
        // Page's Video" (yt-dlp) joins the two.
        val pictureOnly = master.separateAudio.isNotEmpty()
        if (variants.isEmpty()) return
        t0.children.addAll(variants.map { it.uri })
        // Every quality runs as long: one measured, each sized by its bitrate.
        val sec = fetchText(variants.last().uri, headers)?.let(durationOf) ?: 0.0
        val rows = listOf(s.copy(kind = "Best Quality · HLS", detail = "Picks the best when it downloads" + if (sec > 0) " · " + lengthOf(sec) else "")) +
            variants.map { v ->
                val label = v.height?.let { "${it}p" } ?: "${v.bandwidth / 1000} kbps"
                val size = if (sec > 0 && v.bandwidth > 0) "~" + com.torrentviewer.core.bytes((v.bandwidth * sec / 8).toLong()) else null
                Sniffed(v.uri, "$label · HLS", s.page, s.title, s.headers, listOfNotNull(if (sec > 0) lengthOf(sec) else null, size, if (pictureOnly) "picture only — use Grab for sound" else null).joinToString(" · "))
            }
        replace(t0, s.url, rows)
    }

    /** A video file's size; a small one is said to be a preview clip (a thumbnail's hover video), not the film. */
    private fun describeFile(t0: BTab, s: Sniffed) = kotlin.concurrent.thread(name = "browser-size") {
        runCatching {
            val size = sizeOf(s.url, headersFor(s, t0))
            if (size > 0) {
                val detail = if (size < MIN_VIDEO_BYTES) "Preview clip · " + com.torrentviewer.core.bytes(size) else com.torrentviewer.core.bytes(size)
                replace(t0, s.url, listOf(s.copy(detail = detail)))
            }
        }
    }

    /** The headers a download from this page needs: where it came from, the browser's identity, its cookies. */
    fun headersFor(s: Sniffed, t0: BTab = tab): Map<String, String> {
        val h = linkedMapOf<String, String>()
        if (s.page.isNotBlank()) h["Referer"] = s.page
        t0.ua.takeIf { it.isNotBlank() }?.let { h["User-Agent"] = it }
        // What the page itself sent wins: an embedded player checks its own Referer and Origin.
        s.headers.forEach { (k, v) -> if (k.lowercase() !in setOf("range", "accept-encoding", "host")) h[k] = v }
        cookiesOf(t0).getCookie(s.url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    /** A find, or a link to a file, offered for download. */
    fun offer(s: Sniffed, t0: BTab = tab) {
        offer = Offer(s.url, s.title.ifBlank { s.url.substringBefore('?').substringAfterLast('/') }, headersFor(s, t0), direct = true, kind = s.kind)
    }

    /** The page itself offered to yt-dlp ("Grab this page's video"). */
    fun offerPage() {
        val t = tab
        if (t.url.isBlank()) return
        offer = Offer(t.url, t.title.ifBlank { t.url }, headersFor(Sniffed(t.url, "Page", t.url, t.title), t).filterKeys { it != "Referer" }, direct = false, kind = "Page")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context, t: BTab = tab): WebView {
        t.web?.let { return it }
        CookieManager.getInstance().setAcceptCookie(true)
        val w = WebView(context)
        // Incognito: the private profile, set before anything loads; nothing cached to disk.
        if (t.incognito) {
            if (trulyPrivate) runCatching {
                androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE)
                androidx.webkit.WebViewCompat.setProfile(w, PRIVATE)
            }
            w.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // Pop-ups are caught in a window of their own ([catchPopup]) rather than taking over this tab: with
            // windows off, Android loads a pop-up ad *in place of the page*, which is what blacked sites out.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        darken(w.settings)
        // The shield (ad slots stripped, ads skipped) before any of the page's own scripts, where the WebView allows.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, SHIELD_SCRIPT, setOf("*")) }
        }
        t.ua = w.settings.userAgentString.orEmpty()
        cookiesOf(t).setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            // Runs on a background thread for every request: it must never throw.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = try {
                val u = request.url
                if (blockAds && !request.isForMainFrame && blockedHost(u.host.orEmpty())) {
                    main.post { t.blocked++ }
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    sniff(t, u.toString(), request.requestHeaders.orEmpty())
                    null
                }
            } catch (e: Throwable) {
                null
            }

            // The page's renderer crashed or was killed for memory: rebuild this tab instead of closing the app.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                if (t.web === view) t.web = null
                t.loading = false
                main.post { rebuilt++ }
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // A link straight to a file (video, audio, archive, document): offered for download.
                val k = kindOf(u.toString())
                if (k != null && !k.startsWith("Stream")) {
                    val name = u.lastPathSegment?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    offer(Sniffed(u.toString(), k, t.url, name ?: t.title), t)
                    return true
                }
                if (u.scheme != "http" && u.scheme != "https") return true
                if (blockAds && blockedHost(u.host.orEmpty())) { t.blocked++; return true }
                if (stopJump(t, request)) { t.stopped = u.toString(); return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // Older WebViews without start-of-page scripts: the shield as early as they let it in.
                if (blockAds && !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching { view.evaluateJavascript(SHIELD_SCRIPT, null) }
                }
                scrolledUp++
                val u = if (url == "about:blank") "" else url
                if (u != t.url) { t.found.clear(); t.children.clear() }
                t.url = u
                t.loading = true
                t.blocked = 0
                t.stopped = null
                nav(t, view)
            }

            // Leftover ad boxes hidden as soon as the page shows, and again when it's done.
            override fun onPageCommitVisible(view: WebView, url: String) {
                if (blockAds) runCatching { view.evaluateJavascript(AdBlock.HIDE_SCRIPT, null) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (blockAds) runCatching { view.evaluateJavascript(AdBlock.HIDE_SCRIPT, null) }
                t.url = if (url == "about:blank") "" else url
                t.loading = false
                t.title = view.title.orEmpty()
                nav(t, view)
                // Incognito keeps no history.
                if (!t.incognito) visited(t.url, t.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = if (url == "about:blank") "" else url
                nav(t, view)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }

            // A page opening a window: caught here, never loaded over this page.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean =
                catchPopup(view, t, isUserGesture, resultMsg)
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title.orEmpty() }

            // Full screen for a page's video player: its view laid over the whole window, back out with Back.
            private var full: android.view.View? = null
            private var fullDone: CustomViewCallback? = null

            override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                val activity = w.context as? android.app.Activity ?: return callback.onCustomViewHidden()
                if (full != null) { callback.onCustomViewHidden(); return }
                full = view
                fullDone = callback
                runCatching {
                    (activity.window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                    view.setBackgroundColor(android.graphics.Color.BLACK)
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                }
                fullScreen = true
                exitFull = { onHideCustomView() }
            }

            override fun onHideCustomView() {
                val activity = w.context as? android.app.Activity
                full?.let { v -> runCatching { (activity?.window?.decorView as? ViewGroup)?.removeView(v) } }
                full = null
                runCatching { fullDone?.onCustomViewHidden() }
                fullDone = null
                activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                fullScreen = false
                exitFull = null
            }
        }
        // Any movement of the page, either way, sends the tab bar off; it comes back once the page rests ([touched]).
        w.setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) touched++ }
        // Any touch on the page sends the tab bar away (the owner's choice): a pop-up's buttons at the foot of the
        // screen (a site's own dialog, which scrolls inside itself) are never under it. It stays away until the page
        // is scrolled back up or another page opens.
        w.setOnTouchListener { _, e ->
            // Down, each move and the lift: the bar's rest timer starts over from the last of them.
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP -> touched++
            }
            false
        }
        w.addJavascriptInterface(BlobSaver(), "WatchHubBlob")
        // A file the site hands over: straight to the download sheet, with this page's headers.
        w.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            // A file the page made itself (Proton's VPN config, a site's "export"): read in the page and saved here.
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                saveMade(w, url, name, mime)
                return@setDownloadListener
            }
            val s = Sniffed(url, kindOf(name) ?: "File", t.url, name)
            offer = Offer(url, name, headersFor(s, t) + ("User-Agent" to ua), direct = true, kind = s.kind)
        }
        t.web = w
        if (t.url.isNotEmpty()) w.loadUrl(t.url)
        return w
    }

    /**
     * A window a page opens, read in an unseen WebView until its address is known, then: an ad or tracker host, a
     * betting or pop-under site, or any window opened without a tap (with pop-up blocking on) — dropped, the page
     * left as it was; a tapped link to a real page — opened in a new tab, as a desktop browser does.
     */
    private fun catchPopup(opener: WebView, t: BTab, tapped: Boolean, msg: android.os.Message): Boolean {
        if (blockPopups && !tapped) { t.blocked++; return false }
        val probe = WebView(opener.context)
        probe.webViewClient = object : WebViewClient() {
            private var done = false
            private fun decide(url: String) {
                if (done) return
                done = true
                val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
                val ad = url.startsWith("about:") || host.isEmpty() || (blockAds && blockedHost(host)) || BAD_JUMP.containsMatchIn(host)
                main.post {
                    runCatching { probe.stopLoading(); probe.destroy() }
                    if (ad) t.blocked++ else { newTab(); tab.url = url }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { decide(request.url.toString()); return true }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (url != "about:blank") decide(url) }
        }
        (msg.obj as? WebView.WebViewTransport)?.webView = probe
        msg.sendToTarget()
        // Nothing came (a window that stays blank): let go after a moment.
        main.postDelayed({ runCatching { probe.destroy() } }, 8_000)
        return true
    }

    fun newTab(incognito: Boolean = false) {
        tabs.add(BTab((tabs.maxOfOrNull { it.id } ?: 0) + 1, incognito))
        current = tabs.lastIndex
    }

    fun select(i: Int) { current = i.coerceIn(0, tabs.lastIndex) }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.web?.apply {
            if (t.incognito) { clearHistory(); clearCache(true) }
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        tabs.removeAt(i)
        if (t.incognito && tabs.none { it.incognito }) forgetIncognito()
        if (tabs.isEmpty()) tabs.add(BTab(1))
        current = when {
            i < current -> current - 1
            current > tabs.lastIndex -> tabs.lastIndex
            else -> current
        }
    }

    private fun nav(t: BTab, v: WebView) {
        t.canBack = v.canGoBack()
        t.canForward = v.canGoForward()
    }

    /** An address, or words to search for with the chosen [engine]. */
    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> (ENGINES.firstOrNull { it.first == engine } ?: ENGINES.first()).second + java.net.URLEncoder.encode(t, "UTF-8")
        }
        val w = tab.web
        if (w != null) w.loadUrl(target) else tab.url = target
    }

    /** Dark pages: Android's own darkening of light sites, with the app's dark theme. */
    @Suppress("DEPRECATION")
    private fun darken(s: android.webkit.WebSettings) {
        runCatching {
            val dark = darkPages && com.torrentviewer.core.Ink.isDark
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, dark)
            } else if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                androidx.webkit.WebSettingsCompat.setForceDark(s, if (dark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF)
            }
        }
    }

    fun back() = tab.web?.takeIf { it.canGoBack() }?.goBack()
    fun forward() = tab.web?.takeIf { it.canGoForward() }?.goForward()
    fun reload() = tab.web?.reload()

    /* ------------------------------------------------------------ bookmarks and history */

    private const val BOOKMARKS = "browserBookmarks"
    private const val HISTORY = "browserHistory"

    val bookmarks = mutableStateListOf<Place>()
    val history = mutableStateListOf<Place>()
    private var loaded = false

    /** Read once, the first time the Browser is shown. */
    fun load() {
        if (loaded) return
        loaded = true
        bookmarks.addAll(read(BOOKMARKS))
        history.addAll(read(HISTORY))
    }

    private fun read(key: String): List<Place> = runCatching {
        val a = JSONArray(Kv.global(key) ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Place(it.getString("url"), it.optString("title"), it.optLong("time")) }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Place>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("url", it.url).put("title", it.title).put("time", it.time)) }
        Kv.setGlobal(key, a.toString())
    }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank()) return
        if (!bookmarks.removeAll { it.url == url }) bookmarks.add(0, Place(url, title.ifBlank { url }, System.currentTimeMillis()))
        save(BOOKMARKS, bookmarks.toList())
    }

    fun removeBookmark(p: Place) {
        bookmarks.remove(p)
        save(BOOKMARKS, bookmarks.toList())
    }

    /** A page finished loading: to the top of history (the last 500 kept). */
    private fun visited(url: String, title: String) {
        if (url.isBlank()) return
        history.removeAll { it.url == url }
        history.add(0, Place(url, title.ifBlank { url }, System.currentTimeMillis()))
        while (history.size > 500) history.removeAt(history.lastIndex)
        save(HISTORY, history.toList())
    }

    fun removeVisit(p: Place) {
        history.remove(p)
        save(HISTORY, history.toList())
    }

    fun clearHistory() {
        history.clear()
        save(HISTORY, emptyList())
    }
}

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
)

/** One browser tab: its own page, history and finds. */
class BTab(val id: Long) {
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
            s.javaScriptCanOpenWindowsAutomatically = !blockPopups
            darken(s)
        }
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
        // Pieces of a stream aren't worth listing; the stream itself is.
        if (Regex("""(seg|chunk|frag|part)[-_]?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(url) && kind != "Stream (HLS)") return
        val s = Sniffed(url, kind, t0.url, t0.title, sent)
        main.post { if (t0.found.none { it.url == url }) t0.found.add(0, s) }
    }

    /** The headers a download from this page needs: where it came from, the browser's identity, its cookies. */
    fun headersFor(s: Sniffed, t0: BTab = tab): Map<String, String> {
        val h = linkedMapOf<String, String>()
        if (s.page.isNotBlank()) h["Referer"] = s.page
        t0.ua.takeIf { it.isNotBlank() }?.let { h["User-Agent"] = it }
        // What the page itself sent wins: an embedded player checks its own Referer and Origin.
        s.headers.forEach { (k, v) -> if (k.lowercase() !in setOf("range", "accept-encoding", "host")) h[k] = v }
        CookieManager.getInstance().getCookie(s.url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
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
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            // No pop-up windows: most are ads.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = !blockPopups
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        darken(w.settings)
        t.ua = w.settings.userAgentString.orEmpty()
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
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
                val u = if (url == "about:blank") "" else url
                if (u != t.url) t.found.clear()
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
                visited(t.url, t.title)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = if (url == "about:blank") "" else url
                nav(t, view)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }
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
        w.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 6) scrolledDown++ else if (y < oldY - 6) scrolledUp++
        }
        // A file the site hands over: straight to the download sheet, with this page's headers.
        w.setDownloadListener { url, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            val s = Sniffed(url, kindOf(name) ?: "File", t.url, name)
            offer = Offer(url, name, headersFor(s, t) + ("User-Agent" to ua), direct = true, kind = s.kind)
        }
        t.web = w
        if (t.url.isNotEmpty()) w.loadUrl(t.url)
        return w
    }

    fun newTab() {
        tabs.add(BTab((tabs.maxOfOrNull { it.id } ?: 0) + 1))
        current = tabs.lastIndex
    }

    fun select(i: Int) { current = i.coerceIn(0, tabs.lastIndex) }

    fun close(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        t.web?.apply { (parent as? ViewGroup)?.removeView(this); destroy() }
        tabs.removeAt(i)
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

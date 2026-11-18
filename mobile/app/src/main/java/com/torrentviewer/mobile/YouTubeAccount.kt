package com.torrentviewer.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.TubeAccount
import com.torrentviewer.core.TubeChannel
import com.torrentviewer.core.TubeVideo
import com.torrentviewer.core.Type
import com.torrentviewer.core.grain
import com.torrentviewer.core.parseAccountMenu
import com.torrentviewer.core.parseFlatChannels
import com.torrentviewer.core.parseFlatVideos
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import com.composables.icons.lucide.R as L

/**
 * The YouTube account signed in on this phone, as the Grabber app signs in (same owner): Google's own sign-in page in
 * a WebView, its cookies kept in the app's private storage in the cookies.txt yt-dlp reads. With it: the account's
 * name and picture (YouTube's account menu), the real Subscriptions feed and channel list (yt-dlp, signed in), and
 * downloads of videos YouTube shows only to a signed-in account. Nothing leaves the phone but YouTube's own requests.
 *
 * ponytail: the feed is read through yt-dlp, a few seconds per refresh, and breaks if YouTube reshapes its pages until
 * yt-dlp's nightly catches up (it updates itself daily).
 */
object YouTubeAccount {
    private const val ACCOUNT = "youtubeAccount"
    private val DOMAINS = listOf("youtube.com", "google.com")
    private lateinit var app: Context

    private val _account = MutableStateFlow<TubeAccount?>(null)
    /** Who is signed in; null signed out (or not read yet). */
    val account: StateFlow<TubeAccount?> = _account
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    /** Google's sign-in, coming back to YouTube's phone site. */
    const val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com/"

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _signedIn.value = file().let { it.exists() && it.length() > 0 }
        _account.value = Kv.global(ACCOUNT)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?.let { o -> o.optString("name").takeIf { it.isNotBlank() }?.let { TubeAccount(it, o.optString("handle").ifBlank { null }, o.optString("photo").ifBlank { null }) } }
    }

    private fun file() = File(app.filesDir, "signins/YouTube.txt")

    /** The cookies.txt for yt-dlp; null signed out. */
    fun cookiesFile(): File? = if (::app.isInitialized) file().takeIf { it.exists() && it.length() > 0 } else null

    /** Whether the WebView now holds a signed-in Google session (its SAPISID cookie). */
    fun sessionReady(): Boolean = CookieManager.getInstance().getCookie("https://www.youtube.com")?.contains("SAPISID=") == true

    /** Keeps the WebView's YouTube and Google cookies as cookies.txt. Whether there was a session to keep. */
    fun save(): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        if (!sessionReady()) return false
        val far = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = DOMAINS.flatMap { d ->
            val raw = cm.getCookie("https://www.$d") ?: cm.getCookie("https://$d") ?: return@flatMap emptyList()
            raw.split(';').mapNotNull { kv ->
                val k = kv.substringBefore('=').trim()
                val v = kv.substringAfter('=', "").trim()
                if (k.isEmpty()) null else ".$d\tTRUE\t/\tTRUE\t$far\t$k\t$v"
            }
        }
        if (lines.isEmpty()) return false
        file().apply { parentFile?.mkdirs() }.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
        _signedIn.value = true
        return true
    }

    fun signOut() {
        file().delete()
        val cm = CookieManager.getInstance()
        DOMAINS.forEach { d ->
            cm.getCookie("https://www.$d")?.split(';')?.forEach { kv ->
                val k = kv.substringBefore('=').trim()
                cm.setCookie("https://www.$d", "$k=; Max-Age=0; Domain=.$d; Path=/")
            }
        }
        cm.flush()
        _signedIn.value = false
        _account.value = null
        Kv.setGlobal(ACCOUNT, null)
    }

    /**
     * The account's name, @handle and picture, from YouTube's own account menu (InnerTube), signed with the session's
     * SAPISIDHASH as YouTube's web page signs it.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return@withContext
        val sapisid = Regex("""(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)""").find(cookies)?.groupValues?.get(1) ?: return@withContext
        val origin = "https://www.youtube.com"
        val now = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$now $sapisid $origin".toByteArray()).joinToString("") { "%02x".format(it) }
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20250101.00.00","hl":"en"}}}"""
        val request = okhttp3.Request.Builder().url("$origin/youtubei/v1/account/account_menu?prettyPrint=false")
            .post(body.toRequestBody(null))
            .header("Content-Type", "application/json").header("Cookie", cookies).header("Authorization", "SAPISIDHASH ${now}_$hash")
            .header("Origin", origin).header("X-Origin", origin).header("X-Goog-AuthUser", "0").build()
        val text = runCatching { okhttp3.OkHttpClient().newCall(request).execute().use { it.body?.string() } }.getOrNull() ?: return@withContext
        parseAccountMenu(text)?.let { a ->
            _account.value = a
            Kv.setGlobal(ACCOUNT, org.json.JSONObject().put("name", a.name).put("handle", a.handle).put("photo", a.photo).toString())
        }
    }

    /** yt-dlp's flat listing of [url], signed in. */
    private fun flat(url: String, most: Int): String {
        Media.ensure(app)
        val cookies = cookiesFile() ?: error("Not signed in to YouTube")
        val req = YoutubeDLRequest(url).addOption("--flat-playlist").addOption("-J").addOption("--no-update")
            .addOption("--playlist-end", most).addOption("--cookies", cookies.absolutePath)
        val r = YoutubeDL.getInstance().execute(req)
        // Failed (signed out by Google, a page yt-dlp can't read yet): said, not shown as an empty feed.
        if (r.exitCode != 0 || r.out.isBlank()) throw IllegalStateException(Media.cleanError(r.err.ifBlank { "YouTube gave nothing back" }))
        return r.out
    }

    private val _problem = MutableStateFlow<String?>(null)
    /** Why the account's own feed couldn't be read last time; null when it was. Tube's Home says it. */
    val problem: StateFlow<String?> = _problem

    /** [block], with its failure kept in [problem] (and cleared on success). */
    suspend fun <T> noted(block: suspend () -> T): T = runCatching { block() }.onSuccess { _problem.value = null }
        .onFailure { _problem.value = "Your YouTube account's feed didn't load: ${it.message ?: "unknown error"}" }.getOrThrow()

    /** YouTube's own Home for the account: its recommendations. */
    suspend fun recommended(): List<TubeVideo> = withContext(Dispatchers.IO) { parseFlatVideos(flat(":ytrec", 60)) }

    /** The account's watch history on YouTube, newest first. */
    suspend fun watchHistory(): List<TubeVideo> = withContext(Dispatchers.IO) { parseFlatVideos(flat(":ythistory", 100)) }

    /** The signed-in Subscriptions feed, newest first, as YouTube shows it. */
    suspend fun feed(): List<TubeVideo> = withContext(Dispatchers.IO) { parseFlatVideos(flat(":ytsubs", 60)) }

    /** The channels the account subscribes to. */
    suspend fun channels(): List<TubeChannel> = withContext(Dispatchers.IO) { parseFlatChannels(flat("https://www.youtube.com/feed/channels", 500)) }

    /** After signing in: who it is, and its subscriptions added to Tube's. How many channels were new. */
    suspend fun syncSubscriptions(): Int {
        val found = runCatching { channels() }.getOrDefault(emptyList())
        return withContext(Dispatchers.Main) { Tube.importSubscriptions(found) }
    }
}

/**
 * Google's sign-in page, full screen; it closes by itself once the session is there (or with ✕). Google refuses
 * sign-in inside an app's WebView by its "wv" mark: the page is given Chrome's own identity instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignIn(onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    fun finish(ok: Boolean) {
        if (done) return
        done = true
        if (ok) scope.launch {
            YouTubeAccount.refresh()
            YouTubeAccount.syncSubscriptions()
        }
        onDone(ok)
    }
    // Watches for the session: signed in, it's kept and the page closes.
    LaunchedEffect(Unit) {
        while (!done) {
            delay(1500)
            if (YouTubeAccount.sessionReady() && YouTubeAccount.save()) finish(true)
        }
    }
    Dialog({ finish(YouTubeAccount.save()) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { finish(YouTubeAccount.save()) }
        Column(Modifier.fillMaxSize().background(Ink.bg).grain()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tap({ finish(YouTubeAccount.save()) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(10.dp), tint = Ink.text, size = 22.dp) }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Sign In to YouTube", style = Type.title.copy(fontWeight = FontWeight.Bold))
                    Text("Google's own page. The session stays on this phone.", style = Type.meta.copy(color = Ink.faint))
                }
            }
            AndroidView(
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s"""), "")
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(YouTubeAccount.LOGIN)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

/**
 * Who is signed in to YouTube, as a clean card: the account's picture, name and @handle; a switch to sign out; Sync
 * to bring its subscriptions over again. Signed out: one button to sign in. On Tube's You page and in Settings.
 */
@Composable
fun YouTubeAccountCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { YouTubeAccount.init(context) }
    val signedIn by YouTubeAccount.signedIn.collectAsState()
    val account by YouTubeAccount.account.collectAsState()
    var signing by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(signedIn) { if (signedIn && account == null) YouTubeAccount.refresh() }
    Column(modifier.fillMaxWidth().frosted(RoundedCornerShape(16.dp)).padding(16.dp)) {
        if (!signedIn) {
            Text("YouTube", style = Type.title.copy(fontWeight = FontWeight.Bold))
            Text(
                "Sign in for your own subscriptions on Tube's Home, and to save videos YouTube shows only to signed-in accounts.",
                Modifier.padding(top = 4.dp, bottom = 14.dp), style = Type.meta.copy(color = Ink.faint),
            )
            Button("Sign In to YouTube", { signing = true }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_user, primary = true)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Ink.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Text((account?.name ?: "Y").take(1).uppercase(), style = Type.title.copy(color = Ink.accent, fontWeight = FontWeight.Bold))
                    account?.photo?.let { Artwork(it, account?.name ?: "", Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(account?.name ?: "Signed in", style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(account?.handle ?: "YouTube", style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                LabeledToggle("", true, { on -> if (!on) YouTubeAccount.signOut() })
            }
            Button("Sync Subscriptions", {
                said = "Reading your subscriptions…"
                scope.launch {
                    val n = YouTubeAccount.syncSubscriptions()
                    said = if (n == 0) "Your subscriptions are all here." else "Added $n channels."
                }
            }, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_refresh_cw, compact = true)
            said?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta) }
        }
    }
    if (signing) YouTubeSignIn { ok ->
        signing = false
        said = if (ok) "Signed in. Bringing your subscriptions over…" else null
    }
}

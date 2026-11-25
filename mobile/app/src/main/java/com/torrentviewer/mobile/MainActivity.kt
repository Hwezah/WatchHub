package com.torrentviewer.mobile

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.TorrentStream
import com.torrentviewer.core.Watch
import com.torrentviewer.core.preferHighRefreshRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The engine and the session outlive the activity — rotation recreates nothing, but a config change still might. */
object Services {
    lateinit var engine: TorrentStream
        private set
    lateinit var watch: Watch
        private set

    fun init(context: Context) {
        // First, whoever started the app (the activity, or Android restarting the download service): its errors are kept.
        CrashGuard.install(context.applicationContext)
        if (::engine.isInitialized) return
        Kv.init(context)
        SettingsStore.load()
        com.torrentviewer.core.AiSearch.refresh()
        Profiles.load()
        com.torrentviewer.core.Simkl.clientId = BuildConfig.SIMKL_CLIENT_ID
        com.torrentviewer.core.Mdblist.clientId = BuildConfig.MDBLIST_CLIENT_ID
        com.torrentviewer.core.Tmdb.key = BuildConfig.TMDB_KEY
        // 6882: the TV (6881) and the Windows app (51413) may share the router.
        // lan: the stream is also served to the network, for a Cast receiver to fetch.
        engine = TorrentStream(context.applicationContext, port = 6882, lan = true)
        // Readable over adb (Android/data/<app>/files/engine.log): this phone keeps app logs out of logcat.
        engine.logFile = context.getExternalFilesDir(null)?.let { java.io.File(it, "engine.log") }
        // DHT bootstrapped while the viewer browses, not after they press Play.
        AppScope.launch(Dispatchers.IO) { engine.warmUp() }
        watch = Watch(engine)
        Downloads.init(context)
        // CloudStream plugins: loaded in the background, so they're ready by the time a title opens.
        com.torrentviewer.mobile.cs.CsPlugins.init(context)
        // NewPipe, for trailers in the app's own player and for plugins' YouTube links, as CloudStream's app sets it up.
        Trailers.init()
        // The Browser's big blocklist, read in the background; the VPN back on if it was left on.
        AdBlock.init(context)
        runCatching { Vpn.init(context) }
        // Addons read afresh, as Stremio and Nuvio do on launch: rows added on Xperience's or AIOMetadata's own site
        // since the addon was added here then show without adding it again.
        AppScope.launch(Dispatchers.IO) { runCatching { com.torrentviewer.core.Addons.refresh() } }
    }
}

/** Picture-in-picture, between the player (which knows whether a film is playing) and the activity. */
object Pip {
    var active by mutableStateOf(false)
    /** The window's params while a film plays, else null: Home then just leaves. */
    var params: (() -> PictureInPictureParams?)? = null

    fun supported(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

/**
 * An AppCompatActivity: the Cast button's device chooser is a dialog fragment, and CloudStream plugins open
 * their settings by casting the activity to one (getSupportFragmentManager), as CloudStream's own is.
 */
class MainActivity : AppCompatActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(applicationContext)
        // What an update that went through left behind (55 MB), unless one is downloading or waiting to be installed.
        if (com.torrentviewer.core.Updates.progress.value == null && com.torrentviewer.core.Updates.ready.value == null) runCatching { java.io.File(filesDir, "update.apk").delete() }
        // Pictures kept in memory: an eighth of the app's share rather than Coil's quarter — posters reload from disk in a blink.
        runCatching {
            coil3.SingletonImageLoader.setSafe { ctx ->
                coil3.ImageLoader.Builder(ctx).memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(ctx, 0.12).build() }.build()
            }
        }
        Services.init(applicationContext)
        com.torrentviewer.mobile.cs.CsPlugins.attach(this)
        // Plugins' settings sheets: a little space between their stacked option rows.
        supportFragmentManager.registerFragmentLifecycleCallbacks(com.torrentviewer.mobile.cs.PluginSheetSpacing, true)
        // The TV's red glares at arm's length on a phone screen: a deeper one — or the shade picked in Settings → Appearance.
        Ink.accent = Color(com.torrentviewer.core.accentShade(SettingsStore.value.value.accent))
        // Every spinner in the app: the green ring the sources' Reload Links! has.
        Ink.greenSpinner = true
        // The page before the first frame, so a light theme never opens on a flash of dark.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyTheme(darkFor(SettingsStore.value.value.themeMode, systemDark))
        incoming.value = intent?.let(::uriFrom)
        // Known before the first frame, so an upright phone doesn't open with the wider margins.
        resources.configuration.let { Room.tight = it.orientation == Configuration.ORIENTATION_PORTRAIT && it.smallestScreenWidthDp < 600 }
        setContent {
            // Round avatars all through the phone app (the owner's choice); the TV keeps its squares.
            androidx.compose.runtime.CompositionLocalProvider(com.torrentviewer.core.LocalAvatarShape provides { androidx.compose.foundation.shape.CircleShape }) {
            androidx.compose.foundation.layout.Box {
                App(Services.watch, incoming)
                com.torrentviewer.core.Splash(skip = incoming.value != null)
            }
            }
        }
        preferHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = uriFrom(intent)
    }

    /**
     * What an intent is pointing at: a VIEW's own data (a link opened, a torrent), or — when another app Shared to
     * WatchHub (Instagram, TikTok, the system share sheet) — the first web or magnet link in its EXTRA_TEXT. The share
     * text is often "Look at this https://…", so the link is picked out of it rather than taken whole.
     */
    private fun uriFrom(intent: Intent): Uri? {
        intent.data?.let { return it }
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val link = Regex("""(https?://|magnet:)\S+""", RegexOption.IGNORE_CASE).find(text)?.value ?: return null
        return runCatching { Uri.parse(link) }.getOrNull()
    }

    override fun onResume() {
        super.onResume()
        Downloads.visible = true
        com.torrentviewer.mobile.cs.CsPlugins.attach(this)
        // Back in front: a build pushed while away is offered (at most one check every 5 minutes).
        com.torrentviewer.core.Updates.checkPhoneBuild(BuildConfig.VERSION_CODE, "WatchHub-phone-")
        preferHighRefreshRate()
        com.torrentviewer.core.refreshWatched()
        grabClipboardLink()
    }

    /** Set once a copied link is offered, so returning to WatchHub doesn't offer the same one again and again. */
    private var lastClip: String? = null

    /**
     * Back in front with a fresh link on the clipboard (copied in another app, from a site yt-dlp knows): its grab sheet
     * is offered straight away, as 1DM does. Android only lets an app read the clipboard while it's in front, so this is
     * the moment to look — not while WatchHub was in the background. Only a site's own link, and each one only once.
     */
    private fun grabClipboardLink() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val text = runCatching { cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        val link = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value ?: return
        if (link == lastClip || PageGrab.page != null || com.torrentviewer.core.Kids.on || !Media.looksLikeSite(link)) return
        lastClip = link
        PageGrab.grab(link)
    }

    override fun onPause() {
        super.onPause()
        Downloads.visible = false
    }

    /** Home while a film plays shrinks it to a window. Android 12+ does this itself from the params' auto-enter. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Pip.supported(this)) {
            Pip.params?.invoke()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.active = isInPictureInPictureMode
    }

    /** The phone is short of memory, or the app left the screen: the pictures held in memory go (they're on disk). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    override fun onDestroy() {
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) com.torrentviewer.mobile.cs.CsPlugins.attach(null)
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }
}

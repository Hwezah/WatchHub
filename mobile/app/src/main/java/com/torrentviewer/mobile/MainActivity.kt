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
        if (::engine.isInitialized) return
        Kv.init(context)
        SettingsStore.load()
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
        Services.init(applicationContext)
        com.torrentviewer.mobile.cs.CsPlugins.attach(this)
        // Plugins' settings sheets: a little space between their stacked option rows.
        supportFragmentManager.registerFragmentLifecycleCallbacks(com.torrentviewer.mobile.cs.PluginSheetSpacing, true)
        // The TV's red glares at arm's length on a phone screen: a deeper one.
        Ink.accent = Color(0xFFB81D24)
        // Every spinner in the app: the green ring the sources' Reload Fresh Links! has.
        Ink.greenSpinner = true
        // The page before the first frame, so a light theme never opens on a flash of dark.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyTheme(darkFor(SettingsStore.value.value.themeMode, systemDark))
        incoming.value = intent?.data
        // Known before the first frame, so an upright phone doesn't open with the wider margins.
        resources.configuration.let { Room.tight = it.orientation == Configuration.ORIENTATION_PORTRAIT && it.smallestScreenWidthDp < 600 }
        setContent {
            androidx.compose.foundation.layout.Box {
                App(Services.watch, incoming)
                com.torrentviewer.core.Splash(skip = incoming.value != null)
            }
        }
        preferHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.data
    }

    override fun onResume() {
        super.onResume()
        com.torrentviewer.mobile.cs.CsPlugins.attach(this)
        // Back in front: a build pushed while away is offered (at most one check every 5 minutes).
        com.torrentviewer.core.Updates.checkPhoneBuild(BuildConfig.VERSION_CODE, "WatchHub-phone-")
        preferHighRefreshRate()
        com.torrentviewer.core.refreshWatched()
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

    override fun onDestroy() {
        if (com.lagradost.cloudstream3.CommonActivity.activity === this) com.torrentviewer.mobile.cs.CsPlugins.attach(null)
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }
}

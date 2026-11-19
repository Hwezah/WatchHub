package com.torrentviewer.mobile

import com.torrentviewer.core.libraryFolders
import com.torrentviewer.core.GRABBER_ROOT
import com.torrentviewer.core.GRABBER_FOLDERS
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.torrentviewer.core.Hit
import com.torrentviewer.core.History
import com.torrentviewer.core.Kv
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.TorrentFile
import com.torrentviewer.core.objects
import com.torrentviewer.core.parseEpisode
import com.torrentviewer.core.pickFile
import com.torrentviewer.core.seasonsIn
import com.torrentviewer.core.speed
import com.torrentviewer.core.str
import com.torrentviewer.core.withTrackers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.net.URL

/**
 * One download as stored — the Expo build's "torrent-active-download" queue and field
 * names, so a transfer in flight carries over the upgrade.
 */
data class DownloadJob(
    val uri: String,
    /** The release's name. */
    val title: String,
    /** "The Matrix" or "Show · S01E02": the label, and (before the dot) the folder. */
    val titleName: String? = null,
    val poster: String? = null,
    val imdb: String? = null,
    val kind: String? = null,
    /** Episodes wanted out of a pack ("S01E02"); none takes the main video. */
    val tags: List<String> = emptyList(),
    /** A whole season out of a pack. */
    val season: Int? = null,
    /** Chosen file indexes, once the metadata has said which they are. */
    val files: List<Int> = emptyList(),
    /** Indexes already copied out to Movies/Watchly. */
    val saved: List<Int> = emptyList(),
    val paused: Boolean = false,
    /** Resume pressed while waiting for Wi-Fi: carry on over mobile data. */
    val mobileData: Boolean = false,
    /** This download's own order; null follows the default in Settings. */
    val sequential: Boolean? = null,
    /** Episodes of a pack unticked by hand: kept out when the files are chosen again on restart. */
    val skipped: List<Int> = emptyList(),
    /** A direct link rather than a torrent: saved by [LinkFetch], with these headers on every request. */
    val link: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** The link is an HLS playlist: its segments are joined into one file. */
    val playlist: Boolean = false,
    /** The source's resolution ("720p"), to pick the playlist's matching variant. */
    val quality: String? = null,
    /** Where the link came from — the addon or plugin, its link name, the episode — to ask for a fresh one when it expires. */
    val source: String? = null,
    val linkName: String? = null,
    val epSeason: Int? = null,
    val epNumber: Int? = null,
    /** Fresh links fetched so far for this download. */
    val refreshes: Int = 0,
    /** When the link was found (ms). Plugin links carry tokens that expire: an old one is renewed before it starts. 0: unknown. */
    val fetchedAt: Long = 0,
    /** The release's size when it was picked (0 unknown): a fresh link for it should be about the same, not a far bigger file. */
    val size: Long = 0,
    /** Sources turned down by hand ("Different Source"): their addresses and [rejectKey]s, never picked again for this download. */
    val rejected: List<String> = emptyList(),
) {
    val label get() = titleName ?: title

    /** Up to three tags for its card ("720p", "MKV", "x265"), from its link's name and address. */
    val tags3 get() = downloadTags(quality, listOfNotNull(linkName, title).joinToString(" "), uri, playlist)

    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("titleName", titleName).put("poster", poster)
        .put("imdb", imdb).put("kind", kind).put("tags", JSONArray(tags)).put("season", season)
        .put("fileIndices", JSONArray(files)).put("savedIndices", JSONArray(saved)).put("paused", paused).put("mobileData", mobileData).put("sequential", sequential)
        .put("skipped", JSONArray(skipped))
        .put("link", link).put("playlist", playlist).put("quality", quality)
        .put("source", source).put("linkName", linkName).put("epSeason", epSeason).put("epNumber", epNumber).put("refreshes", refreshes).put("fetchedAt", fetchedAt).put("size", size)
        .put("rejected", JSONArray(rejected))
        .put("headers", JSONObject().also { o -> headers.forEach { (k, v) -> o.put(k, v) } })

    companion object {
        fun from(o: JSONObject): DownloadJob {
            fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty().filter { it >= 0 }
            val auto = o.opt("auto") as? String
            return DownloadJob(
                uri = o.getString("uri"),
                title = o.optString("title").ifBlank { "Download" },
                titleName = o.str("titleName"),
                poster = o.str("poster"),
                imdb = o.str("imdb"),
                kind = o.str("kind"),
                tags = (o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() + listOfNotNull(auto)).distinct(),
                season = if (o.has("season") && !o.isNull("season")) o.optInt("season") else null,
                files = ints("fileIndices"),
                saved = ints("savedIndices"),
                skipped = ints("skipped"),
                paused = o.optBoolean("paused"),
                mobileData = o.optBoolean("mobileData"),
                sequential = if (o.has("sequential") && !o.isNull("sequential")) o.optBoolean("sequential") else null,
                link = o.optBoolean("link"),
                headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty(),
                playlist = o.optBoolean("playlist"),
                quality = o.str("quality"),
                source = o.str("source"),
                linkName = o.str("linkName"),
                epSeason = if (o.has("epSeason") && !o.isNull("epSeason")) o.optInt("epSeason") else null,
                epNumber = if (o.has("epNumber") && !o.isNull("epNumber")) o.optInt("epNumber") else null,
                refreshes = o.optInt("refreshes"),
                fetchedAt = o.optLong("fetchedAt"),
                size = o.optLong("size"),
                rejected = o.optJSONArray("rejected")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            )
        }
    }
}

enum class DlState(val label: String) { RESOLVING("Finding peers"), DOWNLOADING("Downloading"), PAUSED("Paused"), WIFI("Waiting for Wi-Fi"), FAILED("Failed") }

/** One file of a download; in a pack, [wanted] false is an episode the viewer skipped. */
data class DlFile(val index: Int, val name: String, val size: Long, val done: Long, val saved: Boolean, val wanted: Boolean = true)

data class LiveDownload(
    val job: DownloadJob,
    val state: DlState,
    val files: List<DlFile> = emptyList(),
    val speed: Int = 0,
    val peers: Int = 0,
    val error: String? = null,
) {
    // Skipped episodes count for nothing.
    val total get() = files.filter { it.wanted }.sumOf { it.size }
    val done get() = files.filter { it.wanted }.sumOf { if (it.saved) it.size else it.done }
    val ratio get() = if (total > 0) done.toFloat() / total else 0f
}

/** A file kept in Movies/Watchly: the Expo build's "torrent-downloads". */
data class Finished(val name: String, val size: Long, val at: Long, val path: String, val poster: String?, val titleName: String?, val imdb: String?, val kind: String?) {
    val file get() = File(path.removePrefix("file://"))
    val label get() = titleName ?: name
}

/**
 * Downloads: keep a file rather than stream it. Its own libtorrent session, beside the
 * streaming engine's, so a download never fights the player's piece deadlines; finished
 * videos are copied into Movies/Watchly and the torrent's data dropped.
 *
 * ponytail: a second session on a random port, unforwarded unless UPnP opens it — a
 * download runs on outgoing peers alone behind a strict router. One shared session is
 * the upgrade if that matters.
 */
object Downloads {
    private const val TAG = "Downloads"
    private const val QUEUE = "torrent-active-download"
    private const val FINISHED = "torrent-downloads"
    private const val METADATA_SECONDS = 120
    private const val BOOTSTRAP = "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.libtorrent.org:25401"
    private val VIDEO = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg")

    private val _live = MutableStateFlow<List<LiveDownload>>(emptyList())
    val live: StateFlow<List<LiveDownload>> = _live
    private val _finished = MutableStateFlow<List<Finished>>(emptyList())
    val finished: StateFlow<List<Finished>> = _finished

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: SessionManager? = null
    private val handles = HashMap<String, TorrentHandle>()
    /** Direct-link downloads running, by link. */
    private val links = HashMap<String, kotlinx.coroutines.Job>()
    private val errors = HashMap<String, String>()
    private var poller: Job? = null
    private var wake: PowerManager.WakeLock? = null
    /** Wi-Fi drops to power save with the screen off — a few hundred kB/s; this keeps it at full speed. */
    private var wifiLock: WifiManager.WifiLock? = null
    private val sessionFile get() = File(app.filesDir, "downloads.session")
    private var lastNote = ""

    /* ------------------------------------------------------------ settings */

    // Booleans in the same preferences file and under the same names as the Expo build's native side.
    private fun prefs() = app.getSharedPreferences("watchly", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs().getBoolean("wifiOnly", false)
        set(v) = prefs().edit().putBoolean("wifiOnly", v).apply()

    var sdCard: Boolean
        get() = prefs().getBoolean("sdCard", false)
        set(v) = prefs().edit().putBoolean("sdCard", v).apply()

    /**
     * In order: each file fills from its start, so it can be watched while it downloads — slower,
     * since every peer is asked for the same next pieces. Off (the default), libtorrent asks for
     * the rarest pieces first, which is how a swarm gives its full speed. Episodes of a pack
     * still come one after another either way (file priorities).
     */
    /**
     * Connections a direct link is fetched over at once, each a range of the file — as 1DM's "threads".
     * More is faster on most hosts; a host that refuses (429, 503) is waited out part by part.
     */
    var connections: Int
        // A new key: everyone moves to 64 (the old default, 32, was saved by anyone who opened the setting).
        get() = prefs().getInt("connections64", 64)
        set(v) = prefs().edit().putInt("connections64", v).apply()

    var sequential: Boolean
        get() = prefs().getBoolean("sequential", false)
        set(v) {
            prefs().edit().putBoolean("sequential", v).apply()
            // Downloads given their own order keep it.
            synchronized(lock) { handles.toList() }.forEach { (uri, h) -> applyOrder(uri, h) }
        }

    /** One download in order or fastest, whatever the default. */
    fun setOrder(uri: String, inOrder: Boolean) {
        update(uri) { it.copy(job = it.job.copy(sequential = inOrder)) }
        synchronized(lock) { handles[uri] }?.let { applyOrder(uri, it) }
    }

    fun inOrder(job: DownloadJob) = job.sequential ?: sequential

    private fun applyOrder(uri: String, h: TorrentHandle) {
        val on = _live.value.find { it.job.uri == uri }?.job?.let(::inOrder) ?: sequential
        runCatching { if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }

    /** Battery optimisation off for the app: some phones (vivo/iQOO, Xiaomi, Oppo) otherwise kill or starve a download with the screen off. */
    fun unrestricted(context: Context = app): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /** The system's "let this app run in the background" prompt. */
    fun askUnrestricted(context: Context = app) {
        if (unrestricted(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** The app's own folder on a mounted card: a real path libtorrent can write, needing no permission — gone on uninstall. */
    fun cardRoot(type: String?): File? = app.getExternalFilesDirs(type).drop(1).firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    val hasCard get() = cardRoot(null) != null

    /** Moving storage under a running transfer would strand its pieces. */
    val busy get() = synchronized(lock) { handles.isNotEmpty() } || _live.value.isNotEmpty()

    private fun pieceDir() = File(if (sdCard) cardRoot(null) ?: app.getExternalFilesDir(null) ?: app.filesDir else app.getExternalFilesDir(null) ?: app.filesDir, "downloads").apply { mkdirs() }

    /**
     * The public Download/Grabber! folder, shared with the Grabber app and visible in the phone's Files app (the owner's
     * layout): Movies, Shows and the rest under it ([GRABBER_FOLDERS]). The card's, if chosen; else the app's own.
     */
    fun keepDir(): File {
        val root = (if (sdCard) cardRoot(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, GRABBER_ROOT) } else null)
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), GRABBER_ROOT)
                .takeIf { runCatching { it.mkdirs(); it.exists() && it.canWrite() }.getOrDefault(false) }
            ?: File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir, GRABBER_ROOT)
        root.mkdirs()
        GRABBER_FOLDERS.forEach { runCatching { File(root, it).mkdirs() } }
        return root
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().ifBlank { "Download" }

    /** Grabber!/Movies/<film>/file.mkv, or Grabber!/Shows/<show>/Season NN/file.mkv ([libraryFolders]). */
    private fun destOf(job: DownloadJob, torrentName: String, path: String): File {
        val file = safe(path.replace('\\', '/').split('/').last { it.isNotBlank() })
        // From the Browser: Grabber!/Video, Audio, Documents or Other, by what the file is, as the Grabber app sorts.
        if (job.kind == WEB_KIND) return File(File(keepDir(), webFolder(file)), file)
        val folders = libraryFolders(job.titleName ?: torrentName, file, job.kind, job.season?.takeIf { it >= 0 })
        return File(folders.fold(keepDir()) { dir, part -> File(dir, part) }, file)
    }

    /**
     * Once: what older builds saved under Movies/Watchly moved into the Grabber! folders (the owner's choice), the
     * list and Continue Watching following each file. A file that can't be moved stays where it is, still listed.
     * Called once a profile is open, so that profile's Continue Watching is the one updated.
     */
    fun moveOldDownloads() = scope.launch { runCatching { moveOld() } }

    /** [moveOldDownloads]'s work, off the main thread. */
    private fun moveOld() {
        // Before the list is read (a profile opened ahead of init): nothing to go on yet, asked again next launch.
        if (!::app.isInitialized || Kv.global(MOVED_KEY) != null) return
        val root = keepDir()
        val moved = _finished.value.map { f ->
            // Old Movies/Watchly files, and films an earlier build put in a folder of their own inside Movies.
            val inMovieFolder = f.file.parentFile?.parentFile?.let { it.name == "Movies" && it.parentFile?.path == root.path } == true
            if ("/Watchly/" !in f.file.path && !inMovieFolder) return@map f
            runCatching {
                val folders = libraryFolders(f.titleName ?: f.name, f.file.name, f.kind, null)
                if (folders.firstOrNull() != "Movies" && inMovieFolder) return@runCatching f
                val dest = File(folders.fold(root) { dir, part -> File(dir, part) }, f.file.name)
                if (dest.path == f.file.path) return@runCatching f
                dest.parentFile?.mkdirs()
                if (!f.file.renameTo(dest)) {
                    f.file.copyTo(dest, overwrite = true)
                    f.file.delete()
                }
                History.moved(f.path, dest.path)
                f.copy(path = dest.path)
            }.onFailure { Log.w(TAG, "move ${f.name}: ${it.message}") }.getOrDefault(f)
        }
        writeFinished(moved)
        // Folders left empty in Movies/Watchly go too.
        runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Watchly").walkBottomUp()
                .forEach { if (it.isDirectory && it.listFiles().isNullOrEmpty()) it.delete() }
        }
        // A film's emptied folder inside Movies goes too (Movies itself stays).
        runCatching { File(root, "Movies").listFiles().orEmpty().filter { it.isDirectory && it.listFiles().isNullOrEmpty() }.forEach { it.delete() } }
        Kv.setGlobal(MOVED_KEY, "1")
    }

    /** Set once the old Movies/Watchly downloads were moved into Grabber!. */
    // A new key: runs once more, so films an earlier build put in folders of their own move up into Movies.
    private const val MOVED_KEY = "downloads-layout-movies-flat"

    /* --------------------------------------------------------------- store */

    private fun readQueue(): List<DownloadJob> = runCatching {
        val raw = Kv.global(QUEUE) ?: return emptyList()
        // The Expo build once kept a single job, not a list.
        val any = org.json.JSONTokener(raw).nextValue()
        when (any) {
            is JSONArray -> any.objects().map(DownloadJob::from)
            is JSONObject -> listOf(DownloadJob.from(any))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun writeQueue(jobs: List<DownloadJob>) {
        Kv.setGlobal(QUEUE, if (jobs.isEmpty()) null else JSONArray(jobs.map { it.toJson() }).toString())
        // The hourly background check runs while anything is left to download, and only then.
        if (jobs.any { !it.paused }) BackgroundResume.watch(app) else BackgroundResume.stop(app)
    }

    private fun jobs() = _live.value.map { it.job }

    private fun update(uri: String, change: (LiveDownload) -> LiveDownload) = synchronized(lock) {
        _live.value = _live.value.map { if (it.job.uri == uri) change(it) else it }
        writeQueue(jobs())
    }

    private fun readFinished(): List<Finished> = runCatching {
        JSONArray(Kv.global(FINISHED) ?: "[]").objects().mapNotNull { o ->
            val path = o.str("path") ?: return@mapNotNull null
            Finished(o.optString("name"), o.optLong("size"), o.optLong("at"), path, o.str("poster"), o.str("titleName"), o.str("imdb"), o.str("kind"))
        }
    }.getOrDefault(emptyList())

    private fun writeFinished(list: List<Finished>) {
        _finished.value = list
        Kv.setGlobal(FINISHED, JSONArray(list.map {
            JSONObject().put("name", it.name).put("size", it.size).put("at", it.at).put("path", it.path).put("poster", it.poster)
                .put("titleName", it.titleName).put("imdb", it.imdb).put("kind", it.kind)
        }).toString())
    }

    /* ----------------------------------------------------------------- API */

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Pages saved as videos by older builds (all a few hundred KB): deleted, and gone from the list.
        val (kept, junk) = readFinished().filter { it.file.exists() }.partition { it.file.length() >= MIN_VIDEO_BYTES }
        junk.forEach { runCatching { it.file.delete() } }
        if (junk.isNotEmpty()) writeFinished(kept) else _finished.value = kept
        val queue = readQueue()
        _live.value = queue.map { LiveDownload(it, if (it.paused) DlState.PAUSED else DlState.RESOLVING) }
        queue.filter { !(it.link && it.paused) }.forEach(::begin)
        if (queue.any { !it.paused }) BackgroundResume.watch(app)
        // The network back (Wi-Fi again, mobile data on): downloads that failed meanwhile carry on at once,
        // not at their next timed try.
        runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (_live.value.any { it.state == DlState.FAILED && !it.job.paused }) wake()
                    }
                }
            })
        }
    }

    /** Anything not paused left to download: the background wake-ups go on while this is so. */
    val unfinished get() = _live.value.any { !it.job.paused }

    /** The last [wake], so a burst of network changes doesn't ask the sources over and over. */
    @Volatile private var lastWake = 0L

    /**
     * Carries on every download that isn't paused and isn't running: a failed one tried again (a fresh link if
     * need be, what's saved kept), a link with nothing fetching it started. Called by the background job
     * ([ResumeWorker]) and when the network comes back. Torrents restart with the engine on their own.
     */
    fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 30_000) return
        lastWake = now
        _live.value.filter { !it.job.paused }.forEach { d ->
            when {
                d.state == DlState.FAILED -> retry(d.job.uri)
                d.job.link && synchronized(lock) { links[d.job.uri] } == null -> begin(d.job)
            }
        }
    }

    /** No network now: a background wake-up waits for it rather than a clock. */
    private fun offline(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.activeNetwork == null
    }.getOrDefault(false)

    /** A download waiting [minutes] for its next try: woken then even if Android closes the app meanwhile — or as soon as the network is back. */
    private fun wakeIn(minutes: Int) = BackgroundResume.soon(app, if (offline()) 0L else minutes.toLong())

    /**
     * Android's daily limit on background downloading (six hours, Android 15) reached: the service goes, the
     * downloads go on while the process lives, and the background job takes over in bursts after that.
     */
    fun onServiceTimeout() {
        refusedAt = System.currentTimeMillis()
        BackgroundResume.soon(app, 1)
    }

    /** Android wouldn't let the service into the foreground: as [onServiceTimeout]. */
    fun onServiceRefused() = onServiceTimeout()

    /**
     * When Android last refused or ended the foreground service: until the app is opened again (which resets Android's
     * daily limit), it isn't asked from the background — each ask it refused risked the app being closed.
     */
    @Volatile private var refusedAt = 0L

    /** An activity of the app is on screen: Android lets a foreground service start, and its daily limit starts over. */
    @Volatile var visible = false
        set(value) {
            field = value
            if (value && refusedAt > 0L) {
                refusedAt = 0L
                // Downloads carried on without the service meanwhile: back under its notification now the app is open.
                if (::app.isInitialized && running) startService()
            }
        }

    /** Another episode out of a torrent already downloading widens it rather than starting it twice. */
    fun add(job: DownloadJob) {
        val existing = _live.value.find { it.job.uri == job.uri }
        if (existing != null) {
            update(job.uri) { it.copy(job = it.job.copy(tags = (it.job.tags + job.tags).distinct(), season = it.job.season ?: job.season)) }
            scope.launch {
                val h = synchronized(lock) { handles[job.uri] } ?: return@launch
                // Asked for again from its page: an episode skipped earlier is wanted after all.
                runCatching { h.torrentFile() }.getOrNull()?.let { ti ->
                    val files = filesOf(ti)
                    val asked = (job.tags.mapNotNull { pickFile(files, it) } + seasonVideos(job, files)).toSet()
                    update(job.uri) { it.copy(job = it.job.copy(skipped = it.job.skipped - asked)) }
                }
                choose(job.uri, h)
            }
            return
        }
        synchronized(lock) {
            _live.value = _live.value + LiveDownload(job, if (job.paused) DlState.PAUSED else DlState.RESOLVING)
            writeQueue(jobs())
        }
        // Added paused (the season sheet's Add): waits in the list for its ▶, nothing fetched yet.
        if (job.paused && job.link) return
        // Asked once, on the first download: the viewer is right here, having just tapped it.
        if (!prefs().getBoolean("askedBattery", false)) {
            prefs().edit().putBoolean("askedBattery", true).apply()
            askUnrestricted()
        }
        begin(job)
    }

    fun pause(uri: String) {
        synchronized(lock) { handles[uri] }?.let { runCatching { it.pause() } }
        // A link stops where it is; its work folder keeps the segments written so far.
        synchronized(lock) { links.remove(uri) }?.cancel()
        update(uri) { it.copy(job = it.job.copy(paused = true), state = DlState.PAUSED, speed = 0) }
        settle()
    }

    /** Resume on a download held for Wi-Fi is the viewer choosing mobile data for it. */
    fun resume(uri: String) {
        update(uri) { it.copy(job = it.job.copy(paused = false, mobileData = it.job.mobileData || it.state == DlState.WIFI), state = DlState.DOWNLOADING) }
        val h = synchronized(lock) { handles[uri] }
        if (h != null) runCatching { h.resume() } else _live.value.find { it.job.uri == uri }?.let { begin(it.job) }
    }

    fun cancel(uri: String) {
        val h = synchronized(lock) {
            _live.value = _live.value.filter { it.job.uri != uri }
            writeQueue(jobs())
            handles.remove(uri)
        }
        if (h != null) scope.launch { runCatching { session?.remove(h, SessionHandle.DELETE_FILES) } }
        synchronized(lock) { links.remove(uri) }?.cancel()
        scope.launch { linkWork(uri).deleteRecursively() }
        settle()
    }

    fun delete(f: Finished) {
        runCatching { f.file.delete() }
        // Leaves no empty show folder behind.
        var dir = f.file.parentFile
        // Not the Grabber! folder itself or its Movies, Shows… folders: only a film's or show's own.
        val keep = GRABBER_FOLDERS.toSet() + GRABBER_ROOT + "Watchly"
        while (dir != null && dir.name !in keep && dir.listFiles().isNullOrEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
        History.drop(f.file.path, Services.engine)
        writeFinished(_finished.value.filter { it.path != f.path })
    }

    /* ---------------------------------------------------------- YouTube */

    /** A YouTube video downloaded from the Tube tab: saved to Grabber!/YouTube. */
    const val TUBE_KIND = "youtube"

    /** A YouTube video's sound alone: saved to Grabber!/Audio. */
    const val TUBE_AUDIO = "youtube-audio"

    /** [v] downloaded at [height] (720 when null), or its sound alone ([audio]). Asked again, it's the same download. */
    fun addTube(v: com.torrentviewer.core.TubeVideo, height: Int?, audio: Boolean) = add(
        DownloadJob(
            uri = v.url + if (audio) "#audio" else "#${height ?: 720}p", title = v.title, titleName = v.title, poster = v.thumb,
            kind = if (audio) TUBE_AUDIO else TUBE_KIND, link = true, quality = if (audio) "audio" else "${height ?: 720}p",
        ),
    )

    /**
     * [runLink] for YouTube: the streams read fresh (YouTube's links last hours, so one kept since yesterday is dead),
     * the picture-only MP4 and the M4A sound fetched by [LinkFetch] (resumable, many connections), then joined into one
     * MP4 by Android's own muxer — no re-encoding.
     *
     * ponytail: YouTube's MP4 tops out at 1080p (1440p and 4K are VP9/AV1 WebM, which would need a WebM muxer).
     */
    private suspend fun saveTube(job: DownloadJob) {
        val id = com.torrentviewer.core.youtubeId(job.uri) ?: throw LinkDownloadException("Not a YouTube link")
        val audioOnly = job.kind == TUBE_AUDIO
        update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Reading YouTube…", speed = 0) }
        val (picture, sound) = Tube.downloadParts(id, job.quality?.removeSuffix("p")?.toIntOrNull(), audioOnly)
        if (sound == null || (!audioOnly && picture == null)) throw LinkDownloadException("YouTube offered no file to save")
        update(job.uri) { it.copy(state = DlState.DOWNLOADING, error = null) }
        val work = linkWork(job.uri)
        var before = 0L
        suspend fun fetch(url: String, part: String): LinkFetch.Result {
            val headers = mapOf("User-Agent" to Trailers.userAgent(url))
            return LinkFetch.download(url, headers, null, false, File(work, part), connections) { done, total, rate ->
                update(job.uri) { it.copy(state = DlState.DOWNLOADING, files = listOf(DlFile(0, job.label, before + maxOf(total, done), before + done, false)), speed = rate) }
                notifyProgress()
            }
        }
        val video = picture?.let { fetch(it, "v").also { r -> before = r.file.length() } }
        val audio = fetch(sound, "a")
        val dest = if (video == null) File(File(keepDir(), "Audio"), safe(job.label) + ".m4a")
        else File(File(keepDir(), "YouTube"), safe(job.label) + ".mp4")
        dest.parentFile?.mkdirs()
        if (video == null) {
            if (!audio.file.renameTo(dest)) audio.file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            update(job.uri) { it.copy(error = "Joining picture and sound…", speed = 0) }
            kotlinx.coroutines.withContext(Dispatchers.IO) { muxTube(video.file, audio.file, dest) }
        }
        work.deleteRecursively()
        writeFinished(listOf(Finished(dest.name, dest.length(), System.currentTimeMillis(), dest.path, job.poster, job.label, null, job.kind)) + _finished.value.filter { it.path != dest.path })
        synchronized(lock) {
            _live.value = _live.value.filter { it.job.uri != job.uri }
            writeQueue(jobs())
        }
    }

    /** A file or stream a web page loaded, downloaded from the Browser. */
    const val WEB_KIND = "web"

    /** [url] (a file, or an HLS stream when [hls]) downloaded with the page's [headers]. */
    fun addWeb(url: String, title: String, headers: Map<String, String>, hls: Boolean) = add(
        DownloadJob(uri = url, title = title, titleName = title, kind = WEB_KIND, link = true, headers = headers, playlist = hls),
    )

    /** Which Grabber! folder a Browser download goes to, by its extension. */
    private fun webFolder(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> "Audio"
        "pdf", "doc", "docx", "epub", "txt", "xls", "xlsx", "ppt", "pptx" -> "Documents"
        "jpg", "jpeg", "png", "webp", "gif" -> "Images"
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "mpg", "mpeg", "3gp", "m3u8" -> "Video"
        // Anything else (an archive, an app, a VPN's .conf…): Other, and not checked as a video.
        else -> "Other"
    }

    /** A file a web page made (no link to fetch), written to its Grabber! folder and listed. The folder's name. */
    fun saveFile(name: String, bytes: ByteArray): String {
        val folder = webFolder(name)
        val dest = File(File(keepDir(), folder), safe(name))
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        writeFinished(listOf(Finished(dest.name, dest.length(), System.currentTimeMillis(), dest.path, null, name, null, WEB_KIND)) + _finished.value.filter { it.path != dest.path })
        return folder
    }

    /** A page's video saved by yt-dlp: Grabber!/YouTube or Grabber!/Video. */
    const val MEDIA_KIND = "media"

    /** A page's sound saved (and converted) by yt-dlp and FFmpeg: Grabber!/Audio. */
    const val MEDIA_AUDIO = "media-audio"

    /** Between a page's address and the format in a yt-dlp download's id: the same page in two formats is two downloads. */
    private const val GRAB = "#grab="

    /** [page] saved in [format] ([SaveFormat.code]) by yt-dlp, with the page's [headers] (a site's Referer and cookies). */
    fun addMedia(page: String, format: String, title: String, poster: String?, headers: Map<String, String> = emptyMap()) = add(
        DownloadJob(
            uri = page + GRAB + format, title = title, titleName = title, poster = poster,
            kind = if (format.startsWith("a:")) MEDIA_AUDIO else MEDIA_KIND, link = true, quality = format, headers = headers,
        ),
    )

    /** [runLink] for yt-dlp: progress as it reports it, the file moved into its Grabber! folder when done. */
    private suspend fun saveMedia(job: DownloadJob) {
        val page = job.uri.substringBeforeLast(GRAB)
        val format = job.quality ?: "v:0"
        update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Reading the page…", speed = 0) }
        val work = linkWork(job.uri)
        val out = Media.run(app, page, format, job.headers, job.label, work) { pct, _ ->
            update(job.uri) { it.copy(state = DlState.DOWNLOADING, error = null, files = listOf(DlFile(0, job.label, 1000, (pct * 10).toLong().coerceIn(0, 1000), false))) }
            notifyProgress()
        }
        val youtube = Regex("""(^|\.)(youtube\.com|youtu\.be)$""").containsMatchIn(runCatching { java.net.URL(page).host }.getOrDefault(""))
        val folder = when {
            format.startsWith("a:") -> "Audio"
            youtube -> "YouTube"
            else -> "Video"
        }
        val dest = File(File(keepDir(), folder), safe(job.label) + "." + out.extension)
        dest.parentFile?.mkdirs()
        if (!out.renameTo(dest)) { out.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } } }
        work.deleteRecursively()
        writeFinished(listOf(Finished(dest.name, dest.length(), System.currentTimeMillis(), dest.path, job.poster, job.label, null, job.kind)) + _finished.value.filter { it.path != dest.path })
        synchronized(lock) {
            _live.value = _live.value.filter { it.job.uri != job.uri }
            writeQueue(jobs())
        }
    }

    /** One picture track and one sound track copied, sample by sample in time order, into one MP4 at [out]. */
    private fun muxTube(video: File, audio: File, out: File) {
        val tmp = File(out.path + ".part")
        val muxer = android.media.MediaMuxer(tmp.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(video to "video/", audio to "audio/").map { (f, kind) ->
            val ex = android.media.MediaExtractor().apply { setDataSource(f.path) }
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith(kind) }
            ex.selectTrack(track)
            ex to muxer.addTrack(ex.getTrackFormat(track))
        }
        try {
            muxer.start()
            val buffer = java.nio.ByteBuffer.allocateDirect(8 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            val done = BooleanArray(sources.size)
            while (!done.all { it }) {
                // The track whose next sample comes first: picture and sound stay interleaved, as players expect.
                val i = sources.indices.filter { !done[it] }.minBy { sources[it].first.sampleTime }
                val (ex, track) = sources[i]
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) { done[i] = true; continue }
                val key = ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, ex.sampleTime, if (key) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buffer, info)
                if (!ex.advance()) done[i] = true
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            sources.forEach { runCatching { it.first.release() } }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    /* ---------------------------------------------------------- direct links */

    /** Where a link's segments and progress are kept until it is done. */
    private fun linkWork(uri: String) = File(pieceDir(), "links/${Integer.toHexString(uri.hashCode())}")

    /**
     * A direct link — a single file, or an HLS playlist joined into one — saved to Movies/Watchly like
     * a torrent's file, with the same Wi-Fi rule, pause, resume and notification.
     */
    private fun beginLink(job: DownloadJob) {
        if (job.paused) return
        startService()
        val run = scope.launch {
            try {
                // Wi-Fi only, unless told to use mobile data for this one.
                while (wifiOnly && onMetered() && !job.mobileData && !(_live.value.find { it.job.uri == job.uri }?.job?.mobileData ?: false)) {
                    update(job.uri) { it.copy(state = DlState.WIFI) }
                    notifyProgress()
                    kotlinx.coroutines.delay(5000)
                }
                // A season's episodes take turns: file hosts refuse (403) a phone pulling many files at once.
                update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Waiting for its turn…", speed = 0) }
                linkSlots.withPermit { runLink(job) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                // Only this run's entry: a renewed link with the same address has already put its own.
                val me = coroutineContext[kotlinx.coroutines.Job]
                synchronized(lock) { if (links[job.uri] === me) links.remove(job.uri) }
                settle()
            }
        }
        synchronized(lock) { links[job.uri] = run }
    }

    /** Link downloads at once; the rest wait. One at a time is slow, more than two gets refused. */
    private val linkSlots = kotlinx.coroutines.sync.Semaphore(2)

    /** A link older than this is renewed before it starts: plugin links' tokens last minutes, not hours. */
    private const val LINK_FRESH_MS = 4 * 60 * 1000L

    private suspend fun runLink(job: DownloadJob) {
            try {
                // A YouTube video: its links read fresh from YouTube, picture and sound fetched apart and joined.
                if (job.kind == TUBE_KIND || job.kind == TUBE_AUDIO) {
                    saveTube(job)
                    return
                }
                // A page's video through yt-dlp (the Browser's "Grab", Tube's MP3/Opus/FLAC/WAV).
                if (job.kind == MEDIA_KIND || job.kind == MEDIA_AUDIO) {
                    saveMedia(job)
                    return
                }
                // Found a while ago (a season's batch waiting its turn, a pause, a restart): the link it has is asked first — a quick
                // look, seconds — and only a link that no longer answers sends the sources off for a fresh one, which takes a while.
                val stale = job.imdb != null && job.fetchedAt > 0 && System.currentTimeMillis() - job.fetchedAt > LINK_FRESH_MS
                if (stale) update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Checking the link still works…", speed = 0) }
                if (stale && !LinkFetch.answers(job.uri, job.headers, job.playlist)) {
                    update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Getting a fresh link…", speed = 0) }
                    val fresh = runCatching { LinkRefresh.fresh(job, tried(job), allowSame = true) }.getOrNull()
                    if (fresh != null) {
                        // Not a failure: it doesn't count against the automatic refreshes.
                        replaceLink(job.copy(refreshes = job.refreshes - 1), fresh, failed = false)
                        return
                    }
                }
                update(job.uri) { it.copy(state = DlState.DOWNLOADING, error = null) }
                val work = linkWork(job.uri)
                // A host that drops connections ("connection closed", reset, timeouts) is usually saying "too many at once":
                // the same link is tried again with half the connections, where it left off, before anything else — and the
                // host's next episodes start at what it took, rather than each finding out again.
                val host = runCatching { java.net.URL(job.uri).host }.getOrDefault("")
                var conns = minOf(connections, hostConns[host] ?: connections)
                var attempt = 0
                val result = run {
                    while (true) {
                        try {
                            return@run LinkFetch.download(job.uri, job.headers, job.quality, job.playlist, work, conns) { done, total, rate ->
                                update(job.uri) { it.copy(state = DlState.DOWNLOADING, files = listOf(DlFile(0, job.label, maxOf(total, done), done, false)), speed = rate) }
                                notifyProgress()
                            }
                        } catch (e: java.io.IOException) {
                            if (e is LinkExpiredException || attempt >= DROPPED_RETRIES) throw e
                            attempt++
                            // A playlist never fetched more than LinkFetch's cap at once: halved from what it really used.
                            conns = maxOf(MIN_CONNECTIONS, (if (job.playlist) minOf(conns, LinkFetch.HLS_MAX) else conns) / 2)
                            hostConns[host] = conns
                            Log.w(TAG, "${job.label}: ${e.message}; again with $conns connections")
                            update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Connection dropped — trying again", speed = 0) }
                            kotlinx.coroutines.delay(3000L * attempt)
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
                // A web page saved as the episode (the host's error or "click to download" page): not a download.
                // Thrown away, and the link counts as dead, so a fresh one is looked for.
                // (A Browser download of a song or a document is small, and a document can be text: only its videos are checked.)
                val web = job.kind == WEB_KIND
                if ((!web || webFolder("x." + result.ext) == "Video") && notVideo(result.file)) {
                    work.deleteRecursively()
                    // Small and not text: a piece of a stream or a preview clip, not a page.
                    val tiny = result.file.length() in 1 until MIN_VIDEO_BYTES && !looksLikeText(runCatching { result.file.inputStream().use { i -> ByteArray(64).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } } }.getOrDefault(ByteArray(0)))
                    throw LinkExpiredException(if (tiny) "Only ${com.torrentviewer.core.bytes(result.file.length())}: a piece of a stream or a preview, not the video" else "The link sent a web page, not the video")
                }
                // A link's own file name ("song.mp3") already has its extension.
                val named = if (web && job.label.endsWith(".${result.ext}", true)) job.label else job.label + "." + result.ext
                val dest = destOf(job, job.label, safe(named))
                dest.parentFile?.mkdirs()
                if (!result.file.renameTo(dest)) {
                    result.file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                }
                work.deleteRecursively()
                autoRounds.remove(job.label)
                writeFinished(listOf(Finished(dest.name, dest.length(), System.currentTimeMillis(), dest.path, job.poster, job.label, job.imdb, job.kind)) + _finished.value.filter { it.path != dest.path })
                synchronized(lock) {
                    _live.value = _live.value.filter { it.job.uri != job.uri }
                    writeQueue(jobs())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link download failed: ${e.message}")
                // An expired or unreachable link: ask the sources for a fresh one and carry on with it.
                // Dropped connections that outlived the retries above count too: that link isn't going to work today.
                val dead = e is java.io.IOException
                if (dead && job.refreshes < MAX_REFRESHES && job.imdb != null) {
                    update(job.uri) { it.copy(state = DlState.RESOLVING, error = "Link expired — getting a fresh one…", speed = 0) }
                    failedLinks.merge(job.label, setOf(job.uri)) { a, b -> a + b }
                    val fresh = runCatching { LinkRefresh.fresh(job, tried(job)) }.onFailure { Log.w(TAG, "refresh: ${it.message}") }.getOrNull()
                    if (fresh != null) {
                        replaceLink(job, fresh)
                        return
                    }
                }
                // Out of fresh links for now: not left for the viewer to retry by hand — asked again on its own a little later
                // (sources come back, links get renewed), a few times, by the same rules.
                val round = autoRounds.merge(job.label, 1, Int::plus) ?: 1
                val wait = waitMinutes(round)
                // Short (one line on the card): what's saved is always kept, so it isn't said.
                errors[job.uri] = justTried(job.uri) + (if (dead && job.imdb != null) "No fresh link" else (e.message ?: "Couldn't save that link")) + " · retrying in $wait min"
                update(job.uri) { it.copy(state = DlState.FAILED, error = errors[job.uri], speed = 0) }
                wakeIn(wait)
                scope.launch {
                    kotlinx.coroutines.delay(wait * 60_000L)
                    // Unless it was cancelled, paused or retried by hand meanwhile.
                    if (_live.value.any { it.job.uri == job.uri && it.state == DlState.FAILED }) retry(job.uri)
                }
            }
    }

    /** Fresh links tried in a row per download before it waits a while ([AUTO_ROUNDS]). */
    private const val MAX_REFRESHES = 4

    /** Quick rounds for a download that ran out of fresh links (after 2, 4, 6 minutes); then it keeps trying, less often. */
    private const val AUTO_ROUNDS = 3

    /**
     * Minutes before a download out of fresh links is tried again, round by round: 2, 4, 6, then 15, 30, then every hour
     * for as long as it takes — days if need be, as 1DM does — what's saved kept all the while. Only cancel ends it.
     */
    private fun waitMinutes(round: Int) = when {
        round <= AUTO_ROUNDS -> round * 2
        round == AUTO_ROUNDS + 1 -> 15
        round == AUTO_ROUNDS + 2 -> 30
        else -> 60
    }

    /** Per download (by its name): how many automatic rounds it has had. */
    private val autoRounds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The same link tried again after dropped connections, each time with half the connections, before a fresh link. */
    private const val DROPPED_RETRIES = 4

    /** Per host: the connections it put up with last, so a season's next episodes don't each start at 64 and fail. */
    private val hostConns = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Never fewer connections than this on a retry: still quick, and few enough for a strict host. */
    private const val MIN_CONNECTIONS = 4

    /** Swaps a dead link for [hit] — a fresh one for the same video — and starts it. */
    /** Per download (by its name): every link that failed it, so a refresh or a retry never goes back to one. */
    private val failedLinks = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun tried(job: DownloadJob) = failedLinks[job.label].orEmpty()

    /**
     * The ↻ on a failed download. A link from a known title: the sources are asked again in the background and
     * the next fresh link at the same resolution — never one that already failed — takes over, from the start.
     * A torrent, or a link with no title to search: tried again as it is.
     */
    /** When ↻ was last tapped on a download: a failure soon after says so, so the tap is seen to have done something. */
    private val triedByHand = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** "Tried again just now · " for a download whose ↻ was tapped in the last few minutes. */
    private fun justTried(uri: String) = if (System.currentTimeMillis() - (triedByHand[uri] ?: 0) < 5 * 60_000L) "Tried again just now · " else ""

    fun retry(uri: String, byHand: Boolean = false) {
        val job = _live.value.find { it.job.uri == uri }?.job ?: return
        if (job.paused) return
        if (!job.link || job.imdb == null) return resume(uri)
        // By hand: every link gets another chance (one that failed an hour ago may work now), the sources are asked
        // afresh rather than from memory, and the waiting rounds start over.
        if (byHand) {
            failedLinks.remove(job.label)
            autoRounds.remove(job.label)
            triedByHand[uri] = System.currentTimeMillis()
        } else failedLinks.merge(job.label, setOf(job.uri)) { a, b -> a + b }
        update(uri) { it.copy(state = DlState.RESOLVING, error = "Finding a fresh ${job.quality ?: ""} link…".replace("  ", " "), speed = 0) }
        scope.launch {
            if (byHand) {
                com.torrentviewer.core.forgetStreams()
                runCatching { com.torrentviewer.mobile.cs.CsPlugins.forgetPages() }
            }
            // By hand: the episode searched again in full, everywhere — and, with no single episode, a pack holding it.
            val fresh = runCatching { LinkRefresh.fresh(job, if (byHand) setOf(job.uri) else tried(job), wide = byHand) }.onFailure { Log.w(TAG, "retry: ${it.message}") }.getOrNull()
            // A retry by hand starts the automatic refreshes over.
            if (fresh != null) replaceLink(job.copy(refreshes = -1), fresh)
            // Nothing new by hand: the link it had, tried again as it is — a tap always does something.
            else if (byHand) resume(uri)
            else {
                // Still nothing: asked again on its own, a few rounds, before it waits for the viewer.
                val round = autoRounds.merge(job.label, 1, Int::plus) ?: 1
                val wait = waitMinutes(round)
                update(uri) {
                    it.copy(state = DlState.FAILED, speed = 0, error = "No other ${job.quality ?: "working"} source · retrying in $wait min")
                }
                wakeIn(wait)
                scope.launch {
                    kotlinx.coroutines.delay(wait * 60_000L)
                    if (_live.value.any { it.job.uri == uri && it.state == DlState.FAILED }) retry(uri)
                }
            }
        }
    }

    /**
     * "Different Source" on a download that looks wrong (too small for its resolution, the wrong codec): its link is
     * stopped, what it fetched is thrown away, and the source is turned down for good — by address and by its addon and
     * link name, so the same file renewed doesn't come back. The sources are then asked again, as ↻ does, for the next
     * one at the resolution; nothing found leaves it failed, its ↻ asking again later.
     */
    fun differentSource(uri: String) {
        val row = _live.value.find { it.job.uri == uri } ?: return
        if (!row.job.link || row.job.imdb == null) return
        val run = synchronized(lock) { links.remove(uri) }
        run?.cancel()
        val job = row.job.copy(rejected = (row.job.rejected + uri + rejectKey(row.job.source, row.job.linkName)).distinct())
        failedLinks.merge(job.label, setOf(uri)) { a, b -> a + b }
        fun mark() = synchronized(lock) {
            _live.value = _live.value.map { if (it.job.uri == uri) LiveDownload(job, DlState.RESOLVING, error = "Finding a different source…") else it }
            writeQueue(jobs())
        }
        mark()
        scope.launch {
            // The stopped run winds down first: whatever it says on its way out (a failure, a retry) is then overwritten.
            run?.join()
            if (_live.value.none { it.job.uri == uri }) return@launch
            mark()
            // A different file: nothing of this one carries over.
            linkWork(uri).deleteRecursively()
            com.torrentviewer.core.forgetStreams()
            runCatching { com.torrentviewer.mobile.cs.CsPlugins.forgetPages() }
            // Any size: the one turned down may have been the odd one out. Not the same source's same link renewed either.
            val fresh = runCatching { LinkRefresh.fresh(job.copy(size = 0, linkName = null), tried(job) + uri, wide = true) }
                .onFailure { Log.w(TAG, "different source: ${it.message}") }.getOrNull()
            if (fresh != null) replaceLink(job.copy(refreshes = -1), fresh)
            else update(uri) { it.copy(state = DlState.FAILED, speed = 0, error = "No other ${job.quality ?: ""} source found · tap ↻ to look again".replace("  ", " ")) }
            settle()
        }
    }

    private fun replaceLink(old: DownloadJob, hit: Hit, failed: Boolean = true) {
        if (failed) failedLinks.merge(old.label, setOf(old.uri)) { a, b -> a + b }
        // Paused (or cancelled) while the fresh link was being found: kept as asked — the new link is recorded, not started.
        val now = _live.value.find { it.job.uri == old.uri } ?: return
        val next = old.copy(
            paused = now.job.paused,
            uri = hit.uri,
            link = hit.direct,
            headers = if (hit.direct) hit.headers else emptyMap(),
            playlist = hit.direct && isPlaylist(hit),
            quality = hit.quality ?: old.quality,
            source = hit.addon.ifEmpty { old.source },
            linkName = hit.name,
            size = if (hit.size > 0) hit.size else old.size,
            refreshes = old.refreshes + 1,
            fetchedAt = System.currentTimeMillis(),
        )
        Log.i(TAG, "fresh link for ${old.label} from ${hit.addon}")
        // What's saved so far goes along to the new link: the file's parts (or the playlist's segments) carry on
        // where they stopped when it's the same video — LinkFetch checks the size (or segment count) and starts
        // over only if it isn't. A switch between a playlist and a file can't share anything.
        val from = linkWork(old.uri)
        val to = linkWork(next.uri)
        if (from != to) runCatching {
            to.deleteRecursively()
            if (old.playlist != next.playlist || !from.renameTo(to)) from.deleteRecursively()
        }
        synchronized(lock) {
            _live.value = _live.value.map {
                if (it.job.uri == old.uri) LiveDownload(next, if (next.paused) DlState.PAUSED else DlState.RESOLVING, error = if (next.paused) null else "Got a fresh link") else it
            }
            writeQueue(jobs())
        }
        if (!next.paused) begin(next)
    }

    /* -------------------------------------------------------------- engine */

    /**
     * The streaming engine's tuning (see TorrentStream.ensureSession for what each one fixed):
     * libtorrent's defaults dial 30 peers a second with a 15s timeout and keep 500 requests
     * in flight, which left a download crawling at a fraction of what the swarm could give.
     */
    private fun ensureSession(): SessionManager = synchronized(lock) {
        session?.takeIf { it.isRunning }?.let { return it }
        val totalRam = runCatching {
            ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem
        }.getOrDefault(Long.MAX_VALUE)
        val peers = if (totalRam < 3L * 1024 * 1024 * 1024) 80 else 200
        val settings = SettingsPack()
            .connectionsLimit(peers)
            .activeDhtLimit(88)
            .downloadRateLimit(0)
            .uploadRateLimit(0)
            .setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), peers)
            .setInteger(settings_pack.int_types.connection_speed.swigValue(), peers)
            .setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 5)
            .setInteger(settings_pack.int_types.request_timeout.swigValue(), 20)
            .setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false)
            .setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), BOOTSTRAP)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        // The DHT nodes from last time: a cold routing table spent the first minutes finding peers.
        val params = runCatching { if (sessionFile.exists()) SessionParams(sessionFile).also { it.setSettings(settings) } else null }.getOrNull()
            ?: SessionParams(settings)
        val s = SessionManager()
        s.start(params)
        s.startDht()
        session = s
        s
    }

    private fun hashOf(uri: String): Sha1Hash? = runCatching {
        val ec = error_code()
        val p = libtorrent.parse_magnet_uri(uri, ec)
        if (ec.value() != 0) null else Sha1Hash.parseHex(p.getInfo_hashes().get_best().to_hex())
    }.getOrNull()

    private fun begin(job: DownloadJob) {
        if (job.link) return beginLink(job)
        scope.launch {
            try {
                startService()
                val s = ensureSession()
                val uri = if (SettingsStore.value.value.addTrackers) withTrackers(job.uri) else job.uri
                val dir = pieceDir()
                val h: TorrentHandle = if (uri.startsWith("magnet:", true)) {
                    val hash = hashOf(uri) ?: error("That magnet link could not be parsed")
                    if (s.find(hash)?.isValid != true) s.download(uri, dir, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    var found: TorrentHandle? = null
                    for (i in 0 until METADATA_SECONDS * 2) {
                        if (!isActive || _live.value.none { it.job.uri == job.uri }) return@launch
                        val h = s.find(hash)
                        if (h != null && h.isValid && h.status().hasMetadata()) {
                            found = h
                            break
                        }
                        delay(500)
                    }
                    found ?: error("No peers answered. The torrent may be dead, or the network is blocking BitTorrent.")
                } else {
                    val file = if (uri.startsWith("http", true)) File(app.cacheDir, "dl-${uri.hashCode()}.torrent").also { f -> URL(uri).openStream().use { i -> f.outputStream().use { i.copyTo(it) } } } else File(uri.removePrefix("file://"))
                    val ti = TorrentInfo(file)
                    s.download(ti, dir, null, Array(ti.files().numFiles()) { Priority.IGNORE }, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    s.find(ti.infoHash()) ?: error("Couldn't add that torrent")
                }
                // Paused means paused: an auto-managed torrent is resumed by the session's queue.
                runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED) }
                // Added in order (the only flags the add takes); rarest-first unless asked.
                applyOrder(job.uri, h)
                synchronized(lock) { handles[job.uri] = h }
                choose(job.uri, h)
                poll()
            } catch (e: Exception) {
                Log.w(TAG, "download failed: ${e.message}")
                errors[job.uri] = e.message ?: "Couldn't start that download"
                update(job.uri) { it.copy(state = DlState.FAILED, error = errors[job.uri]) }
                settle()
            }
        }
    }

    private fun filesOf(ti: TorrentInfo): List<TorrentFile> {
        val fs = ti.files()
        return (0 until fs.numFiles()).map { i ->
            val path = fs.filePath(i)
            TorrentFile(i, path, fs.fileName(i), fs.fileSize(i), path.substringAfterLast('.', "").lowercase() in VIDEO, false)
        }
    }

    /** Episode order — S01E02 before S01E10 — whatever order the torrent lists its files in; anything else after, as listed. */
    private fun ord(name: String, index: Int): Long = parseEpisode(name)?.let { it.season * 10_000L + it.episode } ?: (100_000_000L + index)

    /** A season's videos, for the pack's full list — skipped ones included, so they can be put back. */
    private fun seasonVideos(job: DownloadJob, files: List<TorrentFile>): List<Int> =
        job.season?.let { s ->
            // A whole pack: every episode in it (no samples or extras: those carry no episode number).
            if (s == WHOLE_PACK) files.filter { it.isVideo && parseEpisode(it.name) != null }.map { it.index }
            else files.filter { it.isVideo && (parseEpisode(it.name)?.season == s || s in seasonsIn(it.path)) }.map { it.index }
        }.orEmpty()

    /**
     * Episodes arrive in order: the next one unsaved at the top priority, the two after it
     * a step down each, the rest of the wanted at the default; skipped and saved are ignored.
     */
    private fun prioritize(h: TorrentHandle, job: DownloadJob, files: List<TorrentFile>) {
        val order = job.files.filter { it !in job.saved && it in files.indices }.sortedBy { ord(files[it].name, it) }
        val prios = Array(files.size) { Priority.IGNORE }
        order.forEachIndexed { rank, i ->
            prios[i] = when (rank) {
                0 -> Priority.TOP_PRIORITY
                1 -> Priority.SIX
                2 -> Priority.FIVE
                else -> Priority.DEFAULT
            }
        }
        runCatching { h.prioritizeFiles(prios) }
    }

    /** Skip an episode of a pack, or put it back. A saved one stays saved either way. */
    fun setWanted(uri: String, index: Int, on: Boolean) {
        // The tick shows at once; the next poll would otherwise be up to two seconds away.
        update(uri) {
            it.copy(
                job = it.job.copy(
                    files = if (on) (it.job.files + index).distinct() else it.job.files - index,
                    skipped = if (on) it.job.skipped - index else (it.job.skipped + index).distinct(),
                ),
                files = it.files.map { f -> if (f.index == index) f.copy(wanted = on) else f },
            )
        }
        val h = synchronized(lock) { handles[uri] } ?: return
        scope.launch {
            val job = _live.value.find { it.job.uri == uri }?.job ?: return@launch
            val ti = runCatching { h.torrentFile() }.getOrNull() ?: return@launch
            prioritize(h, job, filesOf(ti))
        }
    }

    /** Which files this job wants, now the torrent has said what it holds. */
    private fun choose(uri: String, h: TorrentHandle) {
        val ti = runCatching { h.torrentFile() }.getOrNull() ?: return
        val job = _live.value.find { it.job.uri == uri }?.job ?: return
        val files = filesOf(ti)
        val season = seasonVideos(job, files)
        val tagged = job.tags.mapNotNull { pickFile(files, it) }
        val asked = (job.files + season + tagged).distinct().ifEmpty { listOfNotNull(pickFile(files)) }
        if (asked.isEmpty()) {
            update(uri) { it.copy(state = DlState.FAILED, error = "No video in this torrent") }
            return
        }
        // The season and tags would bring back what was unticked; on a restart they did.
        val wanted = asked - job.skipped.toSet()
        prioritize(h, job.copy(files = wanted), files)
        if (job.paused) runCatching { h.pause() } else runCatching { h.resume() }
        update(uri) { it.copy(job = it.job.copy(files = wanted), state = if (job.paused) DlState.PAUSED else DlState.DOWNLOADING) }
    }

    /**
     * Data saver (Settings → Downloads): on mobile data, a season scan checks fewer links (the preferred resolution's,
     * at most 40 an episode). On Wi-Fi it changes nothing.
     */
    var dataSaver: Boolean
        get() = prefs().getBoolean("dataSaver", true)
        set(v) = prefs().edit().putBoolean("dataSaver", v).apply()

    /** On mobile data with [dataSaver] on: the scan's lighter mode. */
    fun saving(): Boolean = ::app.isInitialized && dataSaver && onMetered()

    private fun onMetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    private fun poll() {
        synchronized(lock) {
            if (poller?.isActive == true) return
            poller = scope.launch {
                while (isActive) {
                    tick()
                    if (synchronized(lock) { handles.isEmpty() } && _live.value.none { it.state == DlState.RESOLVING }) break
                    delay(2000)
                }
                settle()
            }
        }
    }

    private fun tick() {
        val metered = wifiOnly && onMetered()
        for ((uri, h) in synchronized(lock) { handles.toMap() }) {
            val row = _live.value.find { it.job.uri == uri } ?: continue
            if (!h.isValid) continue
            val ti = runCatching { h.torrentFile() }.getOrNull() ?: continue
            val job = row.job

            // Wi-Fi only: hold on mobile data, pick back up on Wi-Fi, unless told to carry on.
            val hold = metered && !job.mobileData && !job.paused
            if (hold && row.state != DlState.WIFI) runCatching { h.pause() }
            if (!hold && row.state == DlState.WIFI) runCatching { h.resume() }

            val st = runCatching { h.status() }.getOrNull() ?: continue
            val fs = ti.files()
            val progress = runCatching { h.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull()
            var saved = job.saved
            for (i in job.files) {
                if (i in saved || progress == null || i !in progress.indices) continue
                val size = fs.fileSize(i)
                if (size <= 0 || progress[i] < size) continue
                // Done: copy it out and keep the label it was downloaded under.
                val src = File(pieceDir(), fs.filePath(i))
                val dest = destOf(job, ti.name(), fs.filePath(i))
                try {
                    dest.parentFile?.mkdirs()
                    if (!(dest.exists() && dest.length() == size)) src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    saved = saved + i
                    val label = if (job.files.size > 1) parseEpisode(fs.fileName(i))?.let { e -> "${job.titleName?.substringBefore(" · ") ?: ti.name()} · S%02dE%02d".format(e.season, e.episode) } ?: fs.fileName(i) else job.label
                    writeFinished(listOf(Finished(fs.fileName(i), size, System.currentTimeMillis(), dest.path, job.poster, label, job.imdb, job.kind)) + _finished.value.filter { it.path != dest.path })
                } catch (e: Exception) {
                    Log.w(TAG, "copy failed: ${e.message}")
                }
            }
            val all = filesOf(ti)
            // One saved: the next episode moves up to the top priority.
            if (saved != job.saved) prioritize(h, job.copy(saved = saved), all)
            // The pack in episode order, skipped episodes included so they can be put back.
            val files = (job.files + seasonVideos(job, all)).distinct().filter { it in all.indices }.sortedBy { ord(all[it].name, it) }
                .map { i -> DlFile(i, fs.fileName(i), fs.fileSize(i), progress?.getOrNull(i) ?: 0, i in saved, i in job.files) }
            val state = when {
                job.paused -> DlState.PAUSED
                hold -> DlState.WIFI
                else -> DlState.DOWNLOADING
            }
            update(uri) { it.copy(job = it.job.copy(saved = saved), state = state, files = files, speed = st.downloadPayloadRate(), peers = st.numPeers()) }

            // Everything wanted is out in Movies: the torrent's own copy can go.
            if (job.files.isNotEmpty() && job.files.all { it in saved }) {
                synchronized(lock) {
                    handles.remove(uri)
                    _live.value = _live.value.filter { it.job.uri != uri }
                    writeQueue(jobs())
                }
                runCatching { session?.remove(h, SessionHandle.DELETE_FILES) }
            }
        }
        notifyProgress()
    }

    val running get() = _live.value.any { it.state == DlState.RESOLVING || it.state == DlState.DOWNLOADING || it.state == DlState.WIFI }

    /** Nothing running: let the service, the wake lock and the session go. */
    private fun settle() {
        if (running) return
        stopService()
        if (synchronized(lock) { handles.isEmpty() }) {
            val s = synchronized(lock) { session.also { session = null } }
            if (s != null) scope.launch {
                runCatching { s.saveState()?.let { sessionFile.writeBytes(it) } }
                runCatching { s.stop() }
            }
        }
    }

    /* ------------------------------------------------- service, notification */

    private const val CHANNEL = "downloads"
    const val NOTE_ID = 7

    private fun startService() {
        // Refused or out of time today, and the app not open: the background job carries on instead (see onServiceTimeout).
        val held = refusedAt > 0 && !visible && System.currentTimeMillis() - refusedAt < 24 * 60 * 60 * 1000L
        if (!held) runCatching {
            val i = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i) else app.startService(i)
        }
        if (wake?.isHeld != true) {
            wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchly:downloads").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) runCatching {
            @Suppress("DEPRECATION")
            wifiLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "watchly:downloads").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun stopService() {
        // Not stopService(): stopped before it reached startForeground, a foreground service
        // crashes the app. One still starting sees nothing running and stops itself.
        DownloadService.instance?.stopSelf()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_ID)
        lastNote = ""
    }

    fun notification(context: Context, title: String = "Downloading", text: String? = null, percent: Int = 0): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        // Straight to the Downloads screen, not whichever tab the app was left on.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = android.net.Uri.parse(DOWNLOADS_LINK)
        }
        val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun notifyProgress() {
        val rows = _live.value.filter { it.state != DlState.PAUSED && it.state != DlState.FAILED }
        if (rows.isEmpty()) return
        val total = rows.sumOf { it.total }
        val percent = if (total > 0) (rows.sumOf { it.done } * 100 / total).toInt() else 0
        val rate = rows.sumOf { it.speed }
        val waiting = rows.all { it.state == DlState.WIFI }
        val title = (if (waiting) "Waiting for Wi-Fi" else "Downloading") + " · $percent%"
        val left = rows.sumOf { it.total - it.done }
        val eta = if (rate > 0 && left > 0) (left / rate).let { s -> if (s < 3600) "${s / 60 + 1}m left" else "${s / 3600}h ${s % 3600 / 60}m left" } else "— left"
        val text = "${speed(rate)} · $eta · " + if (rows.size > 1) "${rows.size} downloads" else rows.first().job.label
        val key = "$title|${rate / 25_000}|${rows.size}"
        if (key == lastNote) return
        lastNote = key
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, notification(app, title, text, percent))
    }
}

/** Keeps the process, and so the download, alive with the screen off and the app away. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // In the foreground first, at once: Android allows only a few seconds after it is asked to start, and starting
        // the app's services below (the torrent engine, the plugins) can take longer — "did not then call
        // startForeground()", the crash this was.
        foreground()
        // Restarted by the system after the process died: the queue picks itself back up.
        Services.init(applicationContext)
    }

    /**
     * Into the foreground with the downloads' notification. Android may refuse (its daily limit on background
     * downloading spent, or no leave to start from the background): then the background job carries the downloads
     * on in bursts instead, and the app doesn't ask again until it has been opened.
     */
    private fun foreground(): Boolean = runCatching {
        // The full notification, or — if building it fails for any reason — a bare one: Android must hear
        // startForeground() within seconds whatever happens, or it closes the app.
        val note = runCatching { Downloads.notification(this) }.getOrElse {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { nm.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)) }
            NotificationCompat.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Downloading").setOngoing(true).build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(Downloads.NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Downloads.NOTE_ID, note)
        instance = this
        true
    }.getOrElse {
        Log.w("DownloadService", "not let into the foreground: ${it.message}")
        Downloads.onServiceRefused()
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance == null && !foreground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Downloads.running) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** Set once it is in the foreground — only then may it be stopped. */
        @Volatile
        var instance: DownloadService? = null
    }

    /** Android 15 caps a data-sync service at six hours a day, and kills an app still in the foreground after this. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.onServiceTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

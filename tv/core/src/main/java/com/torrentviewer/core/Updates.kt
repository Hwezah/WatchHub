package com.torrentviewer.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/**
 * New versions from the GitHub releases, with no store: asked at each launch, downloaded here, and
 * handed to Android's installer, which asks the viewer to confirm. It only takes an APK signed with
 * the same key and a higher versionCode.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/$RELEASE_REPO/releases/latest"
    /** The version "Not now" was said to. */
    private const val SKIPPED = "update-skipped"

    private var version = ""
    /** Which app's files on the release page: "Watchly-phone-", "Watchly-tv-". */
    private var prefix = ""

    private val _release = MutableStateFlow<AppRelease?>(null)
    /** A version newer than this one, once found. */
    val release: StateFlow<AppRelease?> = _release

    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden

    private val _progress = MutableStateFlow<Float?>(null)
    /** 0–1 while downloading. */
    val progress: StateFlow<Float?> = _progress

    private var job: Job? = null

    private val _ready = MutableStateFlow<AppRelease?>(null)
    /** A newer phone build downloaded in the background ([prepare]), waiting for "Install". */
    val ready: StateFlow<AppRelease?> = _ready

    private val _failed = MutableStateFlow<String?>(null)
    /** The version whose background download failed, for the strip to offer a retry; it's tried again by itself too. */
    val failed: StateFlow<String?> = _failed

    private val _failReason = MutableStateFlow<String?>(null)
    /** Why the last background download failed, in a few words, for the strip. */
    val failReason: StateFlow<String?> = _failReason

    /** Automatic retries made for a version ([prepare]): a few, a minute apart, then only the strip's Retry. */
    private val retries = HashMap<String, Int>()
    private const val AUTO_RETRIES = 3

    /** The strip's ✕ was tapped: no build is offered again until the app is next opened (or asked for in Settings). */
    private var dismissed = false

    /** The phone strip's ✕: hidden until the app is next opened; a download under way carries on. */
    fun dismiss() {
        dismissed = true
        _hidden.value = true
    }

    /**
     * [r] downloaded in the background as soon as it's found, the phone strip's way, so "Install" is instant: [ready] once
     * it's whole and checked. Quiet when it fails ([failed]): the strip offers a retry.
     */
    fun prepare(context: Context, r: AppRelease) {
        if (_ready.value == r || job?.isActive == true) return
        val app = context.applicationContext
        _ready.value = null
        _failed.value = null
        _failReason.value = null
        job = AppScope.launch {
            _progress.value = 0f
            try {
                withContext(Dispatchers.IO) { fetch(r, file(app)) }
                _ready.value = r
                retries.remove(r.version)
            } catch (e: CancellationException) {
                file(app).delete()
                throw e
            } catch (e: Exception) {
                file(app).delete()
                _failed.value = r.version
                _failReason.value = (if (e is java.io.FileNotFoundException) "the build was still being published" else e.message)?.take(80)
                // Mostly a build caught mid-publish (each push replaces the files): tried again a minute later, a few times.
                val n = (retries[r.version] ?: 0) + 1
                retries[r.version] = n
                // Its own job, not this one's child: the download job must end for [prepare] to start another.
                if (n <= AUTO_RETRIES) AppScope.launch {
                    kotlinx.coroutines.delay(60_000L)
                    if (_failed.value == r.version) prepare(app, r)
                }
            } finally {
                _progress.value = null
            }
        }
    }

    /** The build [prepare] downloaded, handed to Android's installer; downloaded again if the file has gone. */
    fun installReady(context: Context) {
        val app = context.applicationContext
        val r = _ready.value ?: return
        if (file(app).isFile) install(app, file(app)) else {
            _ready.value = null
            prepare(app, r)
        }
    }

    private fun file(context: Context) = File(context.filesDir, "update.apk")

    private suspend fun latest(): AppRelease? {
        val json = Net.get(LATEST, 15_000, quiet = true, headers = mapOf("Accept" to "application/vnd.github+json"))
        return parseRelease(json, Build.SUPPORTED_ABIS.toList(), prefix)?.takeIf { isNewerVersion(it.version, version) }
    }

    /** At launch, quietly. The debug build is another app beside this one: an update would not replace it. */
    fun check(context: Context, version: String, prefix: String, debug: Boolean) {
        // Once a run: the shell calling this comes back whenever the activity is recreated, and would delete a download.
        if (this.prefix.isNotEmpty()) return
        this.version = version
        this.prefix = prefix
        if (debug) return
        // What an update that went through left behind.
        file(context).delete()
        AppScope.launch {
            val found = runCatching { latest() }.getOrNull() ?: return@launch
            _hidden.value = Kv.global(SKIPPED) == found.version
            _release.value = found
        }
    }

    /** From Settings: brings back a version put off with "Not now". */
    fun checkNow(say: (String) -> Unit) {
        AppScope.launch {
            val found = try {
                latest()
            } catch (_: IOException) {
                return@launch say("Couldn't check for updates")
            }
            _release.value = found
            _hidden.value = false
            if (found == null) say("You're on the latest version")
        }
    }

    private const val PHONE_LATEST = "https://api.github.com/repos/$RELEASE_REPO/releases/tags/phone-latest"
    private var lastPhoneCheck = 0L

    /**
     * The phone's builds: every push builds a new "phone-latest", with a higher versionCode than the one
     * installed. Asked at launch and whenever the app comes back to the front (at most every 5 minutes), so a
     * new build is offered soon after it's out. The debug and release apps alike: each installs over itself.
     */
    fun checkPhoneBuild(versionCode: Int, prefix: String, force: Boolean = false, say: ((String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        // At most once a minute: often enough that a build finished a moment ago shows the next time the app comes back.
        if (!force && now - lastPhoneCheck < 60_000L) return
        lastPhoneCheck = now
        AppScope.launch {
            val abis = Build.SUPPORTED_ABIS.toList()
            // GitHub's API first; when it won't answer (its hourly limit, a moment mid-publish), the version file
            // published beside the APKs, which has no limit.
            val build = runCatching { Net.get(PHONE_LATEST, 15_000, quiet = true, headers = mapOf("Accept" to "application/vnd.github+json")) }.getOrNull()
                ?.let { parsePhoneBuild(it, abis, prefix) }
                ?: runCatching { Net.get(PHONE_VERSION_URL, 15_000, quiet = true) }.getOrNull()?.let { parsePhoneVersion(it, abis, prefix) }
            if (build == null) return@launch say?.invoke("Couldn't reach GitHub to check — try again in a minute") ?: Unit
            val (code, found) = build
            if (code <= versionCode) return@launch say?.invoke("You're on the latest build (${versionCode - 100})") ?: Unit
            // Asked for from Settings: shown even if it was put off with "Not now" or the strip's ✕.
            if (force) dismissed = false
            _hidden.value = !force && (dismissed || Kv.global(SKIPPED) == found.version)
            _release.value = found
            say?.invoke("Build ${code - 100} is ready — see the update card")
        }
    }

    fun skip(version: String) {
        Kv.setGlobal(SKIPPED, version)
        _hidden.value = true
    }

    fun cancel() {
        job?.cancel()
    }

    fun download(context: Context, r: AppRelease, say: (String) -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = AppScope.launch {
            _progress.value = 0f
            try {
                withContext(Dispatchers.IO) { fetch(r, file(app)) }
                install(app, file(app))
            } catch (e: CancellationException) {
                file(app).delete()
                throw e
            } catch (e: Exception) {
                file(app).delete()
                say(e.message ?: "Couldn't download the update")
            } finally {
                _progress.value = null
            }
        }
    }

    /**
     * [fetchOnce], tried again a few times: a phone's lookup of github.com fails now and then for a moment (a network
     * switching, a slow DNS) and the next try finds it. Said in plain words if it never does.
     */
    private suspend fun fetch(r: AppRelease, to: File) {
        var last: Exception? = null
        // Carries the server's ETag/Last-Modified between tries, so a resume only picks up when the file hasn't changed.
        var validator: String? = null
        repeat(5) { attempt ->
            try {
                validator = fetchOnce(r, to, resume = if (to.isFile) to.length() else 0L, validator = validator)
                return
            } catch (e: java.net.UnknownHostException) {
                last = e
            } catch (e: IOException) {
                // A cut-off read ("unexpected end of stream", a reset, a short transfer) as well as timeouts and refused
                // connections: keep what arrived and resume on the next try, rather than failing the whole thing.
                last = e
            }
            _progress.value = 0f
            kotlinx.coroutines.delay(2_000L * (attempt + 1))
        }
        throw IOException(if (last is java.net.UnknownHostException) "Couldn't reach GitHub — check the connection (or a VPN / DNS blocker) and tap Update again" else "The download kept stopping — tap Update to try again", last)
    }

    /**
     * One download attempt, resuming from [resume] bytes already on disk when it can: a Range request (guarded by
     * [validator] through `If-Range`, so a build republished mid-download comes back whole, not spliced). Returns the
     * server's validator for the next attempt. Throws on anything short of a whole, valid APK, for [fetch] to retry.
     */
    private suspend fun fetchOnce(r: AppRelease, to: File, resume: Long, validator: String?): String? {
        val conn = (URL(r.url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (resume > 0) {
                setRequestProperty("Range", "bytes=$resume-")
                if (validator != null) setRequestProperty("If-Range", validator)
            }
        }
        val code = conn.responseCode
        if (code == 416) { to.delete(); throw IOException("The part file no longer matches; starting over.") }
        if (code != 200 && code != 206) throw IOException("GitHub answered $code")
        val tag = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified") ?: validator
        // 206: the rest, appended to what's here. 200: the server sent the whole file (no range, or the build changed),
        // so start the file over.
        val resuming = code == 206 && resume > 0
        if (!resuming && to.isFile) to.delete()
        val from = if (resuming) resume else 0L
        val total = conn.contentLengthLong.takeIf { it > 0 }?.let { from + it } ?: r.size
        conn.inputStream.use { input ->
            java.io.FileOutputStream(to, resuming).use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = from
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) _progress.value = done.toFloat() / total
                }
            }
        }
        // Whole, and the file the card meant? The checksum is over the finished file, resumed or not.
        val size = to.length()
        val whole = total <= 0 || size >= total
        val got = runCatching {
            to.inputStream().use { inp ->
                val md = MessageDigest.getInstance("SHA-256")
                val b = ByteArray(64 * 1024)
                while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }.getOrNull()
        val same = r.size <= 0 || size == r.size
        val badChecksum = same && r.sha256 != null && got != r.sha256
        if (!whole || badChecksum || !looksLikeApk(to)) {
            // A wrong checksum means the bytes are stale or spliced: drop them so the next try fetches afresh.
            if (badChecksum) to.delete()
            throw IOException("The update didn't download properly. Try again.")
        }
        return tag
    }

    /** An APK is a zip: it starts with "PK". A web page or an error sent instead does not. */
    private fun looksLikeApk(f: File): Boolean = runCatching { f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() } }.getOrDefault(false)

    /** The first time, Android stops to ask for leave to install from this app, then carries on by itself. */
    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

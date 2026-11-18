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
            // Asked for from Settings: shown even if it was put off with "Not now".
            _hidden.value = !force && Kv.global(SKIPPED) == found.version
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
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return fetchOnce(r, to)
            } catch (e: java.net.UnknownHostException) {
                last = e
            } catch (e: java.net.SocketTimeoutException) {
                last = e
            } catch (e: java.net.ConnectException) {
                last = e
            }
            _progress.value = 0f
            kotlinx.coroutines.delay(2_000L * (attempt + 1))
        }
        throw IOException(if (last is java.net.UnknownHostException) "Couldn't reach GitHub — check the connection (or a VPN / DNS blocker) and tap Update again" else "The download kept stalling — tap Update to try again", last)
    }

    private suspend fun fetchOnce(r: AppRelease, to: File) {
        val sha = MessageDigest.getInstance("SHA-256")
        var done = 0L
        // Timed out, not left blocked in a read: nothing could cancel that, and every retry found it still running.
        val conn = URL(r.url).openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
        // What the server says it is sending: a newer build may have replaced the file since the card read its size.
        val length = conn.contentLengthLong
        val expected = if (length > 0) length else r.size
        conn.getInputStream().use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    sha.update(buffer, 0, n)
                    done += n
                    if (expected > 0) _progress.value = done.toFloat() / expected
                }
            }
        }
        val got = sha.digest().joinToString("") { "%02x".format(it) }
        // Cut short: what arrived isn't all the server sent. The checksum is only the card's file's: when a newer build
        // replaced it mid-way (every push publishes over "phone-latest"), the newer, whole file is taken instead.
        val whole = expected <= 0 || done == expected
        val same = r.size <= 0 || done == r.size
        if (!whole || same && r.sha256 != null && got != r.sha256 || !looksLikeApk(to)) throw IOException("The update didn't download properly. Try again.")
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

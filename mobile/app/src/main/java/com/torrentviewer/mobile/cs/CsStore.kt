package com.torrentviewer.mobile.cs

import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Net
import com.torrentviewer.core.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The repositories added and the plugins installed, for every profile alike: plugins are code in the
 * app, not a viewer's preference. Kept as JSON in [Kv]'s global keys, beside the Stremio addons.
 */
object CsStore {
    private const val REPOS = "cs-repos"
    private const val INSTALLED = "cs-plugins"

    private val _repos = MutableStateFlow<List<RepositoryData>>(emptyList())
    val reposFlow: StateFlow<List<RepositoryData>> = _repos

    private val _installed = MutableStateFlow<List<PluginData>>(emptyList())
    val installedFlow: StateFlow<List<PluginData>> = _installed

    /** After [Kv.init]. */
    fun load() {
        _repos.value = runCatching { JSONArray(Kv.global(REPOS) ?: "[]").objects().mapNotNull(::repositoryData) }.getOrDefault(emptyList())
        _installed.value = runCatching { JSONArray(Kv.global(INSTALLED) ?: "[]").objects().mapNotNull(::pluginData) }.getOrDefault(emptyList())
    }

    fun repos(): List<RepositoryData> = _repos.value

    fun installed(): List<PluginData> = _installed.value

    @Synchronized
    fun saveRepos(next: List<RepositoryData>) {
        _repos.value = next
        Kv.setGlobal(REPOS, JSONArray(next.map { it.toJson() }).toString())
    }

    @Synchronized
    fun saveInstalled(next: List<PluginData>) {
        _installed.value = next
        Kv.setGlobal(INSTALLED, JSONArray(next.map { it.toJson() }).toString())
    }
}

/** A repo.json or plugins.json. Quiet: a repository being down isn't the phone being offline. */
suspend fun fetchText(url: String): String = Net.get(url, 20_000, quiet = true)

/** Where a shortcode such as "phisherrepo" leads, without following the redirect; null for no such code. */
suspend fun resolveShortcode(code: String): String? = withContext(Dispatchers.IO) {
    val lookup = shortcodeLookup(code)
    val conn = (URL(lookup).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
    }
    try {
        conn.responseCode
        shortcodeTarget(lookup, conn.getHeaderField("Location"))
    } finally {
        conn.disconnect()
    }
}

/** Downloads [url] to [file] through a temporary file, so a failed download never leaves half a plugin. */
suspend fun download(url: String, file: File, temp: File) = withContext(Dispatchers.IO) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "Mozilla/5.0")
    }
    try {
        if (conn.responseCode !in 200..299) throw IOException("${URL(url).host} answered ${conn.responseCode}")
        temp.parentFile?.mkdirs()
        conn.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        file.parentFile?.mkdirs()
        // A loaded .cs3 is read-only: make room for the new one first.
        if (file.exists()) {
            file.setWritable(true)
            file.delete()
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    } finally {
        conn.disconnect()
    }
}

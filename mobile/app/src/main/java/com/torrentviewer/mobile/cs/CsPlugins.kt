package com.torrentviewer.mobile.cs

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.torrentviewer.core.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.security.Security

/**
 * CloudStream plugins in this app: the repositories the viewer adds (a repo.json link, or a shortcode
 * such as "phisherrepo"), the .cs3 plugins installed from them, and loading those at launch. Each
 * plugin scrapes its site on the phone; its providers ([providers]) answer search, load and loadLinks,
 * which [CsStreams] turns into rows in a title's sources. The app ships with no repository: what is
 * added is the viewer's choice, as with Stremio addons.
 */
object CsPlugins {
    private const val TAG = "CsPlugins"

    val repos: StateFlow<List<RepositoryData>> get() = CsStore.reposFlow
    val installed: StateFlow<List<PluginData>> get() = CsStore.installedFlow

    /** Bumped whenever a plugin loads or unloads, so the screens re-read [providers] and [PluginManager.failures]. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** True until every installed plugin has had its go at loading after launch. */
    private val _starting = MutableStateFlow(true)
    val starting: StateFlow<Boolean> = _starting

    private lateinit var appContext: Context
    private val lock = Mutex()

    /** At launch, after Kv.init. Loads what is installed in the background. */
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        CloudStreamApp.context = appContext
        CsStore.load()
        // Old Androids' TLS fails on many sites; CloudStream bundles Conscrypt for that too.
        runCatching { Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1) }
        app.baseClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(Cache(File(appContext.cacheDir, "cs_http_cache"), 50L * 1024 * 1024))
            .build()
        AppScope.launch(Dispatchers.IO) {
            lock.withLock {
                for (p in CsStore.installed()) PluginManager.loadPlugin(appContext, File(p.filePath), p)
            }
            _starting.value = false
            _changes.value++
            runCatching { MainActivity.afterPluginsLoadedEvent.invoke(true) }
        }
    }

    /** The activity plugins show toasts and dialogs over. */
    fun attach(activity: Activity?) {
        CommonActivity.activity = activity
    }

    /** Every provider the loaded plugins registered. */
    fun providers(): List<MainAPI> = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }

    fun loaded(p: PluginData) = synchronized(PluginManager.plugins) { PluginManager.plugins.containsKey(File(p.filePath).absolutePath) }

    fun failure(p: PluginData): String? = PluginManager.failures[File(p.filePath).absolutePath]

    /**
     * Adds a repository by its link or shortcode and returns it; one already added is refreshed in
     * place. Throws with a message to show.
     */
    suspend fun addRepo(raw: String): RepositoryData {
        val input = repoInput(raw) ?: throw IllegalArgumentException("That isn't a repository link or shortcode")
        val url = when (input) {
            is RepoInput.Link -> input.url
            // As typed, then in lower case: a phone keyboard capitalises the first letter, and the shortener is case-sensitive.
            is RepoInput.Shortcode -> {
                // Unreachable is not "no such code": some networks block link shorteners, and the fix is different.
                var unreachable = false
                listOf(input.code, input.code.lowercase()).distinct().firstNotNullOfOrNull { code ->
                    runCatching { resolveShortcode(code) }.onFailure { unreachable = true }.getOrNull()
                } ?: throw IllegalArgumentException(
                    if (unreachable) "Couldn't reach the shortcode service (${shortcodeLookup(input.code).substringAfter("://").substringBefore('/')}) — your network may block it. Paste the repository's link instead."
                    else "No repository is called \"${input.code}\" — check the spelling, or paste the repository's link instead",
                )
            }
        }
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val text = try {
            fetchText(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Couldn't reach $host — check your connection, or that the link is right")
        }
        val repo = parseRepository(text) ?: throw IllegalArgumentException("$host answered, but not with a repository (repo.json)")
        val data = RepositoryData(repo.iconUrl, repo.name, url, repo.description)
        lock.withLock {
            val now = CsStore.repos()
            CsStore.saveRepos(if (now.any { it.url == url }) now.map { if (it.url == url) data else it } else now + data)
        }
        return data
    }

    /** Removes the repository and every plugin installed from it. */
    suspend fun removeRepo(repo: RepositoryData) {
        lock.withLock {
            val folder = File(appContext.filesDir, "${PluginManager.ONLINE_PLUGINS_FOLDER}/${PluginManager.getPluginSanitizedFileName(repo.url)}")
            val gone = CsStore.installed().filter { File(it.filePath).absolutePath.startsWith(folder.absolutePath) }
            gone.forEach { PluginManager.unloadPlugin(File(it.filePath).absolutePath) }
            CsStore.saveInstalled(CsStore.installed() - gone.toSet())
            CsStore.saveRepos(CsStore.repos().filter { it.url != repo.url })
            withContext(Dispatchers.IO) { folder.deleteRecursively() }
        }
        _changes.value++
    }

    /** What a repository offers; throws with a message when it can't be read. */
    suspend fun offered(repo: RepositoryData): List<PluginWrapper> =
        RepositoryManager.getRepoPlugins(repo) ?: throw IllegalArgumentException("Couldn't reach ${repo.name}")

    fun installedOf(w: PluginWrapper): PluginData? = CsStore.installed().find { it.url == w.plugin.url }

    /** Downloads and loads one plugin, or updates it in place. Throws with a message to show. */
    suspend fun install(w: PluginWrapper) {
        lock.withLock {
            val file = PluginManager.getPluginPath(appContext, w.plugin.internalName, w.repositoryData.url)
            val path = file.absolutePath
            // An update: the old code goes before the new file replaces it.
            PluginManager.unloadPlugin(path)
            try {
                download(w.plugin.url, file, File(appContext.cacheDir, "${file.name}.tmp"))
            } catch (e: Exception) {
                throw IllegalArgumentException("Couldn't download ${w.plugin.name}: ${e.message}")
            }
            val data = PluginData(w.plugin.internalName, w.plugin.url, true, path, w.plugin.version)
            CsStore.saveInstalled(CsStore.installed().filter { it.filePath != path } + data)
            val ok = withContext(Dispatchers.IO) { PluginManager.loadPlugin(appContext, file, data) }
            _changes.value++
            if (!ok) throw IllegalArgumentException("${w.plugin.name} installed but didn't start: ${PluginManager.failures[path]}")
        }
    }

    /** Unloads it and deletes its file. */
    suspend fun uninstall(p: PluginData) {
        lock.withLock {
            val file = File(p.filePath)
            PluginManager.unloadPlugin(file.absolutePath)
            PluginManager.failures.remove(file.absolutePath)
            CsStore.saveInstalled(CsStore.installed().filter { it.filePath != p.filePath })
            withContext(Dispatchers.IO) {
                file.setWritable(true)
                file.delete()
            }
        }
        _changes.value++
        Log.i(TAG, "Removed ${p.internalName}")
    }
}

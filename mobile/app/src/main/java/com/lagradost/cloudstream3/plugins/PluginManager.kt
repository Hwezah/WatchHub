package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.extractorApis
import com.torrentviewer.mobile.cs.CsStore
import com.torrentviewer.mobile.cs.readManifest
import com.torrentviewer.mobile.cs.sanitizeFilename
import dalvik.system.PathClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A plugin installed on this phone (compat; see CloudStreamApp.kt): [url] is the .cs3 it came from, [filePath] where it is. */
data class PluginData(
    val internalName: String,
    val url: String?,
    val isOnline: Boolean,
    val filePath: String,
    val version: Int,
)

/**
 * Loads and unloads .cs3 plugins, as CloudStream's PluginManager does: each file gets a
 * [PathClassLoader] whose parent is the app's, its manifest.json names the class to start, and that
 * class registers its providers ([APIHolder.allProviders]) and extractors ([extractorApis]) in load().
 * A plugin is code from whoever wrote it, run with this app's permissions: only repositories the
 * viewer added are ever installed from. The public members are the ones plugins call.
 */
object PluginManager {
    private const val TAG = "PluginManager"

    /** Under filesDir, as upstream: [getPluginPath] is how some plugins tell what is installed. */
    const val ONLINE_PLUGINS_FOLDER = "Extensions"

    /** Loaded plugins by file path. */
    val plugins: MutableMap<String, BasePlugin> = LinkedHashMap()
    private val classLoaders = HashMap<PathClassLoader, BasePlugin>()

    /** Why a plugin didn't load, by file path, for the plugins page. */
    val failures: MutableMap<String, String> = ConcurrentHashMap()

    fun getPluginsOnline(): Array<PluginData> = CsStore.installed().toTypedArray()

    fun getPluginsLocal(): Array<PluginData> = emptyArray()

    fun getPluginSanitizedFileName(name: String): String = sanitizeFilename(name, true) + "." + name.hashCode()

    /** Where a repository's plugin lives. Upstream says this must not change: plugins use it to see what is installed. */
    fun getPluginPath(context: Context, internalName: String, repositoryUrl: String): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl)
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/$ONLINE_PLUGINS_FOLDER/$folderName/$fileName.cs3")
    }

    /** Loads an installed plugin by its provider's name ("Provider" left off, as upstream). */
    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean =
        getPluginsOnline().firstOrNull { it.internalName.replace("provider", "", ignoreCase = true) == apiName }
            ?.let { loadPlugin(context, File(it.filePath), it) } ?: false

    /** True when the plugin is running afterwards. Never throws: a broken plugin is noted in [failures]. */
    fun loadPlugin(context: Context, file: File, data: PluginData): Boolean {
        val path = file.absolutePath
        synchronized(plugins) { if (plugins.containsKey(path)) return true }
        return try {
            // Android 14 refuses to load a writable dex file.
            if (!file.setReadOnly()) Log.w(TAG, "Couldn't make ${file.name} read-only")
            val loader = PathClassLoader(path, context.classLoader)
            val manifest = loader.getResourceAsStream("manifest.json")?.use { readManifest(it.reader().readText()) }
                ?: error("no manifest.json inside")
            val className = manifest.pluginClassName ?: error("the manifest names no plugin class")
            val instance = loader.loadClass(className).getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = path
            if (manifest.requiresResources) {
                // A plugin's own layouts and strings, from the .cs3 itself.
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, path)
                @Suppress("DEPRECATION")
                (instance as? Plugin)?.resources = Resources(assets, context.resources.displayMetrics, context.resources.configuration)
            }
            synchronized(plugins) { plugins[path] = instance }
            synchronized(classLoaders) { classLoaders[loader] = instance }
            if (instance is Plugin) instance.load(context) else instance.load()
            failures.remove(path)
            Log.i(TAG, "Loaded ${data.internalName} (${manifest.name}, version ${manifest.version})")
            true
        } catch (e: Throwable) {
            // Throwable: a plugin built for a newer CloudStream fails with NoClassDefFoundError / NoSuchMethodError.
            Log.e(TAG, "Couldn't load ${data.internalName}", e)
            failures[path] = "${e::class.java.simpleName}: ${e.message ?: "failed to load"}"
            unloadPlugin(path)
            false
        }
    }

    /** Takes the plugin's providers and extractors out of use and forgets it. The file stays. */
    fun unloadPlugin(absolutePath: String) {
        val plugin = synchronized(plugins) { plugins[absolutePath] } ?: return
        runCatching { plugin.beforeUnload() }.onFailure { Log.w(TAG, "beforeUnload: ${it.message}") }
        APIHolder.apis.filter { it.sourcePlugin == plugin.filename }.forEach { APIHolder.removePluginMapping(it) }
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == plugin.filename } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == plugin.filename } }
        synchronized(classLoaders) { classLoaders.values.removeAll { it == plugin } }
        synchronized(plugins) { plugins.remove(absolutePath) }
    }
}

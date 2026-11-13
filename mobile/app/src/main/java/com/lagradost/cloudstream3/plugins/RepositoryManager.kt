package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.torrentviewer.mobile.cs.CsStore
import com.torrentviewer.mobile.cs.fetchText
import com.torrentviewer.mobile.cs.parseRepository
import com.torrentviewer.mobile.cs.parseSitePlugins
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** A repo.json: its name and the plugins.json lists it points at (compat; see CloudStreamApp.kt). */
data class Repository(
    val iconUrl: String?,
    val name: String,
    val description: String?,
    val manifestVersion: Int,
    val pluginLists: List<String>,
)

/**
 * One entry of a plugins.json. [status] 0 is down, 1 ok, 2 slow, 3 beta only; a change in
 * [version] is an update. [url] is the .cs3 file.
 */
data class SitePlugin(
    val url: String,
    val status: Int,
    val version: Int,
    val apiVersion: Int,
    val name: String,
    val internalName: String,
    val authors: List<String>,
    val description: String?,
    val repositoryUrl: String?,
    val tvTypes: List<String>?,
    val language: String?,
    val iconUrl: String?,
    val fileSize: Long?,
    val fileHash: String? = null,
)

/** A plugin offered by a repository, with the repository it came from. */
data class PluginWrapper(
    val repository: Repository,
    val repositoryData: RepositoryData,
    val plugin: SitePlugin,
)

/**
 * The repositories the viewer added, and what each offers (compat; see CloudStreamApp.kt). Only what
 * plugins call is here; adding and removing repositories is [com.torrentviewer.mobile.cs.CsPlugins]'s.
 */
object RepositoryManager {
    fun getRepositories(): Array<RepositoryData> = CsStore.repos().toTypedArray()

    // The top-level parser by its full name: a bare parseRepository(…) here is this very function, which
    // took the JSON for a link and failed every Browse with "Couldn't reach".
    suspend fun parseRepository(url: String): Repository? = runCatching { com.torrentviewer.mobile.cs.parseRepository(fetchText(url)) }.getOrNull()

    /** Every plugin a repository lists, across its plugin lists; null when the repository can't be read. */
    suspend fun getRepoPlugins(repositoryData: RepositoryData): List<PluginWrapper>? {
        val repo = parseRepository(repositoryData.url) ?: return null
        return coroutineScope {
            repo.pluginLists.map { list ->
                async { runCatching { parseSitePlugins(fetchText(list)) }.getOrDefault(emptyList()).map { PluginWrapper(repo, repositoryData, it) } }
            }.awaitAll().flatten()
        }
    }
}

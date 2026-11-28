package com.torrentviewer.mobile.cs

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.torrentviewer.core.objects
import com.torrentviewer.core.str
import com.torrentviewer.core.strings
import org.json.JSONArray
import org.json.JSONObject

/*
 * The pure half of the CloudStream plugin host: reading a repository's JSON and deciding what a
 * typed-in repository means. No Android and no network here, so it is tested on the JVM.
 */

/** What the viewer typed to add a repository. */
sealed interface RepoInput {
    /** A repo.json link, ready to fetch. */
    data class Link(val url: String) : RepoInput

    /** A short name such as "phisherrepo", which CloudStream resolves through a link shortener. */
    data class Shortcode(val code: String) : RepoInput
}

private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
private val CS_LINK = Regex("^(cloudstreamrepo://|https://cs\\.repo/\\??)", RegexOption.IGNORE_CASE)
private val SHORTCODE = Regex("^[a-zA-Z0-9!_-]+$")

/**
 * Null when it is neither a link nor a shortcode. CloudStream's RepositoryManager.parseRepoUrl rules,
 * except that a cs.repo wrapper is unwrapped before a plain link is taken as it is.
 */
fun repoInput(raw: String): RepoInput? {
    val s = raw.trim()
    return when {
        CS_LINK.containsMatchIn(s) -> s.replace(CS_LINK, "").let { RepoInput.Link(if (SCHEME.containsMatchIn(it)) it else "https://$it") }
        SCHEME.containsMatchIn(s) -> RepoInput.Link(s)
        SHORTCODE.matches(s) -> RepoInput.Shortcode(s)
        else -> null
    }
}

/** Where a shortcode is looked up: "!code" on py.md, any other on cutt.ly. Both answer with a redirect. */
fun shortcodeLookup(code: String): String =
    if (code.startsWith("!")) "https://py.md/${code.removePrefix("!")}" else "https://cutt.ly/$code"

/** The redirect a shortener answered with, or null when it means "no such code". */
fun shortcodeTarget(lookup: String, location: String?): String? {
    val to = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val host = lookup.substringBefore("://") + "://" + lookup.substringAfter("://").substringBefore('/')
    if (to.startsWith("$host/404") || to.removeSuffix("/") == host) return null
    return to
}

/** A shortener's page that shows the link rather than redirecting: the first repository-looking .json link in it. */
fun shortcodeInPage(html: String): String? =
    Regex("""https?://[^\s"'<>]+?\.json""").findAll(html).map { it.value }.firstOrNull { !it.contains("cutt.ly") && !it.contains("py.md") }

/** A repo.json; null when it isn't one (no name, or no plugin lists). */
fun parseRepository(json: String): Repository? = runCatching {
    val o = JSONObject(json)
    val name = o.str("name") ?: return null
    val lists = o.strings("pluginLists").filter { SCHEME.containsMatchIn(it) }
    if (lists.isEmpty()) return null
    Repository(o.str("iconUrl"), name, o.str("description"), o.optInt("manifestVersion", 1), lists)
}.getOrNull()

/** A plugins.json: every entry with a .cs3 link and an internal name; anything else is skipped. */
fun parseSitePlugins(json: String): List<SitePlugin> = runCatching {
    JSONArray(json).objects().mapNotNull { p ->
        val url = p.str("url")?.takeIf { SCHEME.containsMatchIn(it) } ?: return@mapNotNull null
        val internal = p.str("internalName") ?: p.str("name") ?: return@mapNotNull null
        SitePlugin(
            url = url,
            status = p.optInt("status", 1),
            version = p.optInt("version", 1),
            apiVersion = p.optInt("apiVersion", 1),
            name = p.str("name") ?: internal,
            internalName = internal,
            authors = p.strings("authors"),
            description = p.str("description"),
            repositoryUrl = p.str("repositoryUrl"),
            tvTypes = if (p.has("tvTypes")) p.strings("tvTypes") else null,
            language = p.str("language"),
            iconUrl = p.str("iconUrl"),
            fileSize = p.optLong("fileSize", -1).takeIf { it >= 0 },
            fileHash = p.str("fileHash"),
        )
    }
}.getOrDefault(emptyList())

/** The manifest.json inside a .cs3. */
fun readManifest(json: String): BasePlugin.Manifest {
    val o = JSONObject(json)
    return BasePlugin.Manifest().apply {
        name = o.str("name")
        pluginClassName = o.str("pluginClassName")
        requiresResources = o.optBoolean("requiresResources")
        version = if (o.has("version")) o.optInt("version") else null
    }
}

/** CloudStream's file-name cleaning, kept exact: plugin paths are built from it. */
fun sanitizeFilename(name: String, removeSpaces: Boolean = false): String {
    var temp = name
    for (c in "|\\?*<\":>+[]/'") temp = temp.replace(c, ' ')
    if (removeSpaces) temp = temp.replace(" ", "")
    return temp.replace("  ", " ").trim(' ')
}

/** Plugins whose version in the repository is newer than the one installed. */
fun outdated(installed: List<PluginData>, offered: List<SitePlugin>): List<SitePlugin> =
    offered.filter { o -> installed.any { it.url == o.url && o.version > it.version } }

/* ------------------------------------------------------------- stored as JSON */

fun RepositoryData.toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("iconUrl", iconUrl).put("description", description)

fun repositoryData(o: JSONObject): RepositoryData? {
    val url = o.str("url") ?: return null
    return RepositoryData(o.str("iconUrl"), o.str("name") ?: url, url, o.str("description"))
}

fun PluginData.toJson(): JSONObject = JSONObject().put("internalName", internalName).put("url", url)
    .put("isOnline", isOnline).put("filePath", filePath).put("version", version)

fun pluginData(o: JSONObject): PluginData? {
    val name = o.str("internalName") ?: return null
    val path = o.str("filePath") ?: return null
    return PluginData(name, o.str("url"), o.optBoolean("isOnline", true), path, o.optInt("version", 0))
}

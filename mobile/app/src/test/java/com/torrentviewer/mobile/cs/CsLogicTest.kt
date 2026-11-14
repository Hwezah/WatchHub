package com.torrentviewer.mobile.cs

import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsLogicTest {
    @Test fun repoInputKinds() {
        assertEquals(RepoInput.Shortcode("phisherrepo"), repoInput("  phisherrepo "))
        assertEquals(RepoInput.Shortcode("!abc"), repoInput("!abc"))
        assertEquals(RepoInput.Link("https://x.io/repo.json"), repoInput("https://x.io/repo.json"))
        assertEquals(RepoInput.Link("https://x.io/repo.json"), repoInput("cloudstreamrepo://x.io/repo.json"))
        assertEquals(RepoInput.Link("https://x.io/repo.json"), repoInput("https://cs.repo/?https://x.io/repo.json"))
        assertNull(repoInput("not a repo"))
        assertNull(repoInput(""))
    }

    @Test fun shortcodes() {
        assertEquals("https://cutt.ly/phisherrepo", shortcodeLookup("phisherrepo"))
        assertEquals("https://py.md/abc", shortcodeLookup("!abc"))
        val to = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json"
        assertEquals(to, shortcodeTarget("https://cutt.ly/phisherrepo", to))
        // The shortener's own "no such link" answers.
        assertNull(shortcodeTarget("https://cutt.ly/nope", "https://cutt.ly/404"))
        assertNull(shortcodeTarget("https://cutt.ly/nope", "https://cutt.ly/"))
        assertNull(shortcodeTarget("https://py.md/nope", "https://py.md/404?x"))
        assertNull(shortcodeTarget("https://cutt.ly/nope", null))
    }

    /** phisher98's repo.json as published. */
    @Test fun repository() {
        val repo = parseRepository(
            """{"name":"Phisher Repo","iconUrl":"https://x/i.png","description":"Phisher Repository","manifestVersion":1,
              "pluginLists":["https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json"]}""",
        )!!
        assertEquals("Phisher Repo", repo.name)
        assertEquals(1, repo.pluginLists.size)
        assertNull(parseRepository("""{"name":"x","pluginLists":[]}"""))
        assertNull(parseRepository("""{"pluginLists":["https://a/b.json"]}"""))
        assertNull(parseRepository("<html>"))
    }

    /** An entry of phisher98's plugins.json, and the shapes to skip. */
    @Test fun plugins() {
        val list = parseSitePlugins(
            """[{"url":"https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/AllMovieLandProvider.cs3",
                 "status":1,"version":25,"name":"AllMovieLandProvider","internalName":"AllMovieLandProvider","authors":["Phisher98"],
                 "description":"Indian MultiLanguage Provider (Mostly Hindi)","fileSize":87117,"language":"hi",
                 "tvTypes":["Movie","TvSeries","Cartoon"],"iconUrl":null,"apiVersion":1},
                {"name":"no link"},
                {"url":"ftp://x/y.cs3","internalName":"Bad"}]""",
        )
        assertEquals(1, list.size)
        val p = list.single()
        assertEquals(25, p.version)
        assertEquals("hi", p.language)
        assertEquals(listOf("Movie", "TvSeries", "Cartoon"), p.tvTypes)
        assertEquals(87117L, p.fileSize)
        assertNull(p.iconUrl)
        assertTrue(parseSitePlugins("{}").isEmpty())
    }

    @Test fun manifest() {
        val m = readManifest("""{"name":"Anichi","pluginClassName":"com.phisher98.AnichiPlugin","version":12,"requiresResources":true}""")
        assertEquals("com.phisher98.AnichiPlugin", m.pluginClassName)
        assertEquals(12, m.version)
        assertTrue(m.requiresResources)
        assertNull(readManifest("""{"name":"x"}""").version)
    }

    /** Must match CloudStream's, character for character: plugins find each other's files by it. */
    @Test fun fileNames() {
        assertEquals("AllMovieLandProvider", sanitizeFilename("AllMovieLandProvider", true))
        // Three separators become three spaces, and one pass of "  " → " " leaves two.
        assertEquals("https  rawgithubusercontentcom", sanitizeFilename("https://rawgithubusercontentcom"))
        assertEquals("https//rawgithubusercontentcom".replace("/", ""), sanitizeFilename("https://rawgithubusercontentcom", true))
        assertEquals("a b", sanitizeFilename("a|b"))
    }

    @Test fun updates() {
        val installed = listOf(PluginData("A", "https://r/A.cs3", true, "/f/A.cs3", 3))
        val offered = parseSitePlugins("""[{"url":"https://r/A.cs3","internalName":"A","version":4},{"url":"https://r/B.cs3","internalName":"B","version":9}]""")
        assertEquals(listOf("A"), outdated(installed, offered).map { it.internalName })
        assertTrue(outdated(installed.map { it.copy(version = 4) }, offered).isEmpty())
    }

    @Test fun storedJsonRoundTrips() {
        val repo = RepositoryData("https://x/i.png", "Phisher Repo", "https://x/repo.json", null)
        assertEquals(repo, repositoryData(repo.toJson()))
        val p = PluginData("A", "https://r/A.cs3", true, "/f/A.cs3", 3)
        assertEquals(p, pluginData(p.toJson()))
    }
}

class ShortcodePageTest {
    @org.junit.Test fun linkInAPage() {
        val page = """<html><a href="https://cutt.ly/x.json">x</a> Go to <a href="https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json">repo</a></html>"""
        org.junit.Assert.assertEquals("https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json", shortcodeInPage(page))
        org.junit.Assert.assertNull(shortcodeInPage("<html>Not found</html>"))
    }
}

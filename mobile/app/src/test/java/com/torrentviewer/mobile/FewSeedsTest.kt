package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which torrents the sources lists leave out by default. */
class FewSeedsTest {
    private fun torrent(seeders: Int, id: String = "abc") = Hit(id, "Show.S01E01.720p", "magnet:?xt=urn:btih:$id", seeders, 0, 0, "720p", "tv", Scope.EPISODE, null)

    @Test fun underTenSeedsIsLeftOut() {
        assertTrue(fewSeeds(torrent(0)))
        assertTrue(fewSeeds(torrent(9)))
        assertFalse(fewSeeds(torrent(10)))
    }

    @Test fun cachedOnTorBoxOrADirectLinkIsKept() {
        assertFalse(fewSeeds(torrent(1, "ABC"), setOf("abc")))
        val link = Hit("l", "Link 720p", "https://host.example/file.mkv", 0, 0, 0, "720p", "tv", Scope.EPISODE, null)
        assertFalse(fewSeeds(link))
    }
}

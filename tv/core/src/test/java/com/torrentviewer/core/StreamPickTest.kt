package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Play's preferred streaming resolution. */
class StreamPickTest {
    private fun hit(id: String, quality: String?) = Hit(id, id, "https://x/$id", 0, 0, 0, quality, "movie", Scope.EPISODE, null)

    @Test fun autoFollowsTheConnection() {
        assertEquals("1080p", streamTarget("", onMobileData = false))
        assertEquals("720p", streamTarget("", onMobileData = true))
        assertEquals("480p", streamTarget("480p", onMobileData = false))
    }

    @Test fun resolutions() {
        assertEquals(2160, resolutionOf("4K"))
        assertEquals(2160, resolutionOf("2160p"))
        assertEquals(720, resolutionOf("720p"))
        assertNull(resolutionOf("CAM"))
        assertNull(resolutionOf(null))
    }

    @Test fun theWantedResolutionThenNearestBelowThenAbove() {
        val hits = listOf(hit("a", "1080p"), hit("b", null), hit("c", "480p"), hit("d", "720p"), hit("e", "4K"), hit("f", "720p"), hit("g", "360p"))
        assertEquals(listOf("d", "f", "c", "g", "a", "e", "b"), streamOrder(hits, "720p").map { it.id })
        // Nothing at 4K: the best below it first.
        assertEquals(listOf("a", "d", "f", "c", "g", "b"), streamOrder(hits.filter { it.id != "e" }, "4K").map { it.id })
        assertEquals(hits, streamOrder(hits, "any"))
    }

    @Test fun saysWhenItPlaysAnother() {
        assertNull(streamNote(hit("a", "720p"), "720p"))
        assertEquals("No 720p source — playing 1080p", streamNote(hit("a", "1080p"), "720p"))
        assertNull(streamNote(hit("a", null), "720p"))
    }

    /** The player's Sources panel: links first, then the higher resolution, a link's lighter file, a torrent's bigger swarm. */
    @Test fun sourcesPanelOrder() {
        val link720 = Hit("l1", "a", "https://x/l1", 0, 0, 394, "720p", "tv", Scope.EPISODE, null)
        val link720big = link720.copy(id = "l2", uri = "https://x/l2", size = 515)
        val link720unknown = link720.copy(id = "l3", uri = "https://x/l3", size = 0)
        val link1080 = link720.copy(id = "l4", uri = "https://x/l4", quality = "1080p", size = 900)
        val torrent720 = Hit("t1", "t", "magnet:?xt=urn:btih:t1", 50, 0, 300, "720p", "tv", Scope.EPISODE, null)
        val torrent720few = torrent720.copy(id = "t2", uri = "magnet:?xt=urn:btih:t2", seeders = 5)
        val youtube = link720.copy(id = "y", uri = "https://youtube.com/x", external = true)
        val all = listOf(torrent720few, link720unknown, youtube, link720big, torrent720, link1080, link720)
        assertEquals(listOf("l1", "l2", "l3", "t1", "t2"), sourceOrder(all, "720p").map { it.id })
        assertEquals(listOf("l4", "l1", "l2", "l3", "t1", "t2"), sourceOrder(all, null).map { it.id })
        assertEquals(listOf("l4", "l1", "l2", "l3", "t1", "t2"), sourceOrder(all, "").map { it.id })
        assertEquals(listOf("4K", "1080p", "720p"), sourceResolutions(all + link720.copy(id = "k", quality = "4K") + link720.copy(id = "k2", quality = "2160p") + link720.copy(id = "c", quality = "CAM")))
    }

    @Test fun downloadOnlySources() {
        assertEquals(true, downloadOnly(hit("[10Gbps Download Only] [350MB] Film.720p.mkv", "720p")))
        assertEquals(true, downloadOnly(hit("Film Direct-Download 720p", "720p")))
        assertEquals(false, downloadOnly(hit("MoviesDrive GDFlix [Instant Download] 720p", "720p")))
    }

    @Test fun copiesOfOneFileFold() {
        val mb = 1024L * 1024
        fun link(id: String, q: String, size: Long) = Hit(id, id, "https://x/$id", 0, 0, size, q, "movie", Scope.EPISODE, null)
        val hub1 = link("hub-imax", "720p", 1434 * mb)
        val hub2 = link("hub-ds4k", "720p", 1433 * mb)
        val drive = link("drive", "720p", 1464 * mb)
        val other1080 = link("hub-1080", "1080p", 1434 * mb)
        val torrent = Hit("t", "t", "magnet:?xt=urn:btih:t", 30, 0, 1434 * mb, "720p", "movie", Scope.EPISODE, null)
        val tiny = link("tiny", "720p", 20 * mb)
        val tiny2 = link("tiny2", "720p", 20 * mb)
        val folded = foldCopies(listOf(hub1, drive, hub2, other1080, torrent, tiny, tiny2))
        assertEquals(listOf("hub-imax", "drive", "hub-1080", "t", "tiny", "tiny2"), folded.map { it.first.id })
        assertEquals(listOf("hub-imax", "hub-ds4k"), folded.first().second.map { it.id })
        // The copy shown: the one ranked highest (a trusted site).
        assertEquals("hub-ds4k", foldCopies(listOf(hub1, hub2)) { if (it.id == "hub-ds4k") 3 else 0 }.first().first.id)
    }

    @Test fun tubeOpensAtTheChosenHeight() {
        val offered = listOf(144, 360, 720, 1080)
        assertEquals(720, tubeHeight(offered, "720p"))
        assertEquals(360, tubeHeight(offered, "480p"))
        assertEquals(144, tubeHeight(listOf(144, 1080), "360p"))
        assertEquals(1080, tubeHeight(listOf(1080, 1440), "720p"))
        assertNull(tubeHeight(offered, ""))
    }
}

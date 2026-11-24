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
}

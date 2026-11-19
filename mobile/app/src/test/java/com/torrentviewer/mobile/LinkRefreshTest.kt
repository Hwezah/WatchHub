package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkRefreshTest {
    private val mb = 1024L * 1024

    private fun link(name: String, addon: String, quality: String?, uri: String, size: Long = 0) =
        Hit(uri, name, uri, 0, 0, size, quality, "movie", Scope.EPISODE, null, addon = addon)

    private val old = "https://hubcloud.example/old?token=1"

    @Test fun sameSourceAndNameFirst() {
        val hits = listOf(
            link("HubCloud 1080p", "HDhub4u", "1080p", old),
            link("Pixeldrain 1080p", "HDhub4u", "1080p", "https://pd.example/new"),
            link("HubCloud 1080p", "HDhub4u", "1080p", "https://hubcloud.example/new?token=2"),
            link("Other 1080p", "MoviesMod", "1080p", "https://mm.example/x.mp4"),
        )
        assertEquals("https://hubcloud.example/new?token=2", refreshPick(hits, old, "HDhub4u", "HubCloud 1080p", "1080p")!!.uri)
    }

    @Test fun thenSameSourceAtTheResolutionThenAnySource() {
        val hits = listOf(
            link("Pixeldrain 720p", "HDhub4u", "720p", "https://pd.example/720"),
            link("Pixeldrain 1080p", "HDhub4u", "1080p", "https://pd.example/1080"),
            link("Other 1080p", "MoviesMod", "1080p", "https://mm.example/1080.mp4", 900 * mb),
        )
        assertEquals("https://pd.example/1080", refreshPick(hits, old, "HDhub4u", "HubCloud 1080p", "1080p")!!.uri)
        assertEquals("https://mm.example/1080.mp4", refreshPick(hits.drop(1).drop(1), old, "HDhub4u", "HubCloud 1080p", "1080p")!!.uri)
    }

    @Test fun neverTheDeadLinkAgain() {
        assertNull(refreshPick(listOf(link("HubCloud 1080p", "HDhub4u", "1080p", old)), old, "HDhub4u", "HubCloud 1080p", "1080p"))
    }

    @Test fun eachRetryMovesOnPastEveryLinkThatFailed() {
        val hits = listOf(
            link("Pixeldrain 1080p", "HDhub4u", "1080p", "https://pd.example/1080"),
            link("Other 1080p", "MoviesMod", "1080p", "https://mm.example/1080.mp4", 900 * mb),
        )
        assertEquals("https://mm.example/1080.mp4", refreshPick(hits, old, "HDhub4u", "HubCloud 1080p", "1080p", setOf("https://pd.example/1080"))!!.uri)
        assertNull(refreshPick(hits, old, "HDhub4u", "HubCloud 1080p", "1080p", setOf("https://pd.example/1080", "https://mm.example/1080.mp4")))
    }

    /** From S01E05: a 449 MB pick whose link died isn't swapped for a 1.67 GB torrent while 282–436 MB links exist. */
    @Test fun aboutTheSameSize() {
        val torrent = Hit("t", "From.S01E05.720p.x265.mkv", "magnet:?xt=urn:btih:t", 60, 0, 1710 * mb, "720p", "tv", Scope.EPISODE, null, addon = "Torrentio")
        val x264 = link("Moviesmod Driveseed [X264] [436.31MB]", "StreamPlay", "720p", "https://mm.example/436.mkv", 436 * mb)
        val x265 = link("Moviesmod Driveseed [X265] [282.21MB]", "StreamPlay", "720p", "https://mm.example/282.mkv", 282 * mb)
        val pick = refreshPick(listOf(torrent, x264, x265), old, "HDhub4u", "HubCloud 720p", "720p", oldSize = 449 * mb)
        assertEquals("https://mm.example/282.mkv", pick!!.uri)
        // Only the big one left: still better than nothing.
        assertEquals(torrent, refreshPick(listOf(torrent), old, "HDhub4u", "HubCloud 720p", "720p", oldSize = 449 * mb))
    }

    /** "Different Source": a source turned down by hand stays out, even renewed under a new address. */
    @Test fun aRejectedSourceNeverComesBack() {
        val hits = listOf(
            link("HubCloud 720p", "HDhub4u", "720p", "https://hubcloud.example/new?token=3"),
            link("Other 720p", "MoviesMod", "720p", "https://mm.example/720.mkv"),
        )
        val pick = refreshPick(hits, old, "HDhub4u", "HubCloud 720p", "720p", rejected = setOf(rejectKey("HDhub4u", "HubCloud 720p")))
        assertEquals("https://mm.example/720.mkv", pick!!.uri)
    }

    /** A 1.2 GB film isn't swapped for a 300 MB copy while one of about its size is there. */
    @Test fun notFarSmallerEither() {
        val tiny = link("Tiny 720p", "A", "720p", "https://a.example/300.mkv", 300 * mb)
        val near = link("Near 720p", "B", "720p", "https://b.example/1000.mkv", 1000 * mb)
        assertEquals(near, refreshPick(listOf(tiny, near), old, null, null, "720p", oldSize = 1200 * mb))
        assertEquals(tiny, refreshPick(listOf(tiny), old, null, null, "720p", oldSize = 1200 * mb))
    }

    @Test fun aDownloadsTags() {
        assertEquals(listOf("720p", "MKV", "x265"), downloadTags("720p", "In.the.Grey.2025.720p.WEB.x265.mkv", "https://a.example/f", false))
        assertEquals(listOf("1080p", "MP4"), downloadTags("1080p", "Pixeldrain 1080p", "https://a.example/film.mp4?t=1", false))
        assertEquals(listOf("720p", "HLS"), downloadTags("720p", "Server 1", "https://a.example/master.m3u8", true))
    }
}

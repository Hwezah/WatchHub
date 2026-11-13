package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchPickTest {
    private val mb = 1024L * 1024

    private fun hit(name: String, quality: String?, size: Long, seeders: Int = 50, scope: Scope = Scope.EPISODE, uri: String = "magnet:?xt=urn:btih:$name", mime: String? = null) =
        Hit(name, name, uri, seeders, 0, size, quality, "tv", scope, null, mime = mime)

    /** The owner's example: two 720p releases, x264 and x265 — the x265 is kept, though it isn't the only small one. */
    @Test fun hevcFirstThenSmallest() {
        val hits = listOf(
            hit("Show.S01E01.720p.WEB.x264", "720p", 600 * mb),
            hit("Show.S01E01.720p.WEB.x265", "720p", 700 * mb),
            hit("Show.S01E01.720p.HEVC.small", "720p", 350 * mb),
            hit("Show.S01E01.1080p.x265", "1080p", 300 * mb),
        )
        assertEquals("Show.S01E01.720p.HEVC.small", batchPick(hits, "720p")!!.name)
        assertEquals("Show.S01E01.720p.WEB.x265", batchPick(hits.filter { "small" !in it.name }, "720p")!!.name)
        assertEquals("Show.S01E01.720p.WEB.x264", batchPick(hits.filter { "HEVC" !in it.name && "x265" !in it.name || "1080p" in it.name }, "720p")!!.name)
    }

    /** Trailers, samples, packs, DASH manifests and dead torrents are passed over; an HLS playlist is fine, after known sizes. */
    @Test fun skipsWhatIsNotAnEpisodeFile() {
        val trailer = hit("Show.S01E01.720p.trailer", "720p", 9 * mb)
        val pack = hit("Show.S01.720p.x265", "720p", 5000 * mb, scope = Scope.SEASON)
        val dead = hit("Show.S01E01.720p.x265.dead", "720p", 200 * mb, seeders = 0)
        val alive = hit("Show.S01E01.720p.x264", "720p", 800 * mb)
        val playlist = hit("Plugin 720p", "720p", 0, uri = "https://cdn.example/master.m3u8")
        val dash = hit("Plugin DASH 720p", "720p", 0, uri = "https://cdn.example/manifest.mpd")
        assertEquals(alive, batchPick(listOf(trailer, pack, dead, alive, playlist, dash), "720p"))
        // A playlist that will download beats a dead torrent; a dead torrent still beats nothing.
        assertEquals(playlist, batchPick(listOf(trailer, pack, dead, playlist), "720p"))
        assertEquals(dead, batchPick(listOf(trailer, pack, dead, dash), "720p"))
        assertNull(batchPick(listOf(trailer, pack, dash), "720p"))
    }

    /** A direct file link of unknown size is kept after every known size. */
    @Test fun unknownSizeLast() {
        val link = hit("Plugin 720p", "720p", 0, uri = "https://cdn.example/ep1.mp4")
        val torrent = hit("Show.S01E01.720p.x264", "720p", 900 * mb)
        assertEquals(torrent, batchPick(listOf(link, torrent), "720p"))
        assertEquals(link, batchPick(listOf(link), "720p"))
    }

    @Test fun resolutionsOffered() {
        val hits = listOf(
            hit("a 720p", "720p", 400 * mb), hit("b 720p", "720p", 500 * mb),
            hit("c 4K", "4K", 4000 * mb), hit("d 1080p trailer", "1080p", 5 * mb), hit("e", null, 400 * mb),
        )
        assertEquals(listOf("4K" to 1, "720p" to 2), batchQualities(hits))
    }

    @Test fun hevcNames() {
        assertEquals(true, isHevc(hit("Show.S01E01.1080p.H.265", "1080p", 1)))
        assertEquals(true, isHevc(hit("Show S01E01 HEVC 10bit", "1080p", 1)))
        assertEquals(false, isHevc(hit("Show.S01E01.1080p.x264", "1080p", 1)))
    }
}

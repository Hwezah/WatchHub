package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferredPickTest {
    private val mb = 1024L * 1024

    private fun hit(name: String, quality: String?, size: Long, uri: String = "magnet:?xt=urn:btih:$name", seeders: Int = 20) =
        Hit(name, name, uri, seeders, 0, size, quality, "tv", Scope.EPISODE, null)

    private fun link(name: String, quality: String?, size: Long, uri: String) = Hit(uri, name, uri, 0, 0, size, quality, "tv", Scope.EPISODE, null, addon = "HdHub")

    private val defaults = DownloadPrefs()

    @Test fun lightest720pX265MkvByDefault() {
        val hits = listOf(
            hit("E03.2160p.x265.mkv", "4K", 4500 * mb),
            hit("E03.1080p.x265.mkv", "1080p", 1200 * mb),
            hit("E03.720p.x264.mkv", "720p", 500 * mb),
            hit("E03.720p.x265.mp4", "720p", 350 * mb),
            hit("E03.720p.x265.mkv", "720p", 420 * mb),
            hit("E03.720p.x265.mkv.small", "720p", 380 * mb),
        )
        assertEquals("E03.720p.x265.mkv.small", preferredPick(hits, defaults)!!.name)
    }

    @Test fun singleFilesBeforeHls() {
        val hls = link("Plugin 720p x265", "720p", 0, "https://cdn.x/master.m3u8")
        val file = link("E03.720p.x264.mp4", "720p", 900 * mb, "https://cdn.x/e03.mp4")
        assertEquals(file, preferredPick(listOf(hls, file), defaults))
        assertEquals(hls, preferredPick(listOf(hls), defaults))
    }

    @Test fun nearestResolutionBelowThenAbove() {
        val hits = listOf(hit("E03.1080p.x265.mkv", "1080p", 1200 * mb), hit("E03.480p.x264.mp4", "480p", 200 * mb))
        // No 720p: the next one up, before anything below.
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits, defaults)!!.name)
        assertEquals("E03.480p.x264.mp4", preferredPick(hits.drop(1), defaults)!!.name)
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits.take(1), defaults)!!.name)
        assertEquals("E03.1080p.x265.mkv", preferredPick(hits, defaults.copy(quality = "1080p"))!!.name)
    }

    @Test fun anyMeansLightest() {
        val hits = listOf(hit("A.1080p.x264.mp4", "1080p", 800 * mb), hit("B.720p.x265.mkv", "720p", 900 * mb))
        assertEquals("A.1080p.x264.mp4", preferredPick(hits, DownloadPrefs("", "", "", false, ""))!!.name)
    }

    @Test fun trailersNeverPicked() {
        assertNull(preferredPick(listOf(hit("E03.720p.trailer.mkv", "720p", 8 * mb)), defaults))
    }

    @Test fun distances() {
        assertEquals(0, resolutionDistance("720p", "720p"))
        assertEquals(10_240, resolutionDistance("480p", "720p"))
        assertEquals(360, resolutionDistance("1080p", "720p"))
        assertEquals(0, resolutionDistance("4K", ""))
    }

    /** Dope Thief S01E01: Torrentio's 720p x265 MKV (423 MB) against PenguPlay's 720p H.264 HLS links. */
    @Test fun directLinksFirstUnlessHeavy() {
        val torrent = hit("Dope.Thief.S01E01.720p.x265.mkv", "720p", 423 * mb)
        val light = link("PenguPlay 720p HLS H.264 589 MB", "720p", 589 * mb, "https://cdn.x/a.m3u8")
        val heavy = link("PenguPlay 720p HLS H.264 2.02 GB", "720p", 2068 * mb, "https://cdn.x/b.m3u8")
        val unknown = link("Vidy 720p", "720p", 0, "https://cdn.x/c.m3u8")
        val prefs = defaults.copy(direct = true)
        assertEquals(light, preferredPick(listOf(torrent, light, heavy, unknown), prefs))
        // Over 1.5× the torrent, or of unknown size: the torrent.
        assertEquals(torrent, preferredPick(listOf(torrent, heavy, unknown), prefs))
        // Off: as before, the preferred codec and format.
        assertEquals(torrent, preferredPick(listOf(torrent, light), defaults))
        // With no torrent to compare against, any link will do.
        assertEquals(setOf(heavy, unknown), preferableLinks(listOf(heavy, unknown)))
        // The season sheet's chips: the link within the filter wins too.
        assertEquals(light, batchPick(listOf(torrent, light), BatchFilter(quality = "720p"), preferDirect = true))
    }

    /** S01E01: an x265 torrent with 8 seeds loses to an x264 one with 41 — the quick download first. */
    @Test fun wellSeededFirst() {
        val thin = hit("E01.720p.x265.mkv", "720p", 400 * mb, seeders = 8)
        val busy = hit("E01.720p.x264.mkv", "720p", 575 * mb, seeders = 41)
        assertEquals(busy, preferredPick(listOf(thin, busy), defaults))
        assertEquals(busy, batchPick(listOf(thin, busy), BatchFilter(quality = "720p")))
        // Both thin: codec and size decide as before.
        assertEquals(thin, preferredPick(listOf(thin, busy.copy(seeders = 9)), defaults))
    }

    /** A season scan stops asking about an episode once its pick is what was asked for. */
    @Test fun whenAScanCanStop() {
        val prefs = defaults.copy(direct = true)
        val torrent = hit("E01.720p.x265.mkv", "720p", 423 * mb, seeders = 41)
        val link = link("PenguPlay 720p 589 MB", "720p", 589 * mb, "https://cdn.x/a.m3u8")
        assertEquals(false, goodEnough(emptyList(), prefs))
        // Direct links preferred: a torrent alone isn't the end of it; with the link it is.
        assertEquals(false, goodEnough(listOf(torrent), prefs))
        assertEquals(true, goodEnough(listOf(torrent, link), prefs))
        // Torrents will do: a well-seeded 720p is enough, a thin one or a 1080p isn't.
        assertEquals(true, goodEnough(listOf(torrent), defaults))
        assertEquals(false, goodEnough(listOf(torrent.copy(seeders = 3)), defaults))
        assertEquals(false, goodEnough(listOf(hit("E01.1080p.x265.mkv", "1080p", 900 * mb, seeders = 41)), defaults))
    }
}

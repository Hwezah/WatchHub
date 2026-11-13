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
        assertEquals("E03.480p.x264.mp4", preferredPick(hits, defaults)!!.name)
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
        assertEquals(240, resolutionDistance("480p", "720p"))
        assertEquals(10_360, resolutionDistance("1080p", "720p"))
        assertEquals(0, resolutionDistance("4K", ""))
    }
}

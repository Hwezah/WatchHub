package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProbeTest {
    private fun link(name: String, size: Long = 0, quality: String? = null, uri: String = "https://host.example/dl/abc") =
        Hit(id = uri, name = name, uri = uri, seeders = 0, leechers = 0, size = size, quality = quality, kind = "tv", scope = Scope.EPISODE, imdb = null)

    @Test fun nameFromDisposition() {
        assertEquals("Show.S01E02.720p.x265.mkv", fileNameOf("attachment; filename=\"Show.S01E02.720p.x265.mkv\"", "https://h/x"))
        assertEquals("Show S01E02 720p.mkv", fileNameOf("attachment; filename*=UTF-8''Show%20S01E02%20720p.mkv", "https://h/x"))
    }

    @Test fun nameFromAddress() {
        assertEquals("Show.S01E02.1080p.mkv", fileNameOf(null, "https://h/files/Show.S01E02.1080p.mkv?token=1"))
        assertNull(fileNameOf(null, "https://h/dl/abc"))
    }

    @Test fun anUnlabelledLinkIsDescribedByItsFile() {
        val bare = link("Moviesmod Driveseed [326MB]", 326L shl 20)
        assertTrue(unlabelled(bare))
        val d = described(bare, "Show.S01E02.720p.x265.mkv", "video/x-matroska")
        assertEquals("720p", d.quality)
        assertEquals("MKV", traitsOf(d).format)
        assertEquals("x265", traitsOf(d).codec)
        assertEquals(bare.name, d.name)
    }

    @Test fun theTypeGivesTheFormatWhenTheNameHasNone() {
        val d = described(link("Bare", 300L shl 20, "720p"), null, "video/x-matroska")
        assertEquals("MKV", traitsOf(d).format)
    }

    @Test fun aDescribedLightFileBeatsAHeavyLabelledOne() {
        val heavy = link("Show S01E02 1080p x264 .mkv", 11L shl 30, "1080p", "https://a.example/1")
        val light = described(link("Moviesmod Driveseed [326MB]", 326L shl 20), "Show.S01E02.720p.x265.mkv", null)
        assertEquals(light, preferredPick(listOf(heavy, light), DownloadPrefs()))
    }

    @Test fun aBareLinkIsReadByItsSize() {
        val g = guessed(link("Moviesmod Driveseed [326MB]", 326L shl 20))
        assertEquals("720p", g.quality)
        assertEquals("MKV", traitsOf(g).format)
        assertEquals("480p", guessed(link("Bare", 150L shl 20)).quality)
        assertEquals("1080p", guessed(link("Bare", 2L shl 30)).quality)
        // No size, or a labelled link: left as it is.
        assertNull(guessed(link("Bare")).quality)
        val labelled = link("Show 1080p .mkv", 300L shl 20, "1080p")
        assertEquals(labelled, guessed(labelled))
    }

    @Test fun aBareLightLinkBeatsAHeavyLabelledOne() {
        val heavy = link("Show S01E03 1080p x264 .mkv", 11L shl 30, "1080p", "https://a.example/1")
        val light = guessed(link("Moviesmod Driveseed [504MB]", 504L shl 20))
        assertEquals(light, preferredPick(listOf(heavy, light), DownloadPrefs()))
    }

    @Test fun onTargetIsThePreferredResolutionAndFormat() {
        val p = DownloadPrefs()
        assertTrue(onTarget(listOf(link("Show 720p x265 .mkv", 400L shl 20, "720p")), p))
        assertEquals(false, onTarget(listOf(link("Show 1080p x265 .mkv", 3L shl 30, "1080p")), p))
        assertEquals(false, onTarget(emptyList(), p))
    }

    @Test fun seasonPickIsTheLightest720pMkvLink() {
        val heavy1080 = link("Show 1080p x264 .mkv", 3L shl 30, "1080p", "https://a.example/1")
        val big720 = link("Show 720p x265 .mkv", 900L shl 20, "720p", "https://a.example/2")
        val small720 = link("Show 720p .mkv", 320L shl 20, "720p", "https://a.example/3")
        val hls720 = link("Show 720p", 100L shl 20, "720p", "https://a.example/4.m3u8")
        val torrent720 = Hit("hash", "Show 720p x265 .mkv", "magnet:?xt=urn:btih:hash", 50, 0, 200L shl 20, "720p", "tv", Scope.EPISODE, null)
        // Torrents aren't the priority (the owner's rule): the link, though a well-seeded torrent is lighter.
        assertEquals(small720, seasonPick(listOf(heavy1080, big720, small720, hls720, torrent720), DownloadPrefs()))
        assertEquals(torrent720, seasonPick(listOf(torrent720), DownloadPrefs()))
        // A thin one (under 10 seeds) doesn't.
        val thin = torrent720.copy(id = "thin", uri = "magnet:?xt=urn:btih:thin", seeders = 6)
        assertEquals(small720, seasonPick(listOf(heavy1080, big720, small720, hls720, thin), DownloadPrefs()))
        // No 720p MKV anywhere: up to 1080p.
        assertEquals(heavy1080, seasonPick(listOf(heavy1080, hls720), DownloadPrefs()))
    }

    @Test fun aStreamFoundOutIsAPlaylist() {
        val d = described(link("FrameX [720p | English]", 0, "720p"), "index.m3u8", "application/vnd.apple.mpegurl")
        assertEquals("HLS", traitsOf(d).format)
    }

    @Test fun chipsCountEpisodesNotReleases() {
        val ep1 = listOf(link("Show 720p x265 .mkv", 300L shl 20, "720p", "https://a/1"), link("Show 720p x265 .mkv", 310L shl 20, "720p", "https://a/2"))
        val ep2 = listOf(link("Show 1080p .mkv", 2L shl 30, "1080p", "https://a/3"))
        val q = episodeOptions(listOf(ep1, ep2), BatchFilter()) { it.quality }.toMap()
        assertEquals(1, q["720p"])
        assertEquals(1, q["1080p"])
    }

    @Test fun aLightSeasonPackIsFound() {
        fun pack(name: String, size: Long, seeds: Int) = Hit(name, name, "magnet:?xt=urn:btih:$name", seeds, 0, size, "720p", "tv", Scope.SEASON, null)
        val light = pack("Show.S01.720p.x265", 2L shl 30, 40)
        val heavy = pack("Show.S01.720p.x264", 9L shl 30, 80)
        val thin = pack("Show.S01.720p.HEVC.small", 1L shl 30, 4)
        assertEquals(light, lightPack(listOf(light, heavy, thin), 1, 8, DownloadPrefs()))
        assertNull(lightPack(listOf(heavy), 1, 8, DownloadPrefs()))
    }
}

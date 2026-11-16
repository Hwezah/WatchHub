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
}

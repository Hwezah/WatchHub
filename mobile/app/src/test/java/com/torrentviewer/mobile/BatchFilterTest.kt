package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchFilterTest {
    private val mb = 1024L * 1024

    private fun hit(name: String, quality: String?, size: Long, uri: String = "magnet:?xt=urn:btih:$name", seeders: Int = 20) =
        Hit(name, name, uri, seeders, 0, size, quality, "tv", Scope.EPISODE, null)

    @Test fun traitsFromNames() {
        val t = traitsOf(hit("Show.S01E01.1080p.WEB-DL.HEVC.ESub.mkv", "1080p", 900 * mb))
        assertEquals(Traits("1080p", "x265", "MKV", true, "Torrent"), t)
        assertEquals("x264", traitsOf(hit("Show S01E01 720p H.264 AAC.mp4", "720p", 1)).codec)
        assertEquals("MP4", traitsOf(hit("Show S01E01 720p H.264 AAC.mp4", "720p", 1)).format)
        assertEquals("AV1", traitsOf(hit("Show.S01E01.2160p.AV1", "4K", 1)).codec)
        assertEquals(false, traitsOf(hit("Show.S01E01.720p.x264", "720p", 1)).subs)
        // A direct link: its format from the link, HLS for a playlist.
        assertEquals(Traits("720p", UNKNOWN, "MP4", false, "Link"), traitsOf(hit("Plugin", "720p", 0, uri = "https://cdn.x/ep1.mp4?t=1")))
        assertEquals("HLS", traitsOf(hit("Plugin", "720p", 0, uri = "https://cdn.x/master.m3u8")).format)
    }

    private val ep1 = listOf(
        hit("S01E01.1080p.x265.ESub.mkv", "1080p", 700 * mb),
        hit("S01E01.1080p.x264.mp4", "1080p", 1500 * mb),
        hit("S01E01.720p.x264.ESub.mkv", "720p", 600 * mb),
        hit("S01E01.720p.x265.mkv", "720p", 300 * mb),
        hit("S01E01.trailer.720p.mkv", "720p", 5 * mb),
    )

    @Test fun filtersPick() {
        assertEquals("S01E01.720p.x265.mkv", batchPick(ep1, BatchFilter(quality = "720p"))!!.name)
        assertEquals("S01E01.720p.x264.ESub.mkv", batchPick(ep1, BatchFilter(quality = "720p", subsOnly = true))!!.name)
        // A codec picked: no HEVC preference left, just the smallest of that codec.
        assertEquals("S01E01.720p.x264.ESub.mkv", batchPick(ep1, BatchFilter(codec = "x264"))!!.name)
        assertEquals("S01E01.1080p.x264.mp4", batchPick(ep1, BatchFilter(format = "MP4"))!!.name)
        assertNull(batchPick(ep1, BatchFilter(quality = "720p", format = "MP4")))
    }

    @Test fun rowsOfferOnlyWhatIsLeft() {
        // With 1080p chosen, the codec row offers both codecs found at 1080p; the trailer never counts.
        assertEquals(listOf("x265" to 1, "x264" to 1).toSet(), filterOptions(ep1, BatchFilter(quality = "1080p")) { it.codec }.toSet())
        // With subtitles required, the format row has only MKV.
        assertEquals(listOf("MKV" to 2), filterOptions(ep1, BatchFilter(subsOnly = true)) { it.format })
        // Resolutions best first.
        assertEquals(listOf("1080p", "720p"), filterOptions(ep1, BatchFilter()) { it.quality }.map { it.first })
    }

    @Test fun planPerEpisode() {
        val plan = batchPlan(mapOf(1 to ep1, 2 to listOf(hit("S01E02.1080p.x264.mp4", "1080p", 1400 * mb))), BatchFilter(quality = "720p"))
        assertEquals("S01E01.720p.x265.mkv", plan[1]!!.name)
        assertNull(plan[2])
    }
}

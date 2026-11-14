package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Resolution and size read from release and link names. */
class QualityTest {
    @Test fun resolutions() {
        assertEquals("4K", quality("Dope.Thief.S01E01.2160p.ATVP.WEB-DL.HDR.H.265"))
        assertEquals("1080p", quality("pixeldrain | 4KHDHub 1080p"))
        assertEquals("720p", quality("Movies4u HubCloud [Pixeldrain] WEB-DL H264 720p"))
        assertEquals("480p", quality("Show.S01E01.480p.WEB"))
        assertEquals("360p", quality("Vidlink 360p"))
        assertEquals("1440p", quality("Film.1440p.WEB"))
        assertEquals("CAM", quality("Film.2025.1080p.HDCAM.x264"))
        assertNull(quality("Vidy [Miami]"))
        assertNull(quality("Camden.Town.S01E01.WEB"))
    }

    @Test fun sizes() {
        assertEquals((964.83 * (1L shl 20)).toLong(), sizeInName("Moviesmod Driveseed[CF] [X264] [964.83MB]"))
        assertEquals((8.46 * (1L shl 30)).toLong(), sizeInName("[HdHub...] [PixelDrain] [💾 8.46 GB] Dope.Thief.S01E01"))
        assertEquals(0L, sizeInName("💾6Mbps | Dope Thief S1E1"))
        assertEquals(0L, toStreamHits(org.json.JSONArray().put(org.json.JSONObject().put("url", "https://x.example/a.mp4").put("name", "HdHub").put("title", "💾3Mbps | Dope Thief S1E1 720p")), "tv").single().size)
        assertEquals("[PenguPlay] Dope Thief S01E01 · 720p • HLS · Source: Cinejoy · 2.02 GB",
            oneLineName("[PenguPlay] Dope Thief S01E01\n🎞️ 720p • HLS\n📡 Source: Cinejoy\n💾 2.02 GB"))
        assertEquals(0L, sizeInName("Vidy [Miami]"))
    }

    @Test fun episodeInTypedSearch() {
        assertEquals(Triple("dope thief", 1, 3), episodeAsked("dope thief s01e03"))
        assertEquals(Triple("House of the Dragon", 1, 1), episodeAsked("House of the Dragon S1e1"))
        assertEquals(Triple("House of the Dragon", 2, 4), episodeAsked("House of the Dragon S02 E04"))
        assertEquals(Triple("the office", 3, 7), episodeAsked("the office s3ep7"))
        assertEquals(Triple("the office", 3, 7), episodeAsked("the office s3 ep 7"))
        assertEquals(Triple("Dope Thief", 2, 10), episodeAsked("Dope Thief 2x10"))
        assertEquals(Triple("severance", 1, 2), episodeAsked("severance season 1 episode 2"))
        assertEquals(Triple("severance", 2, 1), episodeAsked("severance season 2"))
        assertEquals(Triple("severance", 2, 1), episodeAsked("severance s2"))
        assertEquals(Triple("severance", 1, 5), episodeAsked("severance ep 5"))
        assertEquals(Triple("severance", 1, 5), episodeAsked("severance - s01.e05"))
        assertEquals(null, episodeAsked("dope thief"))
        assertEquals(null, episodeAsked("1917"))
        assertEquals(null, episodeAsked("Se7en"))
        assertEquals(null, episodeAsked("Kingsman"))
        assertEquals(null, episodeAsked("Ocean's 8"))
        assertEquals(null, episodeAsked("s1e1"))
    }
}

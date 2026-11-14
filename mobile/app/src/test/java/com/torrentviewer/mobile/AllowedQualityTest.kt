package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Streaming's left-out resolutions, codecs and formats. */
class AllowedQualityTest {
    private fun hit(name: String, q: String?, uri: String = "https://host.example/a.mkv") = Hit("a", name, uri, 0, 0, 0, q, "movie", Scope.EPISODE, null)

    @Test fun keys() {
        assertEquals(listOf("q:1080p", "c:x265", "f:MKV"), leaveOutKeys(hit("Film.1080p.x265.mkv", "1080p")))
        assertEquals(listOf("q:Auto", "c:Unknown", "f:HLS"), leaveOutKeys(hit("Vidy [Miami]", null, "https://cdn.example/master.m3u8")))
        assertEquals("f:Other", leaveOutKeys(hit("Film 720p", "720p", "https://host.example/watch?id=1"))[2])
    }

    @Test fun leftOutOnesGo() {
        val out = listOf("q:4K", "c:x264", "f:HLS")
        assertFalse(allowed(hit("Film.2160p.x265.mkv", "4K"), out))
        assertFalse(allowed(hit("Film.1080p.x264.mkv", "1080p"), out))
        assertFalse(allowed(hit("Vidy", null, "https://cdn.example/master.m3u8"), out))
        assertTrue(allowed(hit("Film.1080p.x265.mkv", "1080p"), out))
        assertTrue(allowed(hit("Anything", null), emptyList()))
    }
}

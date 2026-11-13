package com.torrentviewer.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
        720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
        https://other.cdn/1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,AUDIO="aud"
        1080sep/index.m3u8
    """.trimIndent()

    @Test fun masterPlaylist() {
        assertTrue(isMasterPlaylist(master))
        val m = parseMaster(master, "https://cdn.example/movie/master.m3u8?token=1")
        assertEquals(4, m.variants.size)
        assertEquals("https://cdn.example/movie/360/index.m3u8", m.variants[0].uri)
        assertEquals("https://other.cdn/1080/index.m3u8", m.variants[2].uri)
        assertEquals(720, m.variants[1].height)
        assertEquals(setOf("aud"), m.separateAudio)
        assertEquals("aud", m.variants[3].audio)
    }

    @Test fun variantChoice() {
        val v = parseMaster(master, "https://cdn.example/m.m3u8").variants
        assertEquals(720, pickVariant(v, 720)!!.height)
        // The 1080p with its audio inside, not the higher-bitrate one whose audio is elsewhere.
        assertEquals("https://other.cdn/1080/index.m3u8", pickVariant(v, null)!!.uri)
        // No 900p: the tallest under it.
        assertEquals(720, pickVariant(v, 900)!!.height)
        assertNull(pickVariant(emptyList(), 720))
    }

    @Test fun mediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:10.0,
            seg7.ts
            #EXTINF:10.0,
            https://x.cdn/seg8.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg9.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val m = parseMedia(text, "https://cdn.example/720/index.m3u8")
        assertTrue(m.ended)
        assertEquals(3, m.segments.size)
        assertEquals("https://cdn.example/720/seg7.ts", m.segments[0].uri)
        assertEquals(7L, m.segments[0].sequence)
        assertEquals("https://cdn.example/720/key.bin", m.segments[1].key!!.uri)
        assertNull(m.segments[2].key)
        assertNull(m.init)
    }

    @Test fun fmp4AndRanges() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:6,
            all.mp4
            #EXT-X-BYTERANGE:500
            #EXTINF:6,
            all.mp4
        """.trimIndent()
        val m = parseMedia(text, "https://cdn.example/v/index.m3u8")
        assertFalse(m.ended)
        assertEquals("https://cdn.example/v/init.mp4", m.init)
        assertEquals(0L, m.segments[0].offset)
        assertEquals(1000L, m.segments[1].offset)
        assertEquals(500L, m.segments[1].length)
    }

    @Test fun ivs() {
        val bySequence = hlsIv(HlsKey("AES-128", "k", null), 258)
        assertArrayEquals(ByteArray(14) + byteArrayOf(1, 2), bySequence)
        val written = hlsIv(HlsKey("AES-128", "k", "0x000102030405060708090A0B0C0D0E0F"), 5)
        assertArrayEquals(ByteArray(16) { it.toByte() }, written)
    }

    @Test fun heights() {
        assertEquals(2160, heightOf("4K"))
        assertEquals(720, heightOf("720p"))
        assertNull(heightOf(null))
    }
}

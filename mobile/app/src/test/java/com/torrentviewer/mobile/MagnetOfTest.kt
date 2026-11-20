package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetOfTest {
    private fun hit(uri: String) = Hit(uri, "Show.S01E01.1080p", uri, 0, 0, 0, "1080p", "tv", Scope.EPISODE, null)

    @Test fun magnets() {
        val m = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=x"
        assertEquals(m, magnetOf(hit(m)))
        // A debrid link names the torrent's hash in its path.
        val rebuilt = magnetOf(hit("https://torrentio.strem.fun/resolve/realdebrid/KEY/0123456789ABCDEF0123456789ABCDEF01234567/null/0/ep.mkv"))!!
        assertTrue(rebuilt.startsWith("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertNull(magnetOf(hit("https://cdn.example/master.m3u8")))
    }
}

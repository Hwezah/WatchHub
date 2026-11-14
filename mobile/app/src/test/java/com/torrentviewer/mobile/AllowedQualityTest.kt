package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Streaming's left-out resolutions. */
class AllowedQualityTest {
    private fun hit(q: String?) = Hit("a", "Film $q", "https://host.example/a.mkv", 0, 0, 0, q, "movie", Scope.EPISODE, null)

    @Test fun leftOutOnesGoUnknownStays() {
        val hidden = listOf("4K", "CAM")
        assertFalse(allowedQuality(hit("4K"), hidden))
        assertFalse(allowedQuality(hit("CAM"), hidden))
        assertTrue(allowedQuality(hit("720p"), hidden))
        assertTrue(allowedQuality(hit(null), hidden))
    }
}

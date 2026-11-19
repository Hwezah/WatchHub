package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Play's preferred streaming resolution. */
class StreamPickTest {
    private fun hit(id: String, quality: String?) = Hit(id, id, "https://x/$id", 0, 0, 0, quality, "movie", Scope.EPISODE, null)

    @Test fun autoFollowsTheConnection() {
        assertEquals("1080p", streamTarget("", onMobileData = false))
        assertEquals("720p", streamTarget("", onMobileData = true))
        assertEquals("480p", streamTarget("480p", onMobileData = false))
    }

    @Test fun resolutions() {
        assertEquals(2160, resolutionOf("4K"))
        assertEquals(2160, resolutionOf("2160p"))
        assertEquals(720, resolutionOf("720p"))
        assertNull(resolutionOf("CAM"))
        assertNull(resolutionOf(null))
    }

    @Test fun theWantedResolutionThenNearestBelowThenAbove() {
        val hits = listOf(hit("a", "1080p"), hit("b", null), hit("c", "480p"), hit("d", "720p"), hit("e", "4K"), hit("f", "720p"), hit("g", "360p"))
        assertEquals(listOf("d", "f", "c", "g", "a", "e", "b"), streamOrder(hits, "720p").map { it.id })
        // Nothing at 4K: the best below it first.
        assertEquals(listOf("a", "d", "f", "c", "g", "b"), streamOrder(hits.filter { it.id != "e" }, "4K").map { it.id })
        assertEquals(hits, streamOrder(hits, "any"))
    }

    @Test fun saysWhenItPlaysAnother() {
        assertNull(streamNote(hit("a", "720p"), "720p"))
        assertEquals("No 720p source — playing 1080p", streamNote(hit("a", "1080p"), "720p"))
        assertNull(streamNote(hit("a", null), "720p"))
    }
}

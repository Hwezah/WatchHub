package com.torrentviewer.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cutting a file into parts for many connections. */
class PartsTest {
    @Test fun partsCoverTheFileExactlyOnce() {
        val total = 1_000_000_007L
        val parts = partsOf(total, 32)
        assertEquals(0L, parts.first().first)
        assertEquals(total - 1, parts.last().second)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.second + 1, b.first) }
        // About four parts a connection.
        assertTrue(parts.size in 120..130)
    }

    @Test fun neverUnderTwoMegabytes() {
        val parts = partsOf(20L * 1024 * 1024, 32)
        assertEquals(10, parts.size)
        assertEquals(emptyList<Pair<Long, Long>>(), partsOf(0, 8))
    }
}

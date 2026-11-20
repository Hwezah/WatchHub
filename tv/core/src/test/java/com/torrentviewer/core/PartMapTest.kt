package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The download card's part map. */
class PartMapTest {
    private fun near(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.01f) }
    }

    /** Four connections, each part of 100 bytes part-way in: each slice as full as its part. */
    @Test fun eachPartItsOwnSlice() {
        val has = listOf(0L..49L, 100L..109L, 200L..299L, LongRange.EMPTY)
        near(listOf(0.5f, 0.1f, 1f, 0f), partMap(400, has, 4))
    }

    /** Fewer slices than parts: each slice counts what falls in it. */
    @Test fun slicesSpanSeveralParts() {
        near(listOf(0.3f, 1f), partMap(400, listOf(0L..49L, 100L..109L, 200L..399L), 2))
    }

    @Test fun inOrderFillsFromTheStart() {
        near(listOf(1f, 1f, 0.5f, 0f), inOrderMap(0.625f, 4))
        near(listOf(0f, 0f), inOrderMap(Float.NaN, 2))
        near(listOf(0f, 0f, 0f), partMap(0, emptyList(), 3))
    }

    @Test fun torrentPiecesAsRuns() {
        val bits = listOf(true, true, false, true, false, false, true, true)
        assertEquals(listOf(0L..1L, 3L..3L, 6L..7L), runsOf(bits.size) { bits[it] })
        near(listOf(1f, 0.5f, 0f, 1f), partMap(8, runsOf(bits.size) { bits[it] }, 4))
    }
}

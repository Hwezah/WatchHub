package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The crash report's reading of Android's exit records. */
class ExitReportTest {
    @Test
    fun ordinaryEndsAreNotReported() {
        // EXIT_SELF, PERMISSION_CHANGE, USER_REQUESTED, USER_STOPPED, OTHER, PACKAGE_UPDATED.
        listOf(1, 8, 10, 11, 13, 16).forEach { assertNull("reason $it", exitLabel(it)) }
        // CRASH_NATIVE, ANR, LOW_MEMORY.
        listOf(5, 6, 3).forEach { assertNotNull("reason $it", exitLabel(it)) }
    }

    @Test
    fun tombstoneTextKeepsWordsDropsNoise() {
        val bytes = byteArrayOf(0, 1) + "Abort message: 'Render process crash not handled'".toByteArray() + byteArrayOf(2) +
            "ab".toByteArray() + byteArrayOf(3) + "9f86d081884c7d659a2feaa0c55ad015".toByteArray() + byteArrayOf(0) +
            "libwebviewchromium.so".toByteArray() + byteArrayOf(0) + "libwebviewchromium.so".toByteArray()
        assertEquals(listOf("Abort message: 'Render process crash not handled'", "libwebviewchromium.so"), readableRuns(bytes))
    }

    @Test
    fun tombstoneTextStopsAtTheLimit() {
        val bytes = (1..10).flatMap { "line number $it".toByteArray().toList() + 0.toByte() }.toByteArray()
        assertEquals(listOf("line number 1", "line number 2", "line number 3"), readableRuns(bytes, limit = 3))
    }

    @Test
    fun anrStartsAtTheMainThread() {
        val dump = "----- pid 123 -----\nCmd line: app\n\n\"main\" prio=5 tid=1 Blocked\n  at a.b(C.kt:1)\n  at d.e(F.kt:2)\n\"Thread-2\" prio=5"
        assertEquals(listOf("\"main\" prio=5 tid=1 Blocked", "  at a.b(C.kt:1)"), anrMainThread(dump, lines = 2))
        assertEquals("----- pid 123 -----", anrMainThread("----- pid 123 -----\nCmd line: app").first())
    }
}

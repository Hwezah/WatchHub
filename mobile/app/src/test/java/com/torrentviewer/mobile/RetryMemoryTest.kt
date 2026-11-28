package com.torrentviewer.mobile

import com.torrentviewer.core.Hit
import com.torrentviewer.core.Scope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryMemoryTest {
    private fun hit(uri: String) = Hit(uri, uri, uri, 0, 0, 0, "720p", "movie", Scope.EPISODE, null, addon = "A")

    @Test fun roundTripsWhatTheQueueStillHas() {
        val memory = RetryMemory(
            failed = mapOf("Show · S01E01" to setOf("https://a/1", "https://a/2"), "Gone" to setOf("https://x")),
            busy = mapOf("Show · S01E01" to setOf("a.example")),
            rounds = mapOf("Show · S01E01" to 3, "Gone" to 1),
        )
        val back = decodeRetryMemory(encodeRetryMemory(memory, keep = setOf("Show · S01E01")))
        assertEquals(setOf("https://a/1", "https://a/2"), back.failed["Show · S01E01"])
        assertEquals(setOf("a.example"), back.busy["Show · S01E01"])
        assertEquals(3, back.rounds["Show · S01E01"])
        // A download no longer queued takes its history with it.
        assertNull(back.failed["Gone"])
        assertNull(back.rounds["Gone"])
    }

    @Test fun keepsOnlyTheLatestFailedLinks() {
        val many = (1..(MAX_REMEMBERED + 10)).map { "https://a/$it" }.toCollection(LinkedHashSet())
        val back = decodeRetryMemory(encodeRetryMemory(RetryMemory(failed = mapOf("F" to many)), setOf("F")))
        assertEquals(MAX_REMEMBERED, back.failed["F"]!!.size)
        assertTrue("https://a/${MAX_REMEMBERED + 10}" in back.failed["F"]!!)
        assertFalse("https://a/1" in back.failed["F"]!!)
    }

    @Test fun unreadableIsEmpty() {
        assertEquals(RetryMemory(), decodeRetryMemory(null))
        assertEquals(RetryMemory(), decodeRetryMemory("not json"))
        assertEquals(RetryMemory(), decodeRetryMemory("[]"))
    }

    /** Candidates in order; [next] gives the first not skipped. */
    private fun from(vararg uris: String): (Set<String>) -> Hit? = { skip -> uris.firstOrNull { it !in skip }?.let(::hit) }

    @Test fun skipsDeadLinksAndWebPages() = runBlocking {
        val checks = mapOf("dead" to LinkCheck.DEAD, "page" to LinkCheck.DEAD, "good" to LinkCheck.RESUMABLE)
        assertEquals("good", checkedPick(5, from("dead", "page", "good")) { checks.getValue(it.uri) }?.uri)
    }

    @Test fun prefersResumableOverWholeFileOnly() = runBlocking {
        val checks = mapOf("whole" to LinkCheck.WHOLE, "ranges" to LinkCheck.RESUMABLE)
        assertEquals("ranges", checkedPick(5, from("whole", "ranges")) { checks.getValue(it.uri) }?.uri)
    }

    @Test fun takesWholeFileWhenNothingResumableIsLeft() = runBlocking {
        val checks = mapOf("whole" to LinkCheck.WHOLE, "dead" to LinkCheck.DEAD)
        assertEquals("whole", checkedPick(5, from("whole", "dead")) { checks.getValue(it.uri) }?.uri)
    }

    @Test fun looksAtNoMoreThanTheLimit() = runBlocking {
        var looked = 0
        val got = checkedPick(3, from("a", "b", "c", "d", "e")) { looked++; LinkCheck.DEAD }
        assertNull(got)
        assertEquals(3, looked)
    }

    @Test fun stallsOutAfterThreeRestarts() {
        assertFalse(stalledOut(2))
        assertTrue(stalledOut(3))
    }
}

package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A person page's birth line: dates, age, and a death. */
class PersonLifeTest {
    @Test fun ageCountsOnlyFullYears() {
        assertEquals(62, ageOf("1964-06-12", null, "2026-06-12"))
        assertEquals(61, ageOf("1964-06-12", null, "2026-06-11"))
        assertEquals(60, ageOf("1964-06-12", null, "2025-06-11"))
        assertNull(ageOf(null, null, "2026-11-13"))
    }

    @Test fun lines() {
        assertEquals("Born Jun 12, 1964 (age 62)", lifeLine("1964-06-12", null, "2026-11-13"))
        assertEquals("Jul 4, 1950 – Mar 3, 2020 (aged 69)", lifeLine("1950-07-04", "2020-03-03", "2026-11-13"))
        assertNull(lifeLine("", null, "2026-11-13"))
    }

    @Test fun newestFirstUnknownLast() {
        val p = TmdbPerson("A", null, null, null, listOf(
            TmdbCard(1, "movie", "Old", "p", "1992"), TmdbCard(2, "movie", "None", "p", null), TmdbCard(3, "movie", "New", "p", "2026"),
        ))
        assertEquals(listOf("New", "Old", "None"), p.byYear.map { it.title })
    }
}

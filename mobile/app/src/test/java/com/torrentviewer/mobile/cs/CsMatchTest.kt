package com.torrentviewer.mobile.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsMatchTest {
    @Test fun titles() {
        assertEquals("dark knight", normTitle("The Dark Knight (2008)"))
        assertEquals("dune part two", normTitle("Dune: Part Two"))
        assertEquals("spider man no way home", normTitle("Spider-Man: No Way Home"))
        assertEquals("amélie", normTitle("Amélie"))
    }

    @Test fun matches() {
        val list = listOf(
            Candidate("The Dark Knight Rises", 2012, false),
            Candidate("The Dark Knight", 2008, false),
            Candidate("The Dark Knight", 2008, true),
        )
        assertEquals(1, bestMatch("The Dark Knight", 2008, false, list))
        // A site a year out still counts, below an exact year.
        assertEquals(1, bestMatch("Dune: Part Two", 2024, false, listOf(Candidate("Dune Part Two", 2023, null), Candidate("Dune: Part Two", 2024, false))))
        assertEquals(0, bestMatch("Dune: Part Two", 2024, false, listOf(Candidate("Dune Part Two 2024", null, null))))
        // Wrong kind, wrong decade, another film.
        assertNull(bestMatch("Dune", 2021, false, listOf(Candidate("Dune", 1984, false), Candidate("Dune", 2021, true), Candidate("Dune: Prophecy", 2024, true))))
        assertNull(bestMatch("", null, false, listOf(Candidate("", null, null))))
        // StreamPlay calls every result a movie: the kind then says nothing.
        assertEquals(1, bestMatch("Dope Thief", 2025, true, listOf(Candidate("Dope", 2025, false), Candidate("Dope Thief", 2025, false))))
        // Mixed kinds still count.
        assertNull(bestMatch("Dope Thief", 2025, true, listOf(Candidate("Dope Thief", 2025, false), Candidate("Other", 2025, true))))
    }

    @Test fun episodes() {
        val eps = listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2).map { (s, e) -> s as Int? to e as Int? }
        assertEquals(3, pickEpisode(eps, 2, 2))
        assertNull(pickEpisode(eps, 3, 1))
        // No seasons: one season, or numbered through the show.
        val flat = (1..30).map { null as Int? to it as Int? }
        assertEquals(4, pickEpisode(flat, 1, 5))
        assertEquals(27, pickEpisode(flat, 2, 4, absolute = 28))
        assertNull(pickEpisode(flat, 2, 4))
    }

    @Test fun qualities() {
        assertEquals("4K", qualityLabel(2160))
        assertEquals("1080p", qualityLabel(1080))
        assertEquals("480p", qualityLabel(480))
        // CloudStream's Unknown is 400; plugins also send 0 and -1.
        assertNull(qualityLabel(400))
        assertNull(qualityLabel(0))
        assertNull(qualityLabel(-1))
    }

    @Test fun languages() {
        val known = listOf("EN" to Regex("\\b(english|eng)\\b", RegexOption.IGNORE_CASE))
        assertEquals("EN", subtitleLang("English", known))
        assertEquals("EN", subtitleLang("eng", known))
        assertEquals("FR", subtitleLang("fr", known))
        assertEquals("Português", subtitleLang("Português", known))
    }
}

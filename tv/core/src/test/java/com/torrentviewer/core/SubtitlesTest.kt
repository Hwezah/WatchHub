package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Saving a source's subtitles. */
class SubtitlesTest {
    @Test fun languagesToOneName() {
        assertEquals("English", subtitleLanguage("en"))
        assertEquals("English", subtitleLanguage("English (SDH)"))
        assertEquals("English", subtitleLanguage("eng [Forced]"))
        assertEquals("Portuguese", subtitleLanguage("ptbr"))
        assertEquals("Tagalog", subtitleLanguage("tagalog"))
    }

    @Test fun onlyTheFirstEnglishTicked() {
        assertEquals(setOf(2), defaultSubtitlePicks(listOf("French", "Hindi", "English", "English (SDH)")))
        assertEquals(emptySet<Int>(), defaultSubtitlePicks(listOf("French", "Hindi")))
    }

    @Test fun namedBesideTheVideo() {
        assertEquals("Film.English.srt", subtitleFileName("Film", "en", "srt", emptyList()))
        assertEquals("Film.English.2.srt", subtitleFileName("Film", "English (SDH)", "srt", listOf("Film.English.srt")))
        assertEquals("Film.English.3.srt", subtitleFileName("Film", "eng", "srt", listOf("Film.English.srt", "film.english.2.srt")))
    }
}

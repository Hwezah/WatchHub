package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading a story out of Wikipedia: a film's plot section, an episode's row in an episode list, and the kept form. */
class BreakdownTest {
    @Test fun wikipediaPlot() {
        val article = "Interstellar is a film.\n\n== Plot ==\nFirst part.\n\n=== Act two ===\nSecond part.\nThe end.\n\n== Cast ==\nSomeone"
        assertEquals(listOf("First part.", "Second part.", "The end."), plotParagraphs(article))
        assertEquals(emptyList<String>(), plotParagraphs("No sections here."))
        val (scenes, ending) = plotBreakdown(listOf("A", "B", "C"))!!
        assertEquals(listOf("A", "B"), scenes.map { it.text })
        assertEquals(listOf("C"), ending.map { it.text })
        assertEquals(emptyList<Scene>(), plotBreakdown(listOf("Only"))!!.second)
        assertNull(plotBreakdown(emptyList()))
    }

    @Test fun episodeLists() {
        val list = """
            == Series overview ==
            == Season 1 (2022) ==
            {{Episode table |episodes =
            {{Episode list
             |EpisodeNumber = 1
             |EpisodeNumber2 = 1
             |Title = [[Good News About Hell]]
             |ShortSummary = Mark is promoted.<ref>{{cite web|url=x}}</ref>
            }}
            {{Episode list
             |EpisodeNumber = 2
             |EpisodeNumber2 = 2
             |Title = Half Loop
             |ShortSummary = Helly tries to quit; [[Mark Scout|Mark]] trains her.<!-- note -->

            Later, ''the board'' answers.
            }}
            }}
            == Season 2 (2025) ==
            {{Episode list/sublist|List of Severance episodes
             |EpisodeNumber = 11
             |EpisodeNumber2 = 2
             |Title = Goodbye, Mrs. Selvig
             |ShortSummary = A new season begins.
            }}
        """.trimIndent()
        assertEquals(listOf("Helly tries to quit; Mark trains her.", "Later, the board answers."), episodeSummary(list, 1, 2, seasonPage = false))
        assertEquals(listOf("A new season begins."), episodeSummary(list, 2, 2, seasonPage = false))
        assertEquals(listOf("Mark is promoted."), episodeSummary(list, 1, 1, seasonPage = false))
        assertEquals(emptyList<String>(), episodeSummary(list, 3, 1, seasonPage = false))
        // A page of one season: no headings, any row of that number.
        val seasonPage = "{{Episode list|EpisodeNumber=5|EpisodeNumber2=5|ShortSummary=Five.}}"
        assertEquals(listOf("Five."), episodeSummary(seasonPage, 4, 5, seasonPage = true))
    }

    @Test fun cleaning() {
        assertEquals(listOf("Bold and italic, a link, done."), cleanWikitext("'''Bold''' and ''italic'', [[Target|a link]], done.{{efn|note {{nested}}}}"))
        assertEquals(listOf("One.", "Two & three."), cleanWikitext("<p>One.</p><p>Two &amp; three.</p>"))
    }

    @Test fun keptForm() {
        val json = """{"scenes":[{"from":"","to":"","text":"A"},{"text":" "}],"ending":[{"text":"B"}],"source":"Plot from Wikipedia"}"""
        val (scenes, ending) = parseBreakdown(json)!!
        assertEquals(listOf(Scene("", "", "A")), scenes)
        assertEquals(listOf(Scene("", "", "B")), ending)
        assertNull(parseBreakdown("""{"scenes":[]}"""))
        assertNull(parseBreakdown("not json"))
    }
}

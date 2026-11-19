package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search that forgives what's typed. */
class FuzzySearchTest {
    @Test fun variants() {
        val v = searchVariants("The Dark Knight 2008")
        assertEquals("The Dark Knight 2008", v.first())
        assertTrue("The Dark Knight" in v)
        assertTrue("dark knight" in v)
        assertTrue("rocky ii" in searchVariants("Rocky 2"))
        assertTrue("fast & furious" in searchVariants("Fast and Furious"))
        assertEquals(emptyList<String>(), searchVariants("   "))
    }

    @Test fun simplifying() {
        assertEquals("amelie", simplify("Amélie"))
        assertEquals("tom and jerry", simplify("Tom & Jerry!"))
    }

    @Test fun typosStillFindTheTitle() {
        assertTrue(fuzzyScore("The Dark Knight", "dark knigt") >= FUZZY_SURE)
        assertTrue(fuzzyScore("Interstellar", "intersteller") >= FUZZY_SURE)
        assertTrue(fuzzyScore("Spider-Man", "spiderman") >= 0.9)
        assertTrue(fuzzyScore("Rocky II", "rocky 2") >= 0.99)
        // A different film that shares one word isn't shown.
        assertTrue(fuzzyScore("Knight and Day", "dark knigt") < FUZZY_SHOW)
        assertTrue(fuzzyScore("The Dark Knight", "dark knigt") > fuzzyScore("The Dark Knight Rises", "dark knigt"))
    }

    @Test fun theYearTypedRanksItsRelease() {
        assertTrue(fuzzyScore("Dune", "dune 2021", "2021") > fuzzyScore("Dune", "dune 2021", "1984"))
    }

    @Test fun partials() {
        val p = partialQueries("dark knigt rises")
        assertTrue("dark knigt" in p)
        assertTrue("rises" in p)
        assertEquals(listOf("interst"), partialQueries("intersteller"))
        assertEquals(1, editDistance("abc", "acb"))
        assertEquals(3, editDistance("kitten", "sitting"))
    }

    /** "1923", "1917", "2012": a name that is a number isn't a year typed with a name. */
    @Test fun aNameThatIsAYear() {
        assertTrue(fuzzyScore("1923", "1923", "2022") >= 1.0)
        assertTrue(fuzzyScore("1917", "1917") >= 1.0)
        assertTrue(fuzzyScore("Blade Runner 2049", "blade runner 2049") >= 1.0)
        assertTrue(fuzzyScore("1923", "1923") > fuzzyScore("1883", "1923"))
        assertEquals(null, yearIn("1923"))
        assertEquals("2021", yearIn("dune 2021"))
        assertEquals(listOf("1923"), searchVariants("1923"))
    }

    @Test fun shortAndEveryWordMisspelt() {
        assertEquals(listOf("dun"), partialQueries("dunee"))
        assertTrue("thro" in partialQueries("gam of throns"))
        assertTrue(fuzzyScore("Game of Thrones", "gam of throns") >= FUZZY_SHOW)
    }
}

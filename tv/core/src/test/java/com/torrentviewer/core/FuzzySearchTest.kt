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
}

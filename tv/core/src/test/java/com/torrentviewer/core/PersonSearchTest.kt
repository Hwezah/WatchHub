package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finding a cast member by name on TMDB. */
class PersonSearchTest {
    @Test fun sameNameActorFirst() {
        val json = """{"results":[
            {"id":1,"name":"Michael Podemski","known_for_department":"Writing","popularity":9.0},
            {"id":2,"name":"Michael Podemski","known_for_department":"Acting","popularity":1.2,"profile_path":"/m.jpg"},
            {"id":3,"name":"Michael Podemskie","known_for_department":"Acting","popularity":30.0}]}"""
        val p = parsePersonSearch(json, "Michael Podemski")!!
        assertEquals(2, p.id)
        assertEquals("https://image.tmdb.org/t/p/w185/m.jpg", p.photo)
    }

    @Test fun noOneByThatName() {
        assertNull(parsePersonSearch("""{"results":[{"id":3,"name":"Someone Else"}]}""", "Kiawentiio"))
    }
}

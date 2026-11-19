package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Grabber! layout and the Files tab's move, copy and naming helpers. */
class FoldersTest {
    @Test
    fun browserDownloadsSortIntoBrowser() {
        assertEquals("Browser/Video", webFolder("clip.MP4"))
        assertEquals("Browser/Audio", webFolder("song.mp3"))
        assertEquals("Browser/Documents", webFolder("book.epub"))
        assertEquals("Browser/Images", webFolder("cat.webp"))
        assertEquals("Browser/Other", webFolder("vpn.conf"))
        assertEquals("Browser/Other", webFolder("noextension"))
    }

    @Test
    fun fixedFoldersAreTheLayoutsOwn() {
        assertTrue(isFixedFolder(listOf("Video")))
        assertTrue(isFixedFolder(listOf("Video", "Shows")))
        assertTrue(isFixedFolder(listOf("Browser", "Documents")))
        assertFalse(isFixedFolder(listOf("Video", "Shows", "1923")))
        // Grabber's own Documents at the root isn't ours to keep.
        assertFalse(isFixedFolder(listOf("Documents")))
    }

    @Test
    fun oldLayoutMovesIntoVideoAndBrowser() {
        assertEquals(listOf("Video", "Movies"), relaid(listOf("Movies"), false))
        assertEquals(listOf("Video", "YouTube"), relaid(listOf("YouTube"), false))
        assertEquals(listOf("Video", "Shows", "1923", "Season 1"), relaid(listOf("Shows", "1923", "Season 01"), false))
        assertEquals(listOf("Video", "Shows", "1923", "Season 12"), relaid(listOf("Video", "Shows", "1923", "Season 12").drop(1), false))
        // The Browser's files leave the shared folders; other apps' stay put.
        assertEquals(listOf("Browser", "Video"), relaid(listOf("Video"), true))
        assertEquals(listOf("Browser", "Documents"), relaid(listOf("Documents"), true))
        assertNull(relaid(listOf("Video"), false))
        assertNull(relaid(listOf("Archives"), true))
        // Already in place.
        assertNull(relaid(listOf("Video", "Shows", "1923", "Season 1"), false))
        assertEquals(listOf("Video", "Shows", "1923", "Season 3"), relaid(listOf("Video", "Shows", "1923", "Season 03"), false))
        assertEquals("Season 1", renumbered("Season 01"))
        assertNull(renumbered("Season 1"))
        assertNull(renumbered("Specials"))
    }

    @Test
    fun freeNamesNumberAClash() {
        assertEquals("Film.mkv", freeName("Film.mkv", listOf("Other.mkv"), false))
        assertEquals("Film (2).mkv", freeName("Film.mkv", listOf("film.MKV"), false))
        assertEquals("Film (3).mkv", freeName("Film.mkv", listOf("Film.mkv", "Film (2).mkv"), false))
        assertEquals("Film (3).mkv", freeName("Film (2).mkv", listOf("Film (2).mkv"), false))
        assertEquals("Mr. Robot (2)", freeName("Mr. Robot", listOf("Mr. Robot"), true))
        assertEquals(".hidden (2)", freeName(".hidden", listOf(".hidden"), false))
    }

    @Test
    fun pathsInsideAFolder() {
        assertTrue(isWithin("/g/Video/Shows/1923", "/g/Video/Shows"))
        assertTrue(isWithin("/g/Video", "/g/Video"))
        assertFalse(isWithin("/g/Videos", "/g/Video"))
        assertEquals("/g/Video/Movies/a.mkv", rebased("/g/Movies/a.mkv", "/g/Movies", "/g/Video/Movies"))
        assertEquals("/g/b.mkv", rebased("/g/a.mkv", "/g/a.mkv", "/g/b.mkv"))
        assertNull(rebased("/g/Moviesx/a.mkv", "/g/Movies", "/g/Video/Movies"))
    }

    @Test
    fun typedNamesKeepTheExtension() {
        assertEquals("Season 1", entryName("  Season 1 ", null))
        assertEquals("Pilot.mkv", entryName("Pilot", "mkv"))
        assertEquals("Pilot.mkv", entryName("Pilot.MKV", "mkv"))
        assertEquals("Pilot.mp4.mkv", entryName("Pilot.mp4", "mkv"))
        assertEquals("a_b", entryName("a/b", null))
        assertEquals("hidden", entryName(".hidden", null))
        assertEquals("Download", entryName("...", null))
    }

    @Test
    fun naturalOrderComparesNumbers() {
        val seasons = listOf("Season 10", "season 2", "Season 1", "Specials", "Season 02b")
        assertEquals(listOf("Season 1", "season 2", "Season 02b", "Season 10", "Specials"), seasons.sortedWith(NaturalOrder))
    }
}

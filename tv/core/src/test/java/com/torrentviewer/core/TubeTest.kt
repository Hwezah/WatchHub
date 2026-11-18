package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Tube tab's pure parts: links, counts, the feed's order, and subscription imports. */
class TubeTest {
    private fun v(id: String, channel: String = "C", at: Long = 0) = TubeVideo(id, "T$id", channel, "https://www.youtube.com/channel/UC$channel", at = at)

    @Test fun videoIds() {
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(youtubeId("https://www.youtube.com/@BlueRockTV"))
    }

    @Test fun channelKeys() {
        assertEquals("UC1234567890123456789012", channelKey("https://www.youtube.com/channel/UC1234567890123456789012/"))
        assertEquals("https://www.youtube.com/@bluerock", channelKey("https://www.youtube.com/@BlueRock/?si=x"))
    }

    @Test fun counts() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2k", compactCount(1_270))
        assertEquals("74k", compactCount(74_310))
        assertEquals("520k", compactCount(520_999))
        assertEquals("3M", compactCount(3_000_000))
        assertEquals("1.2B", compactCount(1_234_000_000))
        assertEquals("", compactCount(-1))
    }

    @Test fun clocks() {
        assertEquals("1:53:09", clockText(6789))
        assertEquals("4:05", clockText(245))
        assertEquals("0:42", clockText(42))
        assertEquals("", clockText(0))
    }

    @Test fun lines() {
        assertEquals("Blue Rock TV • 74k views • 13 days ago", tubeLine(TubeVideo("a", "t", "Blue Rock TV", views = 74_000, ago = "13 days ago")))
        assertEquals("News • Live", tubeLine(TubeVideo("a", "t", "News", live = true)))
        assertEquals("X", tubeLine(TubeVideo("a", "t", "X")))
    }

    @Test fun takeout() {
        val csv = "﻿Channel Id,Channel Url,Channel Title\n" +
            "UC1234567890123456789012,http://www.youtube.com/channel/UC1234567890123456789012,\"Blue Rock, TV\"\n" +
            "\n" +
            "UCabcdefghijklmnopqrstuv,http://www.youtube.com/channel/UCabcdefghijklmnopqrstuv,Travel Cipher\n" +
            "UC1234567890123456789012,http://www.youtube.com/channel/UC1234567890123456789012,Again\n"
        val subs = parseSubscriptions(csv)
        assertEquals(listOf("Blue Rock, TV", "Travel Cipher"), subs.map { it.name })
        assertEquals("https://www.youtube.com/channel/UC1234567890123456789012", subs[0].url)
    }

    @Test fun newPipe() {
        val json = """{"app_version":"0.27","subscriptions":[{"service_id":0,"url":"https://www.youtube.com/channel/UC1234567890123456789012","name":"A"},
            {"service_id":1,"url":"https://soundcloud.com/x","name":"S"}]}"""
        assertEquals(listOf("A"), parseSubscriptions(json).map { it.name })
        assertEquals(emptyList<TubeChannel>(), parseSubscriptions("{nope"))
    }

    @Test fun feed() {
        val subs = listOf(v("a", "X", 5), v("b", "X", 4), v("c", "X", 3), v("d", "X", 2), v("e", "Y", 1))
        val trending = listOf(v("t1", "Z"), v("a", "X"), v("t2", "Z"))
        assertEquals(listOf("a", "b", "c", "e", "t1", "t2"), homeFeed(subs, trending).map { it.id })
        assertEquals(listOf("t1", "a", "t2"), homeFeed(emptyList(), trending).map { it.id })
    }

    @Test fun weaving() {
        assertEquals(listOf("a", "x", "b", "c"), interleave(listOf(listOf(v("a"), v("b"), v("c")), listOf(v("x"), v("b")))).map { it.id })
    }

    @Test fun roundTrip() {
        val list = listOf(TubeVideo("abcdefghijk", "Title", "Ch", "https://www.youtube.com/@ch", null, "https://i.ytimg.com/x.jpg", 61, 10, "1 day ago", 5, false))
        assertEquals(list, tubeVideos(tubeVideosJson(list)))
        val ch = listOf(TubeChannel("https://www.youtube.com/@ch", "Ch"))
        assertEquals(ch, tubeChannels(tubeChannelsJson(ch)))
        assertEquals(emptyList<TubeVideo>(), tubeVideos("garbage"))
    }
}

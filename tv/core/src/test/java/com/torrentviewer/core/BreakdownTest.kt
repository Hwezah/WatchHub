package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading subtitles into timed lines, the AI's breakdown, and Wikipedia's plot section. */
class BreakdownTest {
    @Test fun subRip() {
        val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i> there.\r\nGeneral.\r\n\r\n2\r\n01:02:03,04 --> 01:02:05,000\r\n♪\r\n\r\n3\r\n01:02:06,000 --> 01:02:08,000\r\n{\\an8}Bye."
        assertEquals(listOf(Cue(1500, "Hello there. General."), Cue(3726000, "Bye.")), subtitleCues(srt))
    }

    @Test fun webVttAndAss() {
        val vtt = "WEBVTT\n\nNOTE made by hand\n\n00:05.250 --> 00:07.000 align:start\nShort one\n\n00:01:10.000 --> 00:01:12.000\nLater"
        assertEquals(listOf(Cue(5250, "Short one"), Cue(70000, "Later")), subtitleCues(vtt))
        val ass = "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,Hi, you\\Nthere\n"
        assertEquals(listOf(Cue(2500, "Hi, you there")), subtitleCues(ass))
    }

    @Test fun stampsAndTranscript() {
        assertEquals("08:40", stamp(520_000))
        assertEquals("1:02:15", stamp(3_735_000))
        val said = transcript(listOf(Cue(1000, "a"), Cue(20_000, "b"), Cue(95_000, "c")))
        assertEquals("[00:00] a b\n[01:30] c", said)
        // Too long: each half minute cut alike, the whole running time kept.
        val long = transcript((0 until 100).map { Cue(it * 30_000L, "x".repeat(500)) }, maxChars = 10_000)
        assertTrue(long.length <= 10_000 + 100 * 20)
        assertTrue(long.lines().last().startsWith("[49:30]"))
    }

    @Test fun aiReply() {
        val reply = "```json\n{\"scenes\":[{\"from\":\"00:00\",\"to\":\"08:40\",\"text\":\"Cooper farms.\"},{\"from\":\"08:40\",\"to\":\"20:00\",\"text\":\" \"}]," +
            "\"ending\":[{\"from\":\"2:30:00\",\"to\":\"2:49:00\",\"text\":\"He returns.\"}]}\n```"
        val (scenes, ending) = parseBreakdown(reply)!!
        assertEquals(listOf(Scene("00:00", "08:40", "Cooper farms.")), scenes)
        assertEquals(listOf(Scene("2:30:00", "2:49:00", "He returns.")), ending)
        assertNull(parseBreakdown("I can't help with that."))
        assertNull(parseBreakdown("{\"scenes\":[]}"))
        assertTrue(breakdownPrompt("Interstellar (2014)", "[00:00] hi", 8..16).contains("8 to 16 parts"))
    }

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
}

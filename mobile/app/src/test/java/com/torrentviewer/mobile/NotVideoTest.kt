package com.torrentviewer.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host's web page saved instead of the video is told apart from the video. */
class NotVideoTest {
    @Test fun pagesAndErrors() {
        assertTrue(looksLikeText("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(looksLikeText("\n  <html lang=en>".toByteArray()))
        assertTrue(looksLikeText("{\"error\":\"expired\"}".toByteArray()))
        assertTrue(looksLikeText(ByteArray(0)))
    }

    @Test fun videoContainers() {
        // Matroska / WebM, MP4 ("....ftyp"), MPEG-TS (0x47).
        assertFalse(looksLikeText(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertFalse(looksLikeText(byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertFalse(looksLikeText(byteArrayOf(0x47, 0x40, 0x11, 0x10)))
    }

    @Test fun tooSmallOrMissing() {
        val tiny = java.io.File.createTempFile("ep", ".mkv").apply { writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())); deleteOnExit() }
        assertTrue(notVideo(tiny))
        assertTrue(notVideo(java.io.File("/no/such/file.mkv")))
    }
}

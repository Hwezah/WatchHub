package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AccentTest {
    @Test fun knownShade() = assertEquals(0xFF00897B, accentShade("Teal"))

    @Test fun unknownIsRed() = assertEquals(0xFFB81D24, accentShade("Chartreuse"))

    @Test fun redComesFirst() = assertEquals("Red", ACCENT_SHADES.first().first)

    @Test fun lightShadesTakeDarkWords() {
        assertEquals(true, readsDark(accentShade("White")))
        assertEquals(true, readsDark(accentShade("Sand")))
        assertEquals(false, readsDark(accentShade("Red")))
        assertEquals(false, readsDark(accentShade("Grey")))
        assertEquals(false, readsDark(accentShade("Amber")))
    }
}

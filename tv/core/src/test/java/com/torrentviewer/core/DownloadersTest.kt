package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadersTest {
    @Test fun rememberedWinsWhileInstalled() =
        assertEquals("org.telegram.messenger", defaultDownloader(listOf("idm.internet.download.manager", "org.telegram.messenger"), "org.telegram.messenger"))

    @Test fun uninstalledRememberedFallsBackToKnown() =
        assertEquals("com.dv.adm", defaultDownloader(listOf("com.dv.adm", "org.telegram.messenger"), "idm.internet.download.manager"))

    @Test fun oneDmBeforeAdm() =
        assertEquals("idm.internet.download.manager", defaultDownloader(listOf("com.dv.adm", "idm.internet.download.manager"), null))

    @Test fun noDownloaderMeansPicker() = assertNull(defaultDownloader(listOf("org.telegram.messenger"), null))

    @Test fun onlyDownloadersInTheSheet() {
        assertEquals(true, looksLikeDownloader("idm.internet.download.manager.plus", "1DM+"))
        assertEquals(true, looksLikeDownloader("com.example.grabber", "Grabber!"))
        assertEquals(true, looksLikeDownloader("com.x.y", "Advanced Download Manager"))
        assertEquals(false, looksLikeDownloader("com.samsung.android.calendar", "Calendar"))
        assertEquals(false, looksLikeDownloader("com.google.android.apps.messaging", "Messages"))
        assertEquals(false, looksLikeDownloader("com.anthropic.claude", "Claude"))
        assertEquals(false, looksLikeDownloader("com.google.android.marvin.talkback", "Android Accessibility Suite"))
    }
}

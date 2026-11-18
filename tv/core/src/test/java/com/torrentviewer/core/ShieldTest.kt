package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Browser's blocklist and the VPN's config rewriting. */
class ShieldTest {
    @Test fun hostsList() {
        val text = """
            # Title: StevenBlack/hosts
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.Example.com # comment
            0.0.0.0 tracker.net
            ::1 ip6-localhost
            plain-domain.org
        """.trimIndent()
        assertEquals(setOf("ads.example.com", "tracker.net", "plain-domain.org"), parseHostsList(text))
    }

    @Test fun parentsBlock() {
        val set = setOf("ads.com", "x.tracker.net")
        assertTrue(hostBlocked("a.b.ads.com", set))
        assertTrue(hostBlocked("X.Tracker.net", set))
        assertFalse(hostBlocked("tracker.net", set))
        assertFalse(hostBlocked("com", set))
    }

    private val conf = """
        [Interface]
        PrivateKey = abc=
        Address = 10.64.1.2/32
        DNS = 10.64.0.1
        ExcludedApplications = com.other

        [Peer]
        PublicKey = def=
        AllowedIPs = 0.0.0.0/0,::0/0
        Endpoint = 185.65.134.1:51820
    """.trimIndent()

    @Test fun onlyThisApp() {
        val out = wireguardForApp(conf, "com.hwezah.watchhub", onlyApp = true)
        assertTrue("IncludedApplications = com.hwezah.watchhub" in out)
        assertFalse("ExcludedApplications" in out)
        // Added inside [Interface], before [Peer].
        assertTrue(out.indexOf("IncludedApplications") < out.indexOf("[Peer]"))
    }

    @Test fun wholePhone() {
        val out = wireguardForApp(conf, "com.hwezah.watchhub", onlyApp = false)
        assertFalse("IncludedApplications" in out)
        assertFalse("ExcludedApplications" in out)
        assertTrue("PrivateKey = abc=" in out)
    }

    @Test fun keepAlive() {
        assertTrue("PersistentKeepalive = 25" in wireguardForApp(conf, "x", onlyApp = true))
        val own = conf + "\nPersistentKeepalive = 10"
        val out = wireguardForApp(own, "x", onlyApp = true)
        assertEquals(1, Regex("PersistentKeepalive").findAll(out).count())
    }

    @Test fun server() {
        assertEquals("185.65.134.1", wireguardServer(conf))
        assertEquals("2a03:1b20::1", wireguardServer("[Peer]\nEndpoint = [2a03:1b20::1]:51820"))
        assertEquals("se-got-wg-001.relays.mullvad.net", wireguardServer("Endpoint = se-got-wg-001.relays.mullvad.net:51820"))
        assertNull(wireguardServer("[Interface]"))
    }
}

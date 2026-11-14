package com.torrentviewer.mobile.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading DoH answers, and which hosts need no lookup. */
class SecureDnsTest {
    @Test fun addressesOnlyNotAliases() {
        val json = """{"Status":0,"Answer":[{"name":"a.example.","type":5,"TTL":60,"data":"b.example."},
            {"name":"b.example.","type":1,"TTL":120,"data":"104.21.3.4"},{"name":"b.example.","type":28,"TTL":90,"data":"2606:4700::1"}]}"""
        assertEquals(listOf("104.21.3.4" to 120, "2606:4700::1" to 90), parseDohAnswers(json))
    }

    @Test fun noAnswerOrBadJsonIsEmpty() {
        assertEquals(emptyList<Pair<String, Int>>(), parseDohAnswers("""{"Status":3}"""))
        assertEquals(emptyList<Pair<String, Int>>(), parseDohAnswers("not json"))
    }

    @Test fun literalsAndEndpoints() {
        assertTrue(isIpLiteral("1.1.1.1"))
        assertTrue(isIpLiteral("2606:4700::1"))
        assertFalse(isIpLiteral("hubcloud.example"))
        assertEquals("https://1.1.1.1/dns-query", dohEndpoint("cloudflare"))
        assertNull(dohEndpoint(""))
    }
}

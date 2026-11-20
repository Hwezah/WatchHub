package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the rolling phone build's release. */
class PhoneBuildTest {
    private val files = "https://github.com/Hwezah/WatchHub/releases/download/phone-latest/"
    private fun json(body: String) = """{"tag_name":"phone-latest","body":"$body","assets":[
        {"name":"WatchHub-phone-arm64-v8a.apk","size":50,"digest":"sha256:aa","browser_download_url":"${files}WatchHub-phone-arm64-v8a.apk"},
        {"name":"WatchHub-phone-universal.apk","size":90,"digest":"sha256:bb","browser_download_url":"${files}WatchHub-phone-universal.apk"}]}"""

    @Test fun codeAndTheRightFile() {
        val (code, r) = parsePhoneBuild(json("built at 07c9aeb, versionCode 157. Most phones"), listOf("arm64-v8a"), "WatchHub-phone-")!!
        assertEquals(157, code)
        assertEquals("build 57", r.version)
        assertEquals("${files}WatchHub-phone-arm64-v8a.apk", r.url)
        assertEquals("aa", r.sha256)
        assertEquals("${files}WatchHub-phone-universal.apk", parsePhoneBuild(json("versionCode 157"), listOf("x86_64"), "WatchHub-phone-")!!.second.url)
    }

    @Test fun summaryFromTheNotesAndTheVersionFile() {
        // The notes' "Summary:" line (a JSON "\n" in the body), its area prefix dropped.
        assertEquals("Posters without the outline", parsePhoneBuild(json("versionCode 157. Most phones.\\n\\nSummary: Phone: Posters without the outline"), listOf("arm64-v8a"), "WatchHub-phone-")!!.second.summary)
        assertNull(parsePhoneBuild(json("versionCode 157"), listOf("arm64-v8a"), "WatchHub-phone-")!!.second.summary)
        val v = """{"versionCode":168,"summary":"Core: Layout under Video","files":{"WatchHub-phone-arm64-v8a.apk":{"size":50,"sha256":"cc"}}}"""
        assertEquals("Layout under Video", parsePhoneVersion(v, listOf("arm64-v8a"), "WatchHub-phone-")!!.second.summary)
        assertEquals("Fix the dock", buildSummary("Fix the dock"))
        assertNull(buildSummary("  "))
        assertNull(buildSummary(null))
    }

    @Test fun notesWithoutACodeOfferNothing() {
        assertNull(parsePhoneBuild(json("Latest phone build"), listOf("arm64-v8a"), "WatchHub-phone-"))
    }

    @Test fun versionFileWhenTheApiWontAnswer() {
        val v = """{"versionCode":168,"files":{"WatchHub-phone-arm64-v8a.apk":{"size":50,"sha256":"cc"},"WatchHub-phone-universal.apk":{"size":90,"sha256":"dd"}}}"""
        val (code, r) = parsePhoneVersion(v, listOf("arm64-v8a"), "WatchHub-phone-")!!
        assertEquals(168, code)
        assertEquals("build 68", r.version)
        assertEquals("${files}WatchHub-phone-arm64-v8a.apk", r.url)
        assertEquals("cc", r.sha256)
        assertEquals("${files}WatchHub-phone-universal.apk", parsePhoneVersion(v, listOf("x86_64"), "WatchHub-phone-")!!.second.url)
        assertNull(parsePhoneVersion("""{"versionCode":168,"files":{}}""", listOf("arm64-v8a"), "WatchHub-phone-"))
    }
}

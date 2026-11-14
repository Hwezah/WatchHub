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

    @Test fun notesWithoutACodeOfferNothing() {
        assertNull(parsePhoneBuild(json("Latest phone build"), listOf("arm64-v8a"), "WatchHub-phone-"))
    }
}

package com.torrentviewer.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each AI provider's request and reply, and reading the titles out of an answer. */
class AiSearchTest {
    @Test fun claudeRequest() {
        val r = aiRequest(AiProvider.CLAUDE, "", "sk-test", "hello")
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("sk-test", r.headers["x-api-key"])
        assertEquals("2026-10-21", r.headers["anthropic-version"])
        val body = JSONObject(r.body)
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals("hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse("sk-test" in r.url)
    }

    @Test fun otherRequests() {
        val g = aiRequest(AiProvider.GEMINI, "gemini-x", "g-key", "hi")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent", g.url)
        assertEquals("g-key", g.headers["x-goog-api-key"])
        val d = aiRequest(AiProvider.DEEPSEEK, " ", "d-key", "hi")
        assertEquals("Bearer d-key", d.headers["Authorization"])
        assertEquals("deepseek-chat", JSONObject(d.body).getString("model"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", aiRequest(AiProvider.OPENROUTER, "", "k", "hi").url)
    }

    @Test fun replies() {
        assertEquals("[1]", aiReplyText(AiProvider.CLAUDE, """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"[1]"}]}"""))
        assertEquals("[2]", aiReplyText(AiProvider.GEMINI, """{"candidates":[{"content":{"parts":[{"text":"[2]"}]}}]}"""))
        assertEquals("[3]", aiReplyText(AiProvider.OPENAI, """{"choices":[{"message":{"content":"[3]"}}]}"""))
        val refused = runCatching { aiReplyText(AiProvider.CLAUDE, """{"stop_reason":"refusal","content":[]}""") }
        assertTrue(refused.isFailure)
    }

    @Test fun guessesFromAnswers() {
        val text = "Sure!\n```json\n[{\"title\": \"Groundhog Day\", \"year\": 1993, \"type\": \"movie\"}, {\"title\": \"Russian Doll\", \"year\": \"2019\", \"type\": \"series\"}, {\"year\": 2000}]\n```"
        assertEquals(listOf(AiGuess("Groundhog Day", 1993, false), AiGuess("Russian Doll", 2019, true)), aiGuesses(text))
        assertEquals(emptyList<AiGuess>(), aiGuesses("I'm not sure."))
    }

    @Test fun descriptionsAndErrors() {
        assertTrue(looksLikeDescription("man relives the same day"))
        assertFalse(looksLikeDescription("dope thief"))
        assertEquals("Claude didn't accept the key — check it in Settings → AI Search", aiErrorText(AiProvider.CLAUDE, 401, ""))
        assertEquals("Gemini: quota gone", aiErrorText(AiProvider.GEMINI, 400, """{"error":{"message":"quota gone"}}"""))
    }
}

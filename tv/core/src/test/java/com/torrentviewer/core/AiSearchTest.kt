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

    /** A retired default: the newest plain model the key may use. */
    @Test fun bestModelFromTheList() {
        val gemini = """{"models":[
            {"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.0-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-3.0-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.5-flash-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        assertEquals("gemini-3.0-flash", aiBestModel(AiProvider.GEMINI, gemini))
        assertEquals("gemini-flash-latest", aiBestModel(AiProvider.GEMINI, """{"models":[{"name":"models/gemini-flash-latest"},{"name":"models/gemini-3.0-flash"}]}"""))
        val openai = """{"data":[{"id":"gpt-4.1-mini"},{"id":"gpt-5-mini"},{"id":"gpt-5-mini-tts"},{"id":"gpt-realtime-mini"},{"id":"gpt-5"}]}"""
        assertEquals("gpt-5-mini", aiBestModel(AiProvider.OPENAI, openai))
        assertEquals("deepseek-chat", aiBestModel(AiProvider.DEEPSEEK, """{"data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"}]}"""))
        assertEquals(null, aiBestModel(AiProvider.GEMINI, "not json"))
    }

    /** Gemini is asked not to think first, spelled each generation's way. */
    @Test fun geminiThinking() {
        fun config(t: AiThinking) = JSONObject(aiRequest(AiProvider.GEMINI, "", "k", "hi", t).body).getJSONObject("generationConfig")
        assertEquals("minimal", config(AiThinking.MINIMAL).getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals(0, config(AiThinking.ZERO).getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertFalse(config(AiThinking.DEFAULT).has("thinkingConfig"))
    }

    @Test fun busyRepliesAreRetried() {
        assertEquals(true, aiBusy(503))
        assertEquals(true, aiBusy(429))
        assertEquals(false, aiBusy(401))
        assertEquals(false, aiBusy(404))
    }

    @Test fun guessIds() {
        val g = aiGuesses("""[{"title":"Interstellar","year":2014,"type":"movie","imdb":"tt0816692"},{"title":"X","imdb":"unknown"}]""")
        assertEquals("tt0816692", g[0].imdb)
        assertEquals(null, g[1].imdb)
    }

    @Test fun forgivingMatches() {
        fun t(id: String, name: String, year: String, kind: String = "movie") = Title(id, kind, name, year = year)
        // Spelt its own way.
        val spider = t("tt1", "Spider-Man: Into the Spider-Verse", "2018")
        assertEquals(spider, aiMatch(listOf(listOf(t("tt9", "Spider-Man", "2002"), spider)), AiGuess("Spiderman Into the Spider Verse", 2018, false)))
        // Same name: the year settles it.
        val dune = t("tt2", "Dune", "2021")
        assertEquals(dune, aiMatch(listOf(listOf(t("tt3", "Dune", "1984"), dune)), AiGuess("Dune", 2021, false)))
        // A series, though the AI called it a film.
        val office = t("tt4", "The Office", "2005–2013", "tv")
        assertEquals(office, aiMatch(listOf(emptyList(), listOf(office)), AiGuess("The Office", 2005, false)))
        // Another of its names: the search's first answer, from that very year.
        val edge = t("tt5", "Edge of Tomorrow", "2014")
        assertEquals(edge, aiMatch(listOf(listOf(edge)), AiGuess("Live Die Repeat", 2014, false)))
        // Nothing like it.
        assertEquals(null, aiMatch(listOf(listOf(t("tt6", "Cars", "2006"))), AiGuess("Nonexistent Picture", 1999, false)))
    }
}

package com.torrentviewer.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * AI search: the viewer describes a film or show ("the one where a man relives the same day") and an AI model
 * the viewer has a key for names its best guesses, which are then looked up in Cinemeta like any search. The
 * viewer's own key, on their own account: the app has none. Requests go straight from the phone to the provider.
 * Building each provider's request and reading its reply are pure functions, tested on the JVM.
 */

/**
 * The AI services offered, each with the model asked when the viewer names none. Models change often: the
 * viewer can type another in Settings → AI Search. ponytail: defaults other than Claude's are the long-lived
 * names known when this was written; revisit them when a provider retires one.
 */
enum class AiProvider(val id: String, val label: String, val defaultModel: String, val keyFrom: String) {
    CLAUDE("claude", "Claude", "claude-opus-5-5", "console.anthropic.com"),
    GEMINI("gemini", "Gemini", "gemini-2.5-flash", "aistudio.google.com"),
    OPENAI("openai", "ChatGPT", "gpt-4.1-mini", "platform.openai.com"),
    DEEPSEEK("deepseek", "DeepSeek", "deepseek-chat", "platform.deepseek.com"),
    OPENROUTER("openrouter", "OpenRouter", "openrouter/auto", "openrouter.ai"),
    ;

    companion object {
        fun of(id: String): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

/** One HTTP request to a provider, ready to send. [headers] carry the key: never log them. */
data class AiRequest(val url: String, val headers: Map<String, String>, val body: String)

/** A title the model named. [series] is null when it didn't say. */
data class AiGuess(val title: String, val year: Int?, val series: Boolean?)

/** What the model is asked: only a JSON list, so the reply can be read without a second guess at its shape. */
fun aiPrompt(description: String): String =
    "Someone is trying to find a film or TV show. Their description:\n\n\"${description.trim()}\"\n\n" +
        "Reply with only a JSON array of up to 8 titles that best fit it, most likely first, each as " +
        "{\"title\": \"<official English title>\", \"year\": <first release year>, \"type\": \"movie\" or \"series\"}. " +
        "No other text."

/**
 * The request for [provider]: Claude's Messages API, Gemini's generateContent, or the OpenAI chat format (ChatGPT,
 * DeepSeek and OpenRouter all speak it). Every key goes in a header, never the URL, so no log line can show it.
 */
fun aiRequest(provider: AiProvider, model: String, key: String, prompt: String): AiRequest {
    val m = model.trim().ifEmpty { provider.defaultModel }
    return when (provider) {
        AiProvider.CLAUDE -> AiRequest(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2026-10-21",
                // A declined request is re-run on a fallback model in the same call, instead of coming back empty.
                "anthropic-beta" to "server-side-fallback-2026-07-01",
            ),
            JSONObject()
                .put("model", m)
                .put("max_tokens", 4000)
                // A short list from a description: the lowest effort answers fastest, and well enough.
                .put("output_config", JSONObject().put("effort", "low"))
                .put("fallbacks", "default")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .toString(),
        )
        AiProvider.GEMINI -> AiRequest(
            "https://generativelanguage.googleapis.com/v1beta/models/${enc(m)}:generateContent",
            mapOf("x-goog-api-key" to key),
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                .toString(),
        )
        AiProvider.OPENAI, AiProvider.DEEPSEEK, AiProvider.OPENROUTER -> AiRequest(
            when (provider) {
                AiProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
                AiProvider.DEEPSEEK -> "https://api.deepseek.com/chat/completions"
                else -> "https://openrouter.ai/api/v1/chat/completions"
            },
            mapOf("Authorization" to "Bearer $key"),
            JSONObject()
                .put("model", m)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .toString(),
        )
    }
}

/** The model's answer text from [provider]'s reply. Throws, with a message to show, when it declined or said nothing. */
fun aiReplyText(provider: AiProvider, reply: String): String {
    val o = JSONObject(reply)
    val text = when (provider) {
        AiProvider.CLAUDE -> {
            if (o.optString("stop_reason") == "refusal") throw IllegalStateException("Claude declined this one — try describing it differently")
            val parts = o.optJSONArray("content") ?: JSONArray()
            (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        }
        AiProvider.GEMINI -> {
            val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }
        }
        else -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }
    if (text.isBlank()) throw IllegalStateException("${provider.label} gave no answer")
    return text
}

/**
 * The titles in the model's answer: the JSON list it was asked for, found even inside code fences or a sentence
 * around it. Entries without a title are dropped; at most 8.
 */
fun aiGuesses(text: String): List<AiGuess> {
    val start = text.indexOf('[')
    val end = text.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    val list = runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
        val o = list.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val year = o.opt("year")?.toString()?.take(4)?.toIntOrNull()
        val series = when (o.optString("type").lowercase()) {
            "series", "tv", "show", "tv series", "tv show" -> true
            "movie", "film" -> false
            else -> null
        }
        AiGuess(title, year, series)
    }.take(8)
}

/** A provider's error reply, as a sentence: its own message where it gave one. */
fun aiErrorText(provider: AiProvider, code: Int, body: String): String {
    val said = runCatching {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
    return when (code) {
        401, 403 -> "${provider.label} didn't accept the key — check it in Settings → AI Search"
        404 -> "${provider.label} doesn't know that model — check it in Settings → AI Search"
        429 -> "${provider.label}: too many requests or no credit left on the account"
        else -> "${provider.label}: ${said ?: "error $code"}"
    }.take(200)
}

/** Whether typed text reads like a description rather than a title: four words or more. */
fun looksLikeDescription(query: String): Boolean = query.trim().split(Regex("\\s+")).count { it.length > 1 } >= 4

/**
 * The viewer's chosen AI and its key. The provider and model are plain settings; each provider's key is kept
 * encrypted in [Secrets], so switching provider keeps the others' keys.
 */
object AiSearch {
    private val _ready = MutableStateFlow(false)
    /** A provider is chosen and has a key: AI suggestions can run. */
    val ready: StateFlow<Boolean> = _ready

    private fun keyName(p: AiProvider) = "ai-key:${p.id}"

    fun provider(): AiProvider? = AiProvider.of(SettingsStore.value.value.aiProvider)

    fun hasKey(p: AiProvider): Boolean = !Secrets.get(keyName(p)).isNullOrBlank()

    /** Blank forgets it. */
    fun setKey(p: AiProvider, key: String) {
        Secrets.put(keyName(p), key.trim().ifEmpty { null })
        refresh()
    }

    /** Works out [ready] again: after the provider or a key changes, and at launch. */
    fun refresh() {
        _ready.value = provider()?.let(::hasKey) == true
    }

    /**
     * Titles the AI thinks [description] means, as Cinemeta knows them, most likely first. Throws with a message
     * to show when the provider fails.
     */
    suspend fun suggest(description: String): List<Title> {
        val p = provider() ?: throw IllegalStateException("Choose an AI in Settings → AI Search")
        val key = Secrets.get(keyName(p)) ?: throw IllegalStateException("Add your ${p.label} key in Settings → AI Search")
        val req = aiRequest(p, SettingsStore.value.value.aiModel, key, aiPrompt(description))
        val reply = try {
            Net.postJson(req.url, req.body, timeoutMs = 60_000, quiet = true, headers = req.headers)
        } catch (e: HttpException) {
            throw IllegalStateException(aiErrorText(p, e.code, e.body))
        }
        val guesses = aiGuesses(aiReplyText(p, reply))
        return coroutineScope {
            guesses.map { g ->
                async {
                    val kinds = when (g.series) {
                        true -> listOf("tv")
                        false -> listOf("movie")
                        null -> listOf("movie", "tv")
                    }
                    kinds.firstNotNullOfOrNull { k -> bestMatch(Meta.search(g.title, k), g.title, g.year?.toString()) }
                }
            }.awaitAll().filterNotNull().distinctBy { it.imdb }
        }
    }
}

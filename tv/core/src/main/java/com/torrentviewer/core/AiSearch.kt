package com.torrentviewer.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    // Google's alias for its current Flash model: it moves to each new one, so it isn't retired under the viewer.
    GEMINI("gemini", "Gemini", "gemini-flash-latest", "aistudio.google.com"),
    OPENAI("openai", "ChatGPT", "gpt-4.1-mini", "platform.openai.com"),
    DEEPSEEK("deepseek", "DeepSeek", "deepseek-chat", "platform.deepseek.com"),
    OPENROUTER("openrouter", "OpenRouter", "openrouter/auto", "openrouter.ai"),
    ;

    companion object {
        fun of(id: String): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How a Gemini model is told not to think first: newer generations take a level ("minimal"), older ones a budget
 * of zero, and some neither. Tried in this order; what a model accepted is remembered.
 */
enum class AiThinking { MINIMAL, ZERO, DEFAULT }

/** One HTTP request to a provider, ready to send. [headers] carry the key: never log them. */
data class AiRequest(val url: String, val headers: Map<String, String>, val body: String)

/** A title the model named. [series] is null when it didn't say; [imdb] is its IMDb id, when it gave one that looks like one. */
data class AiGuess(val title: String, val year: Int?, val series: Boolean?, val imdb: String? = null)

/** What the model is asked: only a JSON list, so the reply can be read without a second guess at its shape. */
fun aiPrompt(description: String): String =
    "Someone is trying to find a film or TV show. Their description:\n\n\"${description.trim()}\"\n\n" +
        "Reply with only a JSON array of up to 6 titles that best fit it, most likely first, each as " +
        "{\"title\": \"<official English title>\", \"year\": <first release year>, \"type\": \"movie\" or \"series\", " +
        "\"imdb\": \"<its IMDb id, tt followed by digits, only if you are sure of it>\"}. " +
        "No other text."

/**
 * The request for [provider]: Claude's Messages API, Gemini's generateContent, or the OpenAI chat format (ChatGPT,
 * DeepSeek and OpenRouter all speak it). Every key goes in a header, never the URL, so no log line can show it.
 * [thinking] is how Gemini is asked to skip its thinking step ([AiThinking]).
 */
fun aiRequest(provider: AiProvider, model: String, key: String, prompt: String, thinking: AiThinking = AiThinking.MINIMAL): AiRequest {
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
                // No "thinking" before the answer — naming a film from a description doesn't need it, and it is most of
                // the wait. Gemini's generations spell the setting differently ([AiThinking]); one that refuses a
                // spelling answers 400 and is asked the next way (AiSearch.complete).
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").apply {
                    when (thinking) {
                        AiThinking.MINIMAL -> put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
                        AiThinking.ZERO -> put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                        AiThinking.DEFAULT -> Unit
                    }
                })
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

/**
 * The request listing the models [key] may use, for when the one asked for is unknown (retired or renamed): Gemini's
 * and the OpenAI-format services'. Null for Claude, whose default is the current model.
 */
fun aiModelsRequest(provider: AiProvider, key: String): AiRequest? = when (provider) {
    AiProvider.GEMINI -> AiRequest("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to key), "")
    AiProvider.OPENAI -> AiRequest("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $key"), "")
    AiProvider.DEEPSEEK -> AiRequest("https://api.deepseek.com/models", mapOf("Authorization" to "Bearer $key"), "")
    AiProvider.OPENROUTER, AiProvider.CLAUDE -> null
}

private val VERSION = Regex("""(\d+(?:\.\d+)?)""")
/** Words in a model's name that mean it isn't a plain text model, or isn't a stable one. */
private val NOT_PLAIN = Regex("""lite|nano|preview|exp|image|tts|audio|live|realtime|transcribe|search|embed|vision|thinking|instruct|codex|learnlm|gemma|aqa|veo|imagen""", RegexOption.IGNORE_CASE)

/**
 * The model to ask instead, from [provider]'s list of models ([aiModelsRequest]'s reply): Gemini's own "latest
 * Flash" alias where listed, else the newest plain Flash; ChatGPT's newest plain "mini"; DeepSeek's chat model.
 * Null when nothing fits.
 */
fun aiBestModel(provider: AiProvider, reply: String): String? {
    val o = runCatching { JSONObject(reply) }.getOrNull() ?: return null
    fun newest(names: List<String>) = names.maxByOrNull { VERSION.find(it)?.value?.toDoubleOrNull() ?: 0.0 }
    return when (provider) {
        AiProvider.GEMINI -> {
            val list = o.optJSONArray("models") ?: return null
            val names = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } != false }
                .map { it.optString("name").removePrefix("models/") }.filter { it.startsWith("gemini") }
            names.firstOrNull { it == "gemini-flash-latest" }
                ?: newest(names.filter { "flash" in it && !NOT_PLAIN.containsMatchIn(it) })
                ?: newest(names.filter { !NOT_PLAIN.containsMatchIn(it) })
                ?: names.firstOrNull()
        }
        else -> {
            val list = o.optJSONArray("data") ?: return null
            val ids = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id") }
            when (provider) {
                AiProvider.OPENAI -> newest(ids.filter { it.startsWith("gpt-") && it.endsWith("-mini") && !NOT_PLAIN.containsMatchIn(it.removeSuffix("-mini")) })
                    ?: newest(ids.filter { it.startsWith("gpt-") && !NOT_PLAIN.containsMatchIn(it) })
                else -> ids.firstOrNull { it == "deepseek-chat" } ?: ids.firstOrNull()
            }
        }
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
        AiGuess(title, year, series, o.optString("imdb").trim().takeIf { IMDB_ID.matches(it) })
    }.take(8)
}

private val IMDB_ID = Regex("""^tt\d{5,10}$""")

/**
 * Which of the catalogue's answers is the title the AI meant, forgiving how it was spelt: the closest name
 * ([fuzzyScore] — "Spiderman Into the Spider Verse" is "Spider-Man: Into the Spider-Verse"), nudged by how near its
 * year is and whether it is a film or a series as the AI said. Failing that, a search's own first two answers when
 * one came out that very year: the AI used another of its names ("Live Die Repeat" for "Edge of Tomorrow").
 * [results] is each search's answers, in the catalogue's order. Null when none is it.
 */
fun aiMatch(results: List<List<Title>>, guess: AiGuess): Title? {
    fun yearOf(t: Title) = t.year?.take(4)?.toIntOrNull()
    val all = results.flatten().distinctBy { it.imdb }
    val best = all.map { t ->
        var score = fuzzyScore(t.title, guess.title)
        val y = yearOf(t)
        if (guess.year != null && y != null) score += when (kotlin.math.abs(y - guess.year)) {
            0 -> 0.12
            1 -> 0.06
            2, 3 -> 0.0
            else -> -0.2
        }
        if (guess.series != null) score += if ((t.kind == "tv") == guess.series) 0.05 else -0.05
        t to score
    }.filter { it.second >= AI_MATCH }.maxByOrNull { it.second }?.first
    return best ?: guess.year?.let { y -> results.flatMap { it.take(2) }.firstOrNull { yearOf(it) == y } }
}

/** Close enough, with the year's and kind's nudges, to be the title the AI named. */
const val AI_MATCH = 0.5

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
        429 -> "${provider.label}: too many requests (its lighter models too) or no credit left — try again in a minute"
        500, 502, 503, 504, 529 -> "${provider.label} is busy right now (its lighter models too) — try again in a minute"
        else -> "${provider.label}: ${said ?: "error $code"}"
    }.take(200)
}

/**
 * A reply that says the service is busy or out of its per-minute allowance, not that the request was wrong:
 * worth a second try, or another model ("This model is currently experiencing high demand" is Gemini's 503).
 */
fun aiBusy(code: Int) = code in setOf(429, 500, 502, 503, 504, 529)

/**
 * Gemini's lighter models, asked in turn when the one in use is busy: each has its own capacity and its own free
 * allowance, so they are often free while the main one is swamped.
 */
val GEMINI_WHEN_BUSY = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash")

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

    private fun foundKey(p: AiProvider) = "ai-model-found:${p.id}"

    /** The best model [key] may use on [p] ([aiBestModel]), or null when the list can't be had. */
    private suspend fun discover(p: AiProvider, key: String): String? {
        val req = aiModelsRequest(p, key) ?: return null
        return runCatching { aiBestModel(p, Net.get(req.url, quiet = true, headers = req.headers)) }.getOrNull()
    }

    /** The model in use when the viewer named none: the default, or one found when the default was unknown. */
    fun modelInUse(p: AiProvider): String = SettingsStore.value.value.aiModel.ifBlank { Kv.global(foundKey(p)) ?: p.defaultModel }

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
    /** One request and its answer's text; a connection cut before the answer is asked once more, then said in plain words. */
    private suspend fun askOnce(p: AiProvider, m: String, key: String, prompt: String, thinking: AiThinking, timeoutMs: Int): String {
        val req = aiRequest(p, m, key, prompt, thinking)
        // A connection cut before the answer (the app left the screen, the network switched) is asked once more;
        // a second cut is said in plain words. The service's own errors (HttpException) go straight up.
        repeat(2) { attempt ->
            try {
                return aiReplyText(p, Net.postJson(req.url, req.body, timeoutMs = timeoutMs, quiet = true, headers = req.headers))
            } catch (e: HttpException) {
                throw e
            } catch (e: java.io.IOException) {
                if (attempt == 1) throw IllegalStateException(
                    "The connection to ${p.label} dropped before it answered — try again, and keep WatchHub open while it thinks",
                )
                kotlinx.coroutines.delay(1500)
            }
        }
        error("unreachable")
    }

    /**
     * One question to [p], answered: the reply's text and the model that gave it. The viewer's model, else one found
     * earlier for this service, else the default; an unknown default makes it ask the service which models the key
     * may use and switch to the newest. Throws with a message to show.
     */
    private suspend fun complete(p: AiProvider, key: String, prompt: String, timeoutMs: Int = 30_000, quick: Boolean = false): Pair<String, String> {
        val typed = SettingsStore.value.value.aiModel
        val usual = typed.ifBlank { Kv.global(foundKey(p)).orEmpty() }
        // [quick], with no model typed: Gemini's light model first — a long read written up several times faster.
        val model = if (quick && typed.isBlank() && p == AiProvider.GEMINI) GEMINI_WHEN_BUSY.first() else usual
        suspend fun ask(m: String): String {
            if (p != AiProvider.GEMINI) return askOnce(p, m, key, prompt, AiThinking.DEFAULT, timeoutMs)
            // From the way this model last accepted, so a refused spelling costs a round trip once, not every search.
            val name = m.ifBlank { p.defaultModel }
            val known = Kv.global("ai-thinking:$name")?.let { saved -> AiThinking.entries.firstOrNull { it.name == saved } }
            val ways = AiThinking.entries.dropWhile { known != null && it != known }
            for ((i, way) in ways.withIndex()) {
                try {
                    return askOnce(p, m, key, prompt, way, timeoutMs).also { if (way != known) Kv.setGlobal("ai-thinking:$name", way.name) }
                } catch (e: HttpException) {
                    if (e.code != 400 || i == ways.lastIndex) throw e
                }
            }
            error("unreachable")
        }
        /**
         * [m], and if the service is busy ([aiBusy]): the same again after a moment, then — for Gemini — its lighter
         * models in turn ([GEMINI_WHEN_BUSY]). The reply and the model that gave it; the first refusal if all are busy.
         */
        suspend fun askPatiently(m: String): Pair<String, String> {
            val name = m.ifBlank { p.defaultModel }
            val first = try {
                return ask(m) to name
            } catch (e: HttpException) {
                if (!aiBusy(e.code)) throw e
                e
            }
            kotlinx.coroutines.delay(1500)
            try {
                return ask(m) to name
            } catch (e: HttpException) {
                if (!aiBusy(e.code) || p != AiProvider.GEMINI) throw e
            }
            for (other in GEMINI_WHEN_BUSY.filter { it != name }) {
                try {
                    return ask(other) to other
                } catch (e: HttpException) {
                    // Busy too, or not offered to this key: on to the next.
                    if (!aiBusy(e.code) && e.code != 404 && e.code != 400) throw e
                }
            }
            throw first
        }
        return try {
            askPatiently(model)
        } catch (e: HttpException) {
            // The light model not offered to this key: the usual one.
            if (model != usual && e.code in setOf(400, 404)) return try {
                askPatiently(usual)
            } catch (again: HttpException) {
                throw IllegalStateException(aiErrorText(p, again.code, again.body))
            }
            val better = if (e.code == 404 && typed.isBlank()) discover(p, key)?.takeIf { it != model.ifBlank { p.defaultModel } } else null
            if (better == null) throw IllegalStateException(aiErrorText(p, e.code, e.body))
            Kv.setGlobal(foundKey(p), better)
            try {
                askPatiently(better)
            } catch (again: HttpException) {
                throw IllegalStateException(aiErrorText(p, again.code, again.body))
            }
        }
    }

    /**
     * Any question to the viewer's AI, its answer's text: a film's scene-by-scene breakdown ([Breakdowns]). The same
     * patience as search — busy services tried again, lighter models — with [timeoutMs] for a long read. [quick] asks
     * Gemini's light model first (unless the viewer typed a model): much faster at writing a long answer.
     * Throws with a message to show.
     */
    suspend fun ask(prompt: String, timeoutMs: Int = 30_000, quick: Boolean = false): String {
        val p = provider() ?: throw IllegalStateException("Choose an AI in Settings → AI Search")
        val key = Secrets.get(keyName(p)) ?: throw IllegalStateException("Add your ${p.label} key in Settings → AI Search")
        return complete(p, key, prompt, timeoutMs, quick).first
    }

    /** Settings' Test Key: a one-word question, so the viewer knows the key and model answer. Which model, and how fast. */
    suspend fun test(p: AiProvider): String {
        val key = Secrets.get(keyName(p)) ?: throw IllegalStateException("Save your ${p.label} key first")
        val start = System.currentTimeMillis()
        val model = complete(p, key, "Reply with the single word OK.").second
        return "$model answered in ${"%.1f".format((System.currentTimeMillis() - start) / 1000.0)} s"
    }

    /**
     * The catalogue's title for one of the AI's guesses, found however it was named: films and series both (the AI
     * gets that wrong, a mini-series most of all), the name before any subtitle too, then the IMDb id it gave if its
     * name and year agree, then the addons' own search. Null when nothing is it.
     */
    private suspend fun find(g: AiGuess): Title? = coroutineScope {
        val names = listOfNotNull(g.title, g.title.substringBefore(':').trim().takeIf { it.length >= 3 && it != g.title })
        val kinds = if (g.series == true) listOf("tv", "movie") else listOf("movie", "tv")
        val results = names.flatMap { n -> kinds.map { k -> async { Meta.search(n, k) } } }.awaitAll()
        aiMatch(results, g)
            ?: g.imdb?.let { id ->
                // Only if the id is the film named: a model can be sure of an id and wrong about it.
                kinds.firstNotNullOfOrNull { k -> runCatching { Meta.title(id, k) }.getOrNull() }?.let { aiMatch(listOf(listOf(it)), g) }
            }
            ?: runCatching { aiMatch(listOf(Meta.searchAddons(g.title)), g) }.getOrNull()
    }

    suspend fun suggest(description: String, onPartial: (List<Title>) -> Unit = {}): List<Title> {
        val p = provider() ?: throw IllegalStateException("Choose an AI in Settings → AI Search")
        val key = Secrets.get(keyName(p)) ?: throw IllegalStateException("Add your ${p.label} key in Settings → AI Search")
        val reply = complete(p, key, aiPrompt(description)).first
        val guesses = aiGuesses(reply)
        // Each guess is looked up on its own and shown as soon as it's found ([onPartial]), in the AI's order.
        val found = arrayOfNulls<Title>(guesses.size)
        fun now() = synchronized(found) { found.filterNotNull().distinctBy { it.imdb } }
        coroutineScope {
            guesses.forEachIndexed { i, g ->
                launch {
                    val title = find(g) ?: return@launch
                    synchronized(found) { found[i] = title }
                    onPartial(now())
                }
            }
        }
        return now()
    }
}

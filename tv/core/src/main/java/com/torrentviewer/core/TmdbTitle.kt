package com.torrentviewer.core

import org.json.JSONObject

private const val IMAGES = "https://image.tmdb.org/t/p"

/** Talk shows and news: an actor's every guest spot, and nothing anyone is looking for. */
private val NOT_SHOWS = setOf(10767, 10763)

data class CastMember(val id: Int, val name: String, val role: String?, val photo: String?)

/** A title known by TMDB's id: its IMDb id, which the app goes by, is asked for only when it's opened. */
data class TmdbCard(val id: Int, val kind: String, val title: String, val poster: String?, val year: String?)

/** [directors]: who made it, for their pages — a film's director, a series' creators. */
data class TmdbTitle(val certificate: String?, val cast: List<CastMember>, val similar: List<TmdbCard>, val directors: List<CastMember> = emptyList())

/** "Jonathan Demme" and "jonathan demme" are one name: only letters and digits count. */
fun sameName(a: String, b: String): Boolean {
    fun squash(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
    return squash(a).isNotEmpty() && squash(a) == squash(b)
}

/**
 * A person's page. [credits]: most popular first (the phone's "Popular" row); [byYear]: the same, newest
 * first, unknown years last (its grid). Dates are TMDB's yyyy-MM-dd.
 */
data class TmdbPerson(
    val name: String,
    val photo: String?,
    val knownFor: String?,
    val about: String?,
    val credits: List<TmdbCard>,
    val birthday: String? = null,
    val deathday: String? = null,
    val birthplace: String? = null,
) {
    val byYear get() = credits.sortedWith(compareBy<TmdbCard> { it.year == null }.thenByDescending { it.year })
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "1964-06-12" → "Jun 12, 1964"; null when it isn't a date. */
fun prettyDate(date: String?): String? {
    val parts = date?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
    val (y, m, d) = parts
    return MONTHS.getOrNull(m - 1)?.let { "$it $d, $y" }
}

/** Full years between [birthday] and [deathday] (or [today]); null when either isn't a date. */
fun ageOf(birthday: String?, deathday: String?, today: String): Int? {
    fun parts(s: String?) = s?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
    val b = parts(birthday) ?: return null
    val e = parts(deathday ?: today) ?: return null
    val beforeBirthday = e[1] < b[1] || (e[1] == b[1] && e[2] < b[2])
    return (e[0] - b[0] - if (beforeBirthday) 1 else 0).takeIf { it >= 0 }
}

/** "Born Jun 12, 1964 (age 61)", or with a death "Jun 12, 1964 – Mar 3, 2020 (aged 55)"; null without a birthday. */
fun lifeLine(birthday: String?, deathday: String?, today: String): String? {
    val born = prettyDate(birthday) ?: return null
    val age = ageOf(birthday, deathday, today)
    val died = prettyDate(deathday)
    return if (died != null) "$born – $died" + (age?.let { " (aged $it)" } ?: "") else "Born $born" + (age?.let { " (age $it)" } ?: "")
}

private fun image(size: String, path: String?) = path?.takeIf { it.isNotBlank() }?.let { "$IMAGES/$size$it" }

private fun cardOf(o: JSONObject, kind: String): TmdbCard? {
    val poster = image("w342", o.str("poster_path")) ?: return null
    val name = o.str("title") ?: o.str("name") ?: return null
    return TmdbCard(o.optInt("id"), kind, name, poster, (o.str("release_date") ?: o.str("first_air_date"))?.take(4))
}

/** The US rating: the one set every title has. */
private fun certificateOf(o: JSONObject, kind: String): String? {
    if (kind == "tv") {
        return o.optJSONObject("content_ratings")?.optJSONArray("results")?.objects().orEmpty()
            .firstOrNull { it.optString("iso_3166_1") == "US" }?.str("rating")
    }
    return o.optJSONObject("release_dates")?.optJSONArray("results")?.objects().orEmpty()
        .firstOrNull { it.optString("iso_3166_1") == "US" }
        ?.optJSONArray("release_dates")?.objects().orEmpty()
        .firstNotNullOfOrNull { it.str("certification") }
}

/** /movie or /tv with credits, its ratings and recommendations appended. [kind] is "movie" or "tv". */
fun parseTmdbTitle(json: String, kind: String): TmdbTitle {
    val o = JSONObject(json)
    val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.objects().orEmpty().take(14).map {
        CastMember(it.optInt("id"), it.optString("name"), it.str("character"), image("w185", it.str("profile_path")))
    }
    val similar = o.optJSONObject("recommendations")?.optJSONArray("results")?.objects().orEmpty()
        .filterNot { it.optBoolean("adult") }.mapNotNull { cardOf(it, kind) }.take(15)
    val directors = (o.optJSONObject("credits")?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") == "Director" } +
        o.optJSONArray("created_by")?.objects().orEmpty())
        .map { CastMember(it.optInt("id"), it.optString("name"), null, null) }.distinctBy { it.id }
    return TmdbTitle(certificateOf(o, kind), cast, similar, directors)
}

/** /person with combined_credits appended: what they're in, best known first, each title once. */
fun parseTmdbPerson(json: String): TmdbPerson {
    val o = JSONObject(json)
    val all = o.optJSONObject("combined_credits")
    // What they acted in, and what they directed or created: a director's page is the second.
    val credits = (all?.optJSONArray("cast")?.objects().orEmpty() + all?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") in setOf("Director", "Creator") })
        .filterNot { it.optBoolean("adult") }
        .filter { c -> c.optString("media_type") in setOf("movie", "tv") && c.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).none { g.optInt(it) in NOT_SHOWS } } != false }
        .sortedByDescending { it.optDouble("popularity") }
        .mapNotNull { cardOf(it, it.optString("media_type")) }
        .distinctBy { it.kind to it.id }
        .take(60)
    return TmdbPerson(
        o.optString("name"), image("w342", o.str("profile_path")), o.str("known_for_department"), o.str("biography"), credits,
        birthday = o.str("birthday"), deathday = o.str("deathday"), birthplace = o.str("place_of_birth"),
    )
}

/** Above these a kids profile stops. A title with no rating at all goes by the genre rule, as before. */
private val KIDS_CERTIFICATES = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG")

fun tooOldForKids(certificate: String?): Boolean = certificate != null && certificate !in KIDS_CERTIFICATES

/** A series' seasons from /tv/{id}: each real season's number (no "Specials", season 0) that has episodes. */
fun parseTmdbSeasons(json: String): List<Int> =
    JSONObject(json).optJSONArray("seasons")?.objects().orEmpty()
        .filter { it.optInt("season_number") > 0 && it.optInt("episode_count") > 0 }
        .map { it.optInt("season_number") }.distinct().sorted()

/**
 * A season's episodes from /tv/{id}/season/{n}, as Cinemeta would give them ("tt…:1:2" ids), for series
 * Cinemeta has no episode list for yet. Only episodes aired by [today] (yyyy-MM-dd): one not out has no sources.
 */
fun parseTmdbSeason(json: String, imdb: String, today: String): List<Episode> {
    val o = JSONObject(json)
    val season = o.optInt("season_number")
    return o.optJSONArray("episodes")?.objects().orEmpty().mapNotNull { e ->
        val n = e.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
        val aired = e.str("air_date")?.takeIf { it <= today } ?: return@mapNotNull null
        Episode("$imdb:$season:$n", season, n, e.str("name") ?: "Episode $n", e.str("overview"), image("w500", e.str("still_path")), aired)
    }.sortedBy { it.episode }
}

/**
 * The last resort for a series nobody lists episodes for: the episodes its releases name ("S01E04"), so a
 * season can still be downloaded one episode at a time. Ids as Cinemeta's.
 */
fun episodesFromReleases(imdb: String, names: List<String>): List<Episode> =
    names.mapNotNull(::parseEpisode).filter { it.season > 0 && it.episode > 0 }.distinct()
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .map { Episode("$imdb:${it.season}:${it.episode}", it.season, it.episode, "Episode ${it.episode}", null, null, null) }

/** The person a /search/person answer has by [name] (the same name, an actor first, then the best known); null when none is. */
fun parsePersonSearch(json: String, name: String): CastMember? =
    JSONObject(json).optJSONArray("results")?.objects().orEmpty()
        .filter { sameName(it.optString("name"), name) }
        .sortedWith(compareByDescending<JSONObject> { it.optString("known_for_department") == "Acting" }.thenByDescending { it.optDouble("popularity") })
        .firstOrNull()
        ?.let { CastMember(it.optInt("id"), it.optString("name"), null, image("w185", it.str("profile_path"))) }

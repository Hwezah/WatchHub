package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * AniList (compat; see CloudStreamApp.kt): no account here, but plugins parse AniList's GraphQL answers
 * into these classes, so their names and fields are CloudStream's — the JSON keys are the field names.
 */
class AniListApi : SyncAPI() {
    override val name = "AniList"

    data class Title(val english: String?, val romaji: String?)

    data class CoverImage(val medium: String?, val large: String?, val extraLarge: String?)

    data class LikePageInfo(val total: Int?, val currentPage: Int?, val lastPage: Int?, val perPage: Int?, val hasNextPage: Boolean?)

    data class MediaCoverImage(val extraLarge: String?, val large: String?, val medium: String?, val color: String?)

    data class MediaTitle(val romaji: String?, val english: String?, val native: String?, val userPreferred: String?)

    data class RecommendedMedia(val id: Int?, val title: MediaTitle?, val coverImage: MediaCoverImage?)

    data class Recommendation(val mediaRecommendation: RecommendedMedia?)

    data class RecommendationEdge(val node: Recommendation)

    data class RecommendationConnection(val edges: List<RecommendationEdge> = emptyList(), val nodes: List<Recommendation> = emptyList())

    data class SeasonNextAiringEpisode(val episode: Int?, val timeUntilAiring: Int?)
}

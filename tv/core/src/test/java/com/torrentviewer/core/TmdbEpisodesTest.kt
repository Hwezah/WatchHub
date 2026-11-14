package com.torrentviewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** A new series' episode list from TMDB, and from its release names when nobody lists one. */
class TmdbEpisodesTest {
    @Test
    fun seasonsSkipSpecialsAndEmptyOnes() {
        val json = """{"seasons":[{"season_number":0,"episode_count":3},{"season_number":2,"episode_count":0},{"season_number":1,"episode_count":8}]}"""
        assertEquals(listOf(1), parseTmdbSeasons(json))
    }

    @Test
    fun seasonKeepsOnlyAiredEpisodes() {
        val json = """{"season_number":1,"episodes":[
            {"episode_number":2,"name":"Two","air_date":"2026-03-08","still_path":"/b.jpg"},
            {"episode_number":1,"name":"Pilot","air_date":"2026-03-01","overview":"Kayce leaves."},
            {"episode_number":3,"name":"Three","air_date":"2026-12-01"},
            {"episode_number":4,"name":"Four","air_date":null}]}"""
        val eps = parseTmdbSeason(json, "tt1", "2026-11-13")
        assertEquals(listOf("tt1:1:1", "tt1:1:2"), eps.map { it.id })
        assertEquals("Pilot", eps[0].name)
        assertEquals("Kayce leaves.", eps[0].overview)
        assertEquals("https://image.tmdb.org/t/p/w500/b.jpg", eps[1].thumbnail)
    }

    @Test
    fun episodesFromReleaseNames() {
        val eps = episodesFromReleases("tt1", listOf("Marshals.S01E03.1080p.mkv", "Marshals S01E01 720p", "Marshals.S01E03.720p.x265", "Marshals.S01.COMPLETE"))
        assertEquals(listOf("tt1:1:1", "tt1:1:3"), eps.map { it.id })
    }

    @Test
    fun seriesListedAsAFilm() {
        org.junit.Assert.assertTrue(looksLikeSeries(listOf("U S Marshals S01 1080p", "Marshals.S01E03.mkv", "Marshals 2026 1080p WEB")))
        org.junit.Assert.assertFalse(looksLikeSeries(listOf("Dune.Part.Two.2024.2160p", "Dune Part Two 1080p x265", "Dune.2024.S1.Remux")))
        org.junit.Assert.assertFalse(looksLikeSeries(emptyList()))
    }
}

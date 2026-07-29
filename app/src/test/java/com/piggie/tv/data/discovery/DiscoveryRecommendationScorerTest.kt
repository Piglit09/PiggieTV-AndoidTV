package com.piggie.tv.data.discovery

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRecommendationScorerTest {
    @Test
    fun scoringExcludesWatchedAndExplainsPersonalization() {
        val watched = item(
            "watched",
            genres = listOf("Animation"),
            studios = listOf("Studio A"),
            people = listOf(Person("actor", "Actor A", null, "Actor"))
        )
        val profile = DiscoveryRecommendationScorer.buildProfile(
            watched = listOf(watched),
            favorites = listOf(watched.copy(id = "favorite")),
            recentAdditions = listOf(item("recent"))
        )
        val matching = item(
            "matching",
            genres = listOf("Animation"),
            studios = listOf("Studio A"),
            people = listOf(Person("actor", "Actor A", null, "Actor")),
            rating = 8f
        )
        val unrelated = item("unrelated", genres = listOf("Documentary"), rating = 8f)

        val scored = DiscoveryRecommendationScorer.score(
            listOf(watched, unrelated, matching),
            profile,
            currentYear = 2026
        )

        assertFalse(scored.any { it.item.id == "watched" })
        assertEquals("matching", scored.first().item.id)
        assertTrue(scored.first().reasons.contains("preferred genre"))
        assertTrue(scored.first().reasons.contains("preferred studio"))
        assertTrue(scored.first().reasons.contains("preferred actor"))
    }

    private fun item(
        id: String,
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        people: List<Person> = emptyList(),
        rating: Float? = null
    ) = MediaItem(
        id = id,
        title = id,
        type = "Movie",
        year = "2026",
        imageTag = null,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0,
        runtimeTicks = 0,
        communityRating = rating,
        genres = genres,
        studios = studios,
        productionYear = 2026,
        people = people
    )
}

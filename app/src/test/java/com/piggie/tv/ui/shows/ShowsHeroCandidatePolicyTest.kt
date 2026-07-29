package com.piggie.tv.ui.shows

import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.ui.hero.HeroArtworkKind
import com.piggie.tv.ui.hero.HeroArtworkPolicy
import com.piggie.tv.ui.hero.HeroRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowsHeroCandidatePolicyTest {
    @Test
    fun parentResolutionPlanIsOrderedDeduplicatedAndBounded() {
        val episodes = buildList {
            add(episode("episode-a1", "series-a"))
            add(episode("episode-a2", "series-a"))
            repeat(10) { index ->
                add(episode("episode-$index", "series-$index"))
            }
        }

        assertEquals(
            listOf(
                "series-a",
                "series-0",
                "series-1",
                "series-2",
                "series-3",
                "series-4",
                "series-5",
                "series-6"
            ),
            ShowsHeroCandidatePolicy.parentSeriesIds(episodes)
        )
    }

    @Test
    fun episodeIsWithheldUntilItsAuthoritativeSeriesLookupCompletes() {
        val episode = episode(
            id = "episode",
            seriesId = "series",
            parentBackdropTags = listOf("episode-parent-backdrop")
        )
        val authoritativeSeries = item(
            id = "series",
            type = "Series",
            imageTag = "series-primary",
            backdropTags = listOf("series-backdrop")
        )

        assertTrue(
            ShowsHeroCandidatePolicy.readyCandidates(
                listOf(episode),
                emptyMap()
            ).isEmpty()
        )

        val candidate = ShowsHeroCandidatePolicy.readyCandidates(
            listOf(episode),
            mapOf("series" to authoritativeSeries)
        ).single()
        val artwork = HeroArtworkPolicy.choices(HeroRoute.SHOWS, candidate)

        assertEquals("series", candidate.artworkItem.id)
        assertEquals("series-backdrop", candidate.artworkItem.backdropImageTags.single())
        assertEquals("series", artwork.first().item.id)
        assertEquals(HeroArtworkKind.BACKDROP, artwork.first().kind)
        assertEquals("series-backdrop", artwork.first().tag)
    }

    @Test
    fun failedLookupUsesEpisodeParentArtworkAfterTheAttempt() {
        val episode = episode(
            id = "episode",
            seriesId = "series",
            parentBackdropTags = listOf("parent-backdrop")
        )

        val candidate = ShowsHeroCandidatePolicy.readyCandidates(
            listOf(episode),
            mapOf("series" to null)
        ).single()

        assertEquals("series", candidate.artworkItem.id)
        assertEquals("Series", candidate.artworkItem.type)
        assertEquals("parent-backdrop", candidate.artworkItem.backdropImageTags.single())
    }

    private fun episode(
        id: String,
        seriesId: String,
        parentBackdropTags: List<String> = emptyList()
    ): MediaItem = item(id = id, type = "Episode").copy(
        seriesId = seriesId,
        seriesName = "Series $seriesId",
        parentBackdropItemId = seriesId,
        parentBackdropImageTags = parentBackdropTags
    )

    private fun item(
        id: String,
        type: String,
        imageTag: String? = null,
        backdropTags: List<String> = emptyList()
    ) = MediaItem(
        id = id,
        title = id,
        type = type,
        year = null,
        imageTag = imageTag,
        backdropTag = backdropTags.firstOrNull(),
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0L,
        runtimeTicks = 10L,
        backdropImageTags = backdropTags
    )
}

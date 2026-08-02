package com.piggie.tv.ui.player

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsPoliciesTest {
    @Test
    fun `movie poster and backdrop prefer selected item artwork`() {
        val movie = item("movie", "Movie").copy(
            imageTag = "primary",
            thumbImageTag = "thumb",
            backdropImageTags = listOf("backdrop"),
            parentBackdropItemId = "collection",
            parentBackdropImageTags = listOf("collection-backdrop")
        )

        assertEquals("movie", DetailsArtworkPolicy.posterCandidates(movie).first().itemId)
        assertEquals(DetailsArtworkKind.PRIMARY, DetailsArtworkPolicy.posterCandidates(movie).first().kind)
        assertEquals("movie", DetailsArtworkPolicy.backdropCandidates(movie).first().itemId)
        assertTrue(DetailsArtworkPolicy.backdropCandidates(movie).any { it.blurred })
    }

    @Test
    fun `series falls back from its backdrop to a blurred series poster`() {
        val series = item("series", "Series").copy(imageTag = "series-primary")

        val candidates = DetailsArtworkPolicy.backdropCandidates(series)

        assertEquals("series", candidates.single().itemId)
        assertEquals(DetailsArtworkKind.PRIMARY, candidates.single().kind)
        assertTrue(candidates.single().blurred)
    }

    @Test
    fun `episode uses parent series backdrop logo and poster`() {
        val episode = item("episode", "Episode").copy(
            seriesId = "series",
            imageTag = "episode-primary",
            parentBackdropItemId = "series",
            parentBackdropImageTags = listOf("series-backdrop"),
            parentLogoItemId = "series",
            parentLogoImageTag = "series-logo",
            parentPrimaryImageItemId = "series",
            parentPrimaryImageTag = "series-primary"
        )

        assertEquals("series", DetailsArtworkPolicy.backdrop(episode)?.itemId)
        assertEquals("series", DetailsArtworkPolicy.logo(episode)?.itemId)
        assertEquals("series", DetailsArtworkPolicy.poster(episode)?.itemId)
        assertEquals("episode", DetailsArtworkPolicy.episodeThumbnail(episode)?.itemId)
    }

    @Test
    fun `related content removes current duplicates and caps initial shelf`() {
        val duplicates = buildList {
            add(item("current", "Movie"))
            repeat(20) { add(item("item-${it / 2}", "Movie")) }
        }

        val filtered = RelatedContentPolicy.filter("current", duplicates)

        assertFalse(filtered.any { it.id == "current" })
        assertEquals(filtered.map { it.id }.distinct(), filtered.map { it.id })
        assertTrue(filtered.size <= RelatedContentPolicy.INITIAL_LIMIT)
    }

    @Test
    fun `full details always replace an artwork-bearing seed`() {
        val seed = item("id", "Movie").copy(imageTag = "seed-art")
        val full = seed.copy(overview = "Full overview", audioTracks = emptyList())

        val state = DetailsContentState().withSeed(seed).withFullDetails(full)

        assertTrue(state.hasFullDetails)
        assertEquals("Full overview", state.item?.overview)
    }

    @Test
    fun `full details cannot regress to a later lightweight seed`() {
        val initialSeed = item("id", "Movie").copy(imageTag = "initial-art")
        val full = initialSeed.copy(overview = "Full overview", imageTag = "full-art")
        val lateSeed = initialSeed.copy(overview = null, imageTag = "late-seed-art")

        val state = DetailsContentState()
            .withSeed(initialSeed)
            .withFullDetails(full)
            .withSeed(lateSeed)

        assertTrue(state.hasFullDetails)
        assertEquals("Full overview", state.item?.overview)
        assertEquals("full-art", state.item?.imageTag)
    }

    @Test
    fun `details seed cache expires and trims deterministically`() {
        MediaDetailsSeedStore.clear()
        try {
            MediaDetailsSeedStore.put(item("seed", "Movie"), nowMs = 1_000L)
            assertEquals(1, MediaDetailsSeedStore.size(nowMs = 1_000L))
            assertNull(MediaDetailsSeedStore.getItem("seed", nowMs = 16L * 60L * 1_000L))

            MediaDetailsSeedStore.put(item("seed-2", "Movie"), nowMs = 1_000L)
            MediaDetailsSeedStore.trimToPercent(0)
            assertEquals(0, MediaDetailsSeedStore.size(nowMs = 1_000L))
            assertEquals(0L, MediaDetailsSeedStore.stats(nowMs = 1_000L).estimatedBytes)
        } finally {
            MediaDetailsSeedStore.clear()
        }
    }

    @Test
    fun `oversized details seed cannot exceed byte bound`() {
        MediaDetailsSeedStore.clear()
        try {
            MediaDetailsSeedStore.put(
                item("oversized", "Movie").copy(overview = "x".repeat(600_000)),
                nowMs = 1_000L
            )

            assertEquals(0, MediaDetailsSeedStore.size(nowMs = 1_000L))
        } finally {
            MediaDetailsSeedStore.clear()
        }
    }

    @Test
    fun `season focus graph is deterministic`() {
        assertTrue(
            DetailsInitialFocusPolicy.shouldRequestPrimary(
                hasFocusInsideDetails = false
            )
        )
        assertFalse(
            DetailsInitialFocusPolicy.shouldRequestPrimary(
                hasFocusInsideDetails = true
            )
        )
        assertEquals(
            DetailsFocusRegion.SEASONS,
            SeasonFocusPolicy.target(
                DetailsFocusRegion.ACTIONS,
                DetailsFocusDirection.DOWN,
                hasSeasons = true
            )
        )
        assertEquals(
            DetailsFocusRegion.EPISODES,
            SeasonFocusPolicy.target(
                DetailsFocusRegion.SEASONS,
                DetailsFocusDirection.DOWN,
                hasSeasons = true
            )
        )
        assertEquals(
            DetailsFocusRegion.SEASONS,
            SeasonFocusPolicy.target(
                DetailsFocusRegion.EPISODES,
                DetailsFocusDirection.UP,
                hasSeasons = true
            )
        )
        assertNull(
            SeasonFocusPolicy.target(
                DetailsFocusRegion.ACTIONS,
                DetailsFocusDirection.DOWN,
                hasSeasons = false
            )
        )
        assertEquals(
            SeasonEpisodeEntryDecision.WAIT_FOR_EPISODES,
            SeasonEpisodeEntryPolicy.decide(
                hasFocusableEpisode = false,
                episodesLoading = true
            )
        )
        assertEquals(
            SeasonEpisodeEntryDecision.MOVE_TO_EPISODES,
            SeasonEpisodeEntryPolicy.decide(
                hasFocusableEpisode = true,
                episodesLoading = true
            )
        )
    }

    @Test
    fun `action down waits for unresolved seasons even when related is ready`() {
        assertEquals(
            ActionSecondaryFocusDecision.WAIT,
            ActionSecondaryFocusPolicy.decide(
                hasFocusableSeason = false,
                seasonState = AsyncShelfState.LOADING,
                hasFocusableRelated = true,
                relatedState = AsyncShelfState.NON_EMPTY
            )
        )
        assertEquals(
            ActionSecondaryFocusDecision.WAIT,
            ActionSecondaryFocusPolicy.decide(
                hasFocusableSeason = false,
                seasonState = AsyncShelfState.NON_EMPTY,
                hasFocusableRelated = true,
                relatedState = AsyncShelfState.NON_EMPTY
            )
        )
    }

    @Test
    fun `action down reaches related only after seasons settle empty or error`() {
        assertEquals(
            ActionSecondaryFocusDecision.MOVE_TO_RELATED,
            ActionSecondaryFocusPolicy.decide(
                hasFocusableSeason = false,
                seasonState = AsyncShelfState.EMPTY_OR_ERROR,
                hasFocusableRelated = true,
                relatedState = AsyncShelfState.NON_EMPTY
            )
        )
        assertEquals(
            ActionSecondaryFocusDecision.WAIT,
            ActionSecondaryFocusPolicy.decide(
                hasFocusableSeason = false,
                seasonState = AsyncShelfState.EMPTY_OR_ERROR,
                hasFocusableRelated = false,
                relatedState = AsyncShelfState.LOADING
            )
        )
    }

    private fun item(id: String, type: String) = MediaItem(
        id = id,
        title = id,
        type = type,
        year = null,
        imageTag = null,
        seriesName = null,
        episodeLabel = null,
        playbackPositionTicks = 0L,
        runtimeTicks = 0L
    )
}

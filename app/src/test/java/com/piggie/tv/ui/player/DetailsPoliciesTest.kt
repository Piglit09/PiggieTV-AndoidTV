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

        assertEquals("series", DetailsArtworkPolicy.poster(series)?.itemId)
        assertEquals(DetailsArtworkKind.PRIMARY, DetailsArtworkPolicy.poster(series)?.kind)
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
    fun `blank artwork tags never create remote candidates`() {
        val item = item("movie", "Movie").copy(
            imageTag = " ",
            logoTag = "",
            backdropImageTags = listOf("", "  "),
            parentLogoImageTag = " "
        )

        assertTrue(DetailsArtworkPolicy.posterCandidates(item).isEmpty())
        assertTrue(DetailsArtworkPolicy.logoCandidates(item).isEmpty())
        assertTrue(DetailsArtworkPolicy.backdropCandidates(item).isEmpty())
    }

    @Test
    fun `episode details resolve their parent series destination`() {
        val episode = item("episode", "Episode").copy(seriesId = "series")

        assertEquals("series", EpisodeSeriesNavigationPolicy.seriesId(episode))
    }

    @Test
    fun `non episodes and invalid parent ids cannot expose a series destination`() {
        assertNull(
            EpisodeSeriesNavigationPolicy.seriesId(
                item("movie", "Movie").copy(seriesId = "series")
            )
        )
        assertNull(
            EpisodeSeriesNavigationPolicy.seriesId(
                item("episode", "Episode").copy(seriesId = "  ")
            )
        )
        assertNull(
            EpisodeSeriesNavigationPolicy.seriesId(
                item("episode", "Episode").copy(seriesId = "episode")
            )
        )
    }

    @Test
    fun `season card click opens a normalized nested season destination`() {
        val series = item("series", "Series").copy(
            title = "Piggie Show",
            imageTag = "series-primary",
            logoTag = "series-logo",
            thumbImageTag = "series-thumb",
            backdropImageTags = listOf("series-backdrop")
        )
        val season = item("season-2", "Season").copy(
            title = "Season 2",
            imageTag = "season-primary",
            seriesId = "stale-parent",
            seriesName = " ",
            parentLogoImageTag = " ",
            parentBackdropImageTags = listOf("", "  ")
        )

        assertEquals(
            SeasonDetailsClickDecision.OPEN_DETAILS,
            SeasonDetailsNavigationPolicy.clickDecision(series, season)
        )
        val opened = SeasonDetailsNavigationPolicy.routeItem(series, season)

        assertEquals("season-2", opened.id)
        assertEquals("Season", opened.type)
        assertEquals("season-primary", opened.imageTag)
        assertEquals("series", opened.seriesId)
        assertEquals("Piggie Show", opened.seriesName)
        assertEquals("series", opened.parentPrimaryImageItemId)
        assertEquals("series-primary", opened.parentPrimaryImageTag)
        assertEquals("series", opened.parentLogoItemId)
        assertEquals("series-logo", opened.parentLogoImageTag)
        assertEquals("series", opened.parentThumbItemId)
        assertEquals("series-thumb", opened.parentThumbImageTag)
        assertEquals("series", opened.parentBackdropItemId)
        assertEquals(listOf("series-backdrop"), opened.parentBackdropImageTags)
        assertEquals("series-primary", opened.seriesPrimaryImageTag)
    }

    @Test
    fun `invalid season card pairs cannot open nested details`() {
        val series = item("series", "Series")

        listOf(
            series to item("episode", "Episode"),
            item("not-a-series", "Movie") to item("season", "Season"),
            series to item(" ", "Season"),
            series to item("series", "Season")
        ).forEach { (candidateSeries, candidateSeason) ->
            assertEquals(
                SeasonDetailsClickDecision.IGNORE,
                SeasonDetailsNavigationPolicy.clickDecision(candidateSeries, candidateSeason)
            )
            assertEquals(
                candidateSeason,
                SeasonDetailsNavigationPolicy.routeItem(candidateSeries, candidateSeason)
            )
        }
    }

    @Test
    fun `season details resolve their parent series destination`() {
        assertEquals(
            "series",
            EpisodeSeriesNavigationPolicy.seriesId(
                item("season", "Season").copy(seriesId = "  series  ")
            )
        )
        assertEquals(
            "series",
            EpisodeSeriesNavigationPolicy.seriesId(
                item("episode", "Episode").copy(seriesId = "series")
            )
        )
        assertNull(
            EpisodeSeriesNavigationPolicy.seriesId(
                item("season", "Season").copy(seriesId = "season")
            )
        )
        assertNull(
            EpisodeSeriesNavigationPolicy.seriesId(
                item("movie", "Movie").copy(seriesId = "series")
            )
        )
    }

    @Test
    fun `season route loads only its episodes as nested secondary content`() {
        val season = SeasonDetailsNavigationPolicy.routeItem(
            item("series", "Series"),
            item("season-3", "Season")
        )

        assertEquals(
            DetailsSecondaryLoadDecision(
                kind = DetailsSecondaryLoadKind.SEASON_EPISODES,
                seriesId = "series",
                seasonId = "season-3"
            ),
            DetailsSecondaryLoadingPolicy.decide(season)
        )
        assertEquals(
            DetailsSecondaryLoadDecision(
                kind = DetailsSecondaryLoadKind.SERIES_SEASONS,
                seriesId = "series"
            ),
            DetailsSecondaryLoadingPolicy.decide(item("series", "Series"))
        )
        assertEquals(
            DetailsSecondaryLoadDecision(DetailsSecondaryLoadKind.RELATED_ONLY),
            DetailsSecondaryLoadingPolicy.decide(item("movie", "Movie"))
        )
        assertEquals(
            DetailsSecondaryLoadDecision(
                kind = DetailsSecondaryLoadKind.SEASON_EPISODES,
                seasonId = "orphan-season"
            ),
            DetailsSecondaryLoadingPolicy.decide(item("orphan-season", "Season"))
        )
    }

    @Test
    fun `series and season seeds recover their required browser after refresh failure`() {
        assertTrue(
            DetailsRefreshFailurePolicy.shouldRecoverSecondary(item("series", "Series"))
        )
        assertTrue(
            DetailsRefreshFailurePolicy.shouldRecoverSecondary(
                item("season", "Season").copy(seriesId = "series")
            )
        )
        assertFalse(
            DetailsRefreshFailurePolicy.shouldRecoverSecondary(item("episode", "Episode"))
        )
        assertFalse(
            DetailsRefreshFailurePolicy.shouldRecoverSecondary(item("movie", "Movie"))
        )
    }

    @Test
    fun `id-only View Series navigation reuses only its exact cached seed`() {
        val cachedSeries = item("series", "Series").copy(title = "Cached Series")
        MediaDetailsSeedStore.clear()
        try {
            MediaDetailsSeedStore.put(cachedSeries, nowMs = 1_000L)
            val cached = MediaDetailsSeedStore.getItem("series", nowMs = 1_000L)

            assertEquals(
                cachedSeries,
                NestedDetailsSeedPolicy.resolve(" series ", cached)
            )
            assertNull(
                NestedDetailsSeedPolicy.resolve("different-series", cachedSeries)
            )
            assertNull(NestedDetailsSeedPolicy.resolve("  ", cachedSeries))
            assertNull(NestedDetailsSeedPolicy.resolve("series", null))
        } finally {
            MediaDetailsSeedStore.clear()
        }
    }

    @Test
    fun `season containers never request video track metadata`() {
        assertFalse(DetailsPlaybackMetadataPolicy.needsVideoTracks(item("series", "Series")))
        assertFalse(DetailsPlaybackMetadataPolicy.needsVideoTracks(item("season", "Season")))
        assertTrue(DetailsPlaybackMetadataPolicy.needsVideoTracks(item("movie", "Movie")))
        assertTrue(DetailsPlaybackMetadataPolicy.needsVideoTracks(item("episode", "Episode")))
        assertTrue(DetailsPlaybackMetadataPolicy.needsVideoTracks(item("video", "Video")))
    }

    @Test
    fun `series playback keeps every valid series episode in server order`() {
        val series = item(" series ", "Series")
        val candidates = listOf(
            item("episode-1", "Episode").copy(seriesId = "series"),
            item(" episode-2 ", "episode").copy(seriesId = null),
            item("episode-3", "Episode").copy(seriesId = " series ")
        )

        val episodes = SeriesPlaybackQueuePolicy.episodes(series, candidates)

        assertEquals(listOf("episode-1", "episode-2", "episode-3"), episodes.map { it.id })
        assertTrue(episodes.all { it.seriesId == "series" })
    }

    @Test
    fun `series playback rejects invalid duplicates but retains merged library episodes`() {
        val series = item("series", "Series")
        val valid = item("episode-1", "Episode").copy(seriesId = "series")
        val episodes = SeriesPlaybackQueuePolicy.episodes(
            series,
            listOf(
                valid,
                valid.copy(title = "duplicate"),
                item("movie", "Movie").copy(seriesId = "series"),
                item(" ", "Episode").copy(seriesId = "series"),
                item("episode-2", "Episode").copy(seriesId = "other-series"),
                item("series", "Episode").copy(seriesId = "series")
            )
        )

        assertEquals(listOf("episode-1", "episode-2"), episodes.map { it.id })
        assertEquals("other-series", episodes.last().seriesId)
        assertNull(SeriesPlaybackQueuePolicy.validSeriesId(item("series", "Movie")))
        assertTrue(
            SeriesPlaybackQueuePolicy.episodes(item(" ", "Series"), listOf(valid)).isEmpty()
        )
    }

    @Test
    fun `full season details retain normalized nested route metadata`() {
        val seed = SeasonDetailsNavigationPolicy.routeItem(
            item("series", "Series").copy(
                title = "Piggie Show",
                logoTag = "series-logo"
            ),
            item("season", "Season").copy(
                childCount = 8,
                episodeCount = 8,
                recursiveItemCount = 8
            )
        )
        val full = item("season", "Season").copy(overview = "Full season overview")

        val merged = DetailsNavigationMetadataPolicy.merge(seed, full)

        assertEquals("series", merged.seriesId)
        assertEquals("Piggie Show", merged.seriesName)
        assertEquals("series", merged.parentLogoItemId)
        assertEquals("series-logo", merged.parentLogoImageTag)
        assertEquals(8, merged.childCount)
        assertEquals(8, merged.episodeCount)
        assertEquals(8, merged.recursiveItemCount)
        assertEquals("Full season overview", merged.overview)
    }

    @Test
    fun `full episode details retain navigation metadata omitted by the server`() {
        val seed = item("episode", "Episode").copy(
            seriesId = "series",
            seasonId = "season",
            seriesName = "Piggie Show",
            indexNumber = 4,
            parentIndexNumber = 2,
            episodeLabel = "S2 E4"
        )
        val full = item("episode", "Episode").copy(overview = "Full overview")

        val merged = DetailsNavigationMetadataPolicy.merge(seed, full)

        assertEquals("series", merged.seriesId)
        assertEquals("season", merged.seasonId)
        assertEquals("Piggie Show", merged.seriesName)
        assertEquals(4, merged.indexNumber)
        assertEquals(2, merged.parentIndexNumber)
        assertEquals("S2 E4", merged.episodeLabel)
        assertEquals("Full overview", merged.overview)
    }

    @Test
    fun `full details retain parent artwork omitted by the server`() {
        val seed = item("episode", "Episode").copy(
            imageTag = "primary",
            parentLogoItemId = "series",
            parentLogoImageTag = "series-logo",
            parentBackdropItemId = "series",
            parentBackdropImageTags = listOf("series-backdrop"),
            seriesPrimaryImageTag = "series-primary"
        )

        val merged = DetailsNavigationMetadataPolicy.merge(seed, item("episode", "Episode"))

        assertEquals("primary", merged.imageTag)
        assertEquals("series", merged.parentLogoItemId)
        assertEquals("series-logo", merged.parentLogoImageTag)
        assertEquals(listOf("series-backdrop"), merged.parentBackdropImageTags)
        assertEquals("series-primary", merged.seriesPrimaryImageTag)
    }

    @Test
    fun `play time uses zero padded hours and minutes`() {
        assertEquals("00:50", DetailsTimePolicy.playTime(50L * 600_000_000L))
        assertEquals("02:05", DetailsTimePolicy.playTime(125L * 600_000_000L))
        assertEquals("27:03", DetailsTimePolicy.playTime((27L * 60L + 3L) * 600_000_000L))
        assertNull(DetailsTimePolicy.playTime(0L))
    }

    @Test
    fun `end time uses remaining playback duration`() {
        val now = 1_000L
        val runtime = 120L * 600_000_000L
        val position = 30L * 600_000_000L

        assertEquals(now + 90L * 60_000L, DetailsTimePolicy.endsAtMillis(now, runtime, position))
        assertNull(DetailsTimePolicy.endsAtMillis(now, 0L, 0L))
    }

    @Test
    fun `movie facts resolve one coherent directed play time and ends row`() {
        val now = 10_000L
        val runtime = 125L * 600_000_000L
        val position = 5L * 600_000_000L

        assertEquals(
            MovieDetailsFacts(
                directedBy = "Jane Director",
                playTime = "02:05",
                endsAtMillis = now + 120L * 60_000L
            ),
            MovieDetailsFactsPolicy.resolve(
                item("movie", "Movie").copy(
                    director = "  Jane Director  ",
                    runtimeTicks = runtime,
                    playbackPositionTicks = position
                ),
                nowMillis = now
            )
        )
        assertNull(MovieDetailsFactsPolicy.resolve(item("series", "Series"), now))
    }

    @Test
    fun `server navigation metadata replaces stale seed values`() {
        val seed = item("episode", "Episode").copy(seriesId = "old-series")
        val full = item("episode", "Episode").copy(seriesId = "current-series")

        assertEquals(
            "current-series",
            DetailsNavigationMetadataPolicy.merge(seed, full).seriesId
        )
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

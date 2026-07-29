package com.piggie.tv.ui.hero

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPolicyTest {
    @Test
    fun visibilityTracksFullPartialAndOffscreenHeroGeometry() {
        assertEquals(100, HeroVisibilityPolicy.visiblePercent(400, 0, 170, 170))
        assertEquals(50, HeroVisibilityPolicy.visiblePercent(400, -85, 85, 170))
        assertEquals(0, HeroVisibilityPolicy.visiblePercent(400, -170, 0, 170))
    }

    @Test
    fun routePoolsFilterMediaAndShowEpisodesBySource() {
        val movie = candidate("movie", "Movie", HeroSource.RECENT)
        val series = candidate("series", "Series", HeroSource.RECENT)
        val recentEpisode = candidate("recent-episode", "Episode", HeroSource.RECENT)
        val nextEpisode = candidate("next-episode", "Episode", HeroSource.NEXT_UP)
        val sources = listOf(movie, series, recentEpisode, nextEpisode)
            .groupBy(HeroCandidate::source)

        assertEquals(
            listOf("movie"),
            HeroPoolPolicy.build(HeroRoute.MOVIES, sources).map { it.item.id }
        )
        assertEquals(
            listOf("next-episode", "series"),
            HeroPoolPolicy.build(HeroRoute.SHOWS, sources).map { it.item.id }
        )
    }

    @Test
    fun continueCandidatesMustRepresentMeaningfulProgress() {
        val zero = candidate("zero", "Movie", HeroSource.CONTINUE)
        val played = candidate("played", "Movie", HeroSource.CONTINUE, position = 5L, played = true)
        val resumable = candidate("resume", "Movie", HeroSource.CONTINUE, position = 5L)

        val result = HeroPoolPolicy.build(
            HeroRoute.MOVIES,
            mapOf(HeroSource.CONTINUE to listOf(zero, played, resumable))
        )

        assertEquals(listOf("resume"), result.map { it.item.id })
    }

    @Test
    fun rotationIsVisibleLifecycleAndMotionAware() {
        assertTrue(HeroRotationPolicy.shouldSchedule(2, 100, true, false, false))
        assertFalse(HeroRotationPolicy.shouldSchedule(2, 0, true, false, false))
        assertFalse(HeroRotationPolicy.shouldSchedule(2, 100, false, false, false))
        assertFalse(HeroRotationPolicy.shouldSchedule(2, 100, true, true, false))
        assertFalse(HeroRotationPolicy.shouldSchedule(2, 100, true, false, true))
        assertFalse(HeroRotationPolicy.shouldSchedule(1, 100, true, false, false))
    }

    @Test
    fun nextSelectionNeverImmediatelyDuplicatesWhenAlternativesExist() {
        assertEquals(1, HeroPoolPolicy.nextIndex(0, 3))
        assertEquals(0, HeroPoolPolicy.nextIndex(2, 3))
        assertEquals(0, HeroPoolPolicy.nextIndex(0, 1))
    }

    @Test
    fun retainedSelectionSupportsStableDetailsReturn() {
        val pool = listOf(
            candidate("a", "Movie", HeroSource.RECENT),
            candidate("b", "Movie", HeroSource.RECENT)
        )

        assertEquals(1, HeroSelectionPolicy.retainedIndex(pool, "b"))
        assertEquals(-1, HeroSelectionPolicy.retainedIndex(pool, "gone"))
        assertEquals(
            1,
            HeroSelectionPolicy.indexAfterPoolUpdate(
                route = HeroRoute.MOVIES,
                previousPool = pool,
                updatedPool = pool,
                currentItemId = "b"
            )
        )
    }

    @Test
    fun slowHigherPrioritySourcePromotesAfterFocusOrRotationChangedHero() {
        val lowerPriorityPool = listOf(
            candidate("recent-a", "Movie", HeroSource.RECENT),
            candidate("recent-b", "Movie", HeroSource.RECENT)
        )
        val higherPriorityArrives = listOf(
            candidate(
                "continue",
                "Movie",
                HeroSource.CONTINUE,
                position = 5L
            )
        ) + lowerPriorityPool

        assertEquals(
            0,
            HeroSelectionPolicy.indexAfterPoolUpdate(
                route = HeroRoute.MOVIES,
                previousPool = lowerPriorityPool,
                updatedPool = higherPriorityArrives,
                // Represents the second low-priority item selected by stable
                // focus or rotation while Continue Watching was still loading.
                currentItemId = "recent-b"
            )
        )
    }

    @Test
    fun equalPriorityArrivalDoesNotDisplaceRetainedHero() {
        val previousPool = listOf(
            candidate("recent-a", "Movie", HeroSource.RECENT),
            candidate("recent-b", "Movie", HeroSource.RECENT)
        )
        val samePriorityUpdate = listOf(
            candidate("recent-new", "Movie", HeroSource.RECENT)
        ) + previousPool

        assertEquals(
            2,
            HeroSelectionPolicy.indexAfterPoolUpdate(
                route = HeroRoute.MOVIES,
                previousPool = previousPool,
                updatedPool = samePriorityUpdate,
                currentItemId = "recent-b"
            )
        )
    }

    @Test
    fun higherPriorityDuplicateIsRepresentedOnlyOnce() {
        val previousPool = listOf(
            candidate("same-item", "Movie", HeroSource.RECENT)
        )
        val updatedPool = HeroPoolPolicy.build(
            route = HeroRoute.MOVIES,
            candidatesBySource = mapOf(
                HeroSource.CONTINUE to listOf(
                    candidate(
                        "same-item",
                        "Movie",
                        HeroSource.CONTINUE,
                        position = 5L
                    )
                ),
                HeroSource.RECENT to previousPool
            )
        )

        assertEquals(listOf("same-item"), updatedPool.map { it.item.id })
        assertEquals(
            0,
            HeroSelectionPolicy.indexAfterPoolUpdate(
                route = HeroRoute.MOVIES,
                previousPool = previousPool,
                updatedPool = updatedPool,
                currentItemId = "same-item"
            )
        )
    }

    @Test
    fun musicArtistArtworkUsesBackdropPrimaryThenAlbumCover() {
        val album = candidate(
            "album",
            "MusicAlbum",
            HeroSource.RECENT,
            imageTag = "album-primary",
            backdropTag = "album-backdrop"
        ).item
        val artist = candidate(
            "artist",
            "MusicArtist",
            HeroSource.RECENT,
            imageTag = "artist-primary",
            backdropTag = "artist-backdrop",
            fallbackArtworkItems = listOf(album)
        )

        assertEquals(
            listOf(
                "artist:backdrop:artist-backdrop",
                "artist:primary:artist-primary",
                "album:primary:album-primary"
            ),
            HeroArtworkPolicy.choices(HeroRoute.MUSIC, artist).map {
                "${it.item.id}:${it.kind.wireName}:${it.tag}"
            }
        )
    }

    @Test
    fun musicAlbumArtworkUsesCoverTrackThenArtistAndStaysBounded() {
        val track = candidate(
            "track",
            "Audio",
            HeroSource.RECENT,
            imageTag = "track-primary"
        ).item
        val artist = candidate(
            "artist",
            "MusicArtist",
            HeroSource.RECENT,
            imageTag = "artist-primary",
            backdropTag = "artist-backdrop"
        ).item
        val extraFallbacks = (0 until 10).map {
            candidate(
                "extra-$it",
                "Audio",
                HeroSource.RECENT,
                imageTag = "extra-tag-$it"
            ).item
        }
        val album = candidate(
            "album",
            "MusicAlbum",
            HeroSource.RECENT,
            imageTag = "album-primary",
            backdropTag = "album-backdrop",
            fallbackArtworkItems = listOf(track, artist) + extraFallbacks
        )
        val choices = HeroArtworkPolicy.choices(HeroRoute.MUSIC, album)

        assertEquals(
            listOf(
                "album:primary",
                "track:primary",
                "artist:primary"
            ),
            choices.take(3).map { "${it.item.id}:${it.kind.wireName}" }
        )
        assertEquals(HeroArtworkPolicy.MAX_ATTEMPTS, choices.size)
    }

    @Test
    fun showsEpisodeAttemptsUntaggedParentBackdropBeforeTaggedFallbacks() {
        val episode = candidate(
            "episode",
            "Episode",
            HeroSource.NEXT_UP,
            imageTag = "episode-primary",
            backdropTag = "episode-backdrop"
        ).item
        val parentSeries = candidate(
            "series",
            "Series",
            HeroSource.NEXT_UP,
            imageTag = "series-primary"
        ).item
        val hero = HeroCandidate(
            item = episode,
            source = HeroSource.NEXT_UP,
            artworkItem = parentSeries,
            fallbackArtworkItems = listOf(episode)
        )

        val choices = HeroArtworkPolicy.choices(HeroRoute.SHOWS, hero)

        assertEquals(
            listOf(
                "series:backdrop:null",
                "series:primary:series-primary",
                "episode:backdrop:episode-backdrop",
                "episode:primary:episode-primary"
            ),
            choices.map { "${it.item.id}:${it.kind.wireName}:${it.tag}" }
        )
        assertEquals("ptv-hero:series:backdrop:untagged", choices.first().cacheKey)
        assertTrue(choices.size <= HeroArtworkPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun enrichedArtworkChainInvalidatesCallbacksOwnedBySyntheticEpisodeChain() {
        val initialKey = HeroArtworkLoadGuard.chainKey(
            "episode",
            listOf("ptv-hero:series:backdrop:untagged")
        )
        val enrichedKey = HeroArtworkLoadGuard.chainKey(
            "episode",
            listOf("ptv-hero:series:backdrop:resolved-tag")
        )

        assertFalse(
            HeroArtworkLoadGuard.ownsCallback(
                expectedGeneration = 1L,
                activeGeneration = 2L,
                expectedChainKey = initialKey,
                activeChainKey = enrichedKey,
                expectedCandidateItemId = "episode",
                currentCandidateItemId = "episode"
            )
        )
        assertTrue(
            HeroArtworkLoadGuard.ownsCallback(
                expectedGeneration = 2L,
                activeGeneration = 2L,
                expectedChainKey = enrichedKey,
                activeChainKey = enrichedKey,
                expectedCandidateItemId = "episode",
                currentCandidateItemId = "episode"
            )
        )
    }

    @Test
    fun episodesSharingSeriesArtworkStillHaveDistinctLoadChainIdentities() {
        val sharedArtwork = listOf("ptv-hero:series:backdrop:resolved-tag")

        assertNotEquals(
            HeroArtworkLoadGuard.chainKey("episode-one", sharedArtwork),
            HeroArtworkLoadGuard.chainKey("episode-two", sharedArtwork)
        )
    }

    @Test
    fun untaggedBackdropFallbackIsNotAddedForNonParentArtwork() {
        val directSeries = candidate(
            "series",
            "Series",
            HeroSource.RECENT,
            imageTag = "series-primary"
        )
        val movie = candidate(
            "movie",
            "Movie",
            HeroSource.RECENT,
            imageTag = "movie-primary"
        )

        assertEquals(
            listOf("series:primary:series-primary"),
            HeroArtworkPolicy.choices(HeroRoute.SHOWS, directSeries).map {
                "${it.item.id}:${it.kind.wireName}:${it.tag}"
            }
        )
        assertEquals(
            listOf("movie:primary:movie-primary"),
            HeroArtworkPolicy.choices(HeroRoute.MOVIES, movie).map {
                "${it.item.id}:${it.kind.wireName}:${it.tag}"
            }
        )
    }

    @Test
    fun initialVisibilityWaitsForOneUsableLayout() {
        assertFalse(HeroInitialLayoutPolicy.shouldSample(0, 540, false))
        assertFalse(HeroInitialLayoutPolicy.shouldSample(960, 0, false))
        assertTrue(HeroInitialLayoutPolicy.shouldSample(960, 540, false))
        assertFalse(HeroInitialLayoutPolicy.shouldSample(960, 540, true))
    }

    private fun candidate(
        id: String,
        type: String,
        source: HeroSource,
        position: Long = 0L,
        played: Boolean = false,
        imageTag: String? = null,
        backdropTag: String? = null,
        fallbackArtworkItems: List<MediaItem> = emptyList()
    ) = HeroCandidate(
        item = MediaItem(
            id = id,
            title = id,
            type = type,
            year = null,
            imageTag = imageTag,
            backdropTag = backdropTag,
            seriesName = null,
            episodeLabel = null,
            playbackPositionTicks = position,
            runtimeTicks = 10L,
            isPlayed = played
        ),
        source = source,
        fallbackArtworkItems = fallbackArtworkItems
    )
}

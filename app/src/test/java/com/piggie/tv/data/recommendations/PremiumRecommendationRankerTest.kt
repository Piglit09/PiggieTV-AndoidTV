package com.piggie.tv.data.recommendations

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumRecommendationRankerTest {
    @Test
    fun surfacePoliciesExposeTheSharedFamiliarDiscoveryContract() {
        assertEquals(14, PremiumRecommendationSurface.HOME.policy.familiarTarget(20))
        assertEquals(14, PremiumRecommendationSurface.MADE_FOR_YOU.policy.familiarTarget(20))
        assertEquals(5, PremiumRecommendationSurface.RADIO.policy.familiarTarget(20))
        assertEquals(12, PremiumRecommendationSurface.RELATED.policy.familiarTarget(20))
        assertEquals(4, PremiumRecommendationSurface.DISCOVERY.policy.familiarTarget(20))
        PremiumRecommendationSurface.values().forEach { surface ->
            assertEquals(2, surface.policy.maxConsecutiveArtist)
            assertEquals(2, surface.policy.maxConsecutiveAlbum)
        }
    }

    @Test
    fun suggestionsKeepServerRelevanceButRemoveConsumedAndConcentratedItems() {
        val candidates = listOf(
            item("played", genres = listOf("Drama"), played = true),
            item("space-a", genres = listOf("Science Fiction"), studios = listOf("Studio A"), rating = 9f),
            item("space-b", genres = listOf("Science Fiction"), studios = listOf("Studio A"), rating = 8f),
            item("comedy", genres = listOf("Comedy"), studios = listOf("Studio B"), rating = 8f),
            item("space-c", genres = listOf("Science Fiction"), studios = listOf("Studio A"), rating = 7f)
        )

        val ranked = PremiumRecommendationRanker.rankSuggestions(candidates, limit = 4)

        assertFalse(ranked.any { it.id == "played" })
        assertEquals("space-a", ranked.first().id)
        assertTrue(ranked.indexOfFirst { it.id == "comedy" } < ranked.indexOfFirst { it.id == "space-c" })
    }

    @Test
    fun relatedMusicRejectsZeroAffinityWhenMetadataCanExplainBetterMatches() {
        val seed = audio("seed", "Seed Artist", "Seed Album", listOf("Synthwave"))
        val sameGenre = audio("genre", "New Artist", "New Album", listOf("Synthwave"))
        val sameArtist = audio("artist", "Seed Artist", "Other Album", listOf("Rock"))
        val unrelated = audio("unrelated", "Other", "Other", listOf("Country"))

        val ranked = PremiumRecommendationRanker.rankRelated(
            seed = seed,
            candidates = listOf(unrelated, sameGenre, sameArtist),
            allowedTypes = setOf("Audio"),
            limit = 10
        )

        assertEquals(listOf("artist", "genre"), ranked.map(MediaItem::id))
    }

    @Test
    fun musicMixIsSeededRepeatableAndAvoidsArtistOrAlbumRuns() {
        val seed = audio("seed", "Seed Artist", "Seed Album", listOf("Metal"), playCount = 3)
        val candidates = buildList {
            add(seed)
            repeat(4) { index ->
                add(audio("seed-$index", "Seed Artist", "Seed Album", listOf("Metal"), playCount = 2))
            }
            repeat(5) { index ->
                add(audio("related-$index", "Related $index", "Album $index", listOf("Metal")))
            }
            add(audio("familiar", "Known Artist", "Known Album", listOf("Rock"), playCount = 5))
        }

        val first = PremiumRecommendationRanker.rankMusicMix(seed, candidates, 10, "session-a")
        val repeated = PremiumRecommendationRanker.rankMusicMix(seed, candidates, 10, "session-a")

        assertEquals("seed", first.first().id)
        assertEquals(first.map(MediaItem::id), repeated.map(MediaItem::id))
        assertEquals(first.size, first.distinctBy(MediaItem::id).size)
        assertTrue(first.any { it.id == "familiar" })
        assertTrue(longestRun(first) { it.album.orEmpty() } <= 2)
        assertTrue(longestRun(first) { it.albumArtist.orEmpty() } <= 2)
    }

    @Test
    fun musicSurfacePoliciesUseTheSharedFamiliarDiscoveryContract() {
        val seed = audio("policy-seed", "Seed Artist", "Seed Album", listOf("Soul"), playCount = 4)
        val familiar = (1..20).map { index ->
            audio("familiar-$index", "Known $index", "Known Album $index", listOf("Soul"), playCount = 2)
        }
        val discovery = (1..20).map { index ->
            audio("discovery-$index", "New $index", "New Album $index", listOf("Soul"))
        }

        val madeForYou = PremiumRecommendationRanker.rankMusicMix(
            seed = seed,
            candidates = familiar + discovery,
            limit = 20,
            sessionSeed = "policy",
            surface = PremiumRecommendationSurface.MADE_FOR_YOU
        )
        val radio = PremiumRecommendationRanker.rankMusicMix(
            seed = seed,
            candidates = familiar + discovery,
            limit = 20,
            sessionSeed = "policy",
            surface = PremiumRecommendationSurface.RADIO
        )

        assertEquals(14, madeForYou.count { it.id == seed.id || it.playCount > 0 })
        assertEquals(5, radio.count { it.id == seed.id || it.playCount > 0 })
        assertTrue(longestRun(radio) { it.albumArtist.orEmpty() } <= 2)
        assertTrue(longestRun(radio) { it.album.orEmpty() } <= 2)
    }

    private fun longestRun(items: List<MediaItem>, key: (MediaItem) -> String): Int {
        var longest = 0
        var current = 0
        var previous = ""
        items.forEach { item ->
            val value = key(item)
            current = if (value.isNotBlank() && value == previous) current + 1 else 1
            longest = maxOf(longest, current)
            previous = value
        }
        return longest
    }

    private fun audio(
        id: String,
        artist: String,
        album: String,
        genres: List<String>,
        playCount: Int = 0
    ) = item(
        id = id,
        type = "Audio",
        genres = genres,
        artists = listOf(artist),
        album = album,
        albumArtist = artist,
        playCount = playCount
    )

    private fun item(
        id: String,
        type: String = "Movie",
        genres: List<String> = emptyList(),
        studios: List<String> = emptyList(),
        artists: List<String> = emptyList(),
        album: String? = null,
        albumArtist: String? = null,
        playCount: Int = 0,
        played: Boolean = false,
        rating: Float? = null
    ) = MediaItem(
        id = id,
        title = id,
        type = type,
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
        artists = artists,
        album = album,
        albumArtist = albumArtist,
        playCount = playCount,
        isPlayed = played
    )
}

package com.piggie.tv.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAffinityScorerTest {
    @Test
    fun priorityTiersBeatRawEngagementScores() {
        val selection = MusicAffinityScorer.select(
            listOf(
                artist("frequent", playCount = 900, completedTracks = 400),
                album("recent-album", recentAlbumPlays = 20, lastPlayedAtMs = 9_000),
                artist("recent-artist", recentArtistPlays = 1, lastPlayedAtMs = 100)
            )
        )

        assertEquals("recent-artist", selection?.candidate?.id)
        assertEquals(MusicAffinityReason.RECENTLY_PLAYED_ARTIST, selection?.reason)
    }

    @Test
    fun fallsThroughEveryDocumentedTier() {
        val frequent = MusicAffinityScorer.select(
            listOf(
                artist("favorite", isFavorite = true),
                album("recent", recentAlbumPlays = 4, lastPlayedAtMs = 500),
                artist("frequent", playCount = 3)
            )
        )
        assertEquals(MusicAffinityReason.FREQUENTLY_PLAYED_ARTIST, frequent?.reason)

        val recentAlbum = MusicAffinityScorer.select(
            listOf(
                artist("favorite", isFavorite = true),
                album("recent", recentAlbumPlays = 1, lastPlayedAtMs = 500)
            )
        )
        assertEquals(MusicAffinityReason.RECENTLY_PLAYED_ALBUM, recentAlbum?.reason)

        val favorite = MusicAffinityScorer.select(
            listOf(
                album("new", addedAtMs = 800),
                artist("favorite", isFavorite = true)
            )
        )
        assertEquals(MusicAffinityReason.FAVORITE_ARTIST, favorite?.reason)

        val recentAddition = MusicAffinityScorer.select(
            listOf(
                album("older", addedAtMs = 100),
                album("newer", addedAtMs = 800)
            )
        )
        assertEquals("newer", recentAddition?.candidate?.id)
        assertEquals(MusicAffinityReason.RECENTLY_ADDED_ALBUM, recentAddition?.reason)
    }

    @Test
    fun deterministicTieBreakAndDiagnosticsExplainSelection() {
        val selection = MusicAffinityScorer.select(
            listOf(
                artist("z-artist", playCount = 2),
                artist("a-artist", playCount = 2)
            )
        )

        assertEquals("a-artist", selection?.candidate?.id)
        assertTrue(selection?.diagnosticSummary.orEmpty().contains("frequently_played_artist"))
        assertTrue(selection?.diagnosticSummary.orEmpty().contains("item=a-artist"))
    }

    @Test
    fun returnsNullInsteadOfChoosingUnrelatedMusic() {
        assertNull(
            MusicAffinityScorer.select(
                listOf(
                    artist("arbitrary"),
                    album("arbitrary-album")
                )
            )
        )
    }

    private fun artist(
        id: String,
        playCount: Int = 0,
        recentArtistPlays: Int = 0,
        completedTracks: Int = 0,
        isFavorite: Boolean = false,
        lastPlayedAtMs: Long? = null
    ) = MusicAffinityCandidate(
        id = id,
        title = id,
        kind = MusicAffinityKind.ARTIST,
        playCount = playCount,
        recentArtistPlays = recentArtistPlays,
        completedTracks = completedTracks,
        isFavorite = isFavorite,
        lastPlayedAtMs = lastPlayedAtMs
    )

    private fun album(
        id: String,
        playCount: Int = 0,
        recentAlbumPlays: Int = 0,
        completedTracks: Int = 0,
        lastPlayedAtMs: Long? = null,
        addedAtMs: Long? = null
    ) = MusicAffinityCandidate(
        id = id,
        title = id,
        kind = MusicAffinityKind.ALBUM,
        playCount = playCount,
        recentAlbumPlays = recentAlbumPlays,
        completedTracks = completedTracks,
        lastPlayedAtMs = lastPlayedAtMs,
        addedAtMs = addedAtMs
    )
}

package com.piggie.tv.ui.player

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalCompletionDetailsGuardTest {
    @Test
    fun `stale details keep natural completion user data while server metadata wins`() {
        val id = "completion-stale"
        NaturalCompletionDetailsGuard.clear(id)
        try {
            val seed = NaturalCompletionDetailsGuard.markCompleted(
                item(id).copy(
                    title = "Seed title",
                    playbackPositionTicks = 590_000_000L,
                    isPlayed = false
                ),
                nowMs = 1_000L
            )
            val merged = NaturalCompletionDetailsGuard.mergeServerDetails(
                item(id).copy(
                    title = "Fresh server title",
                    overview = "Fresh overview",
                    playbackPositionTicks = 580_000_000L,
                    isPlayed = false
                ),
                nowMs = 1_001L
            )

            assertTrue(seed.isPlayed)
            assertEquals(0L, seed.playbackPositionTicks)
            assertEquals("Fresh server title", merged.title)
            assertEquals("Fresh overview", merged.overview)
            assertTrue(merged.isPlayed)
            assertEquals(0L, merged.playbackPositionTicks)
        } finally {
            NaturalCompletionDetailsGuard.clear(id)
        }
    }

    @Test
    fun `server acknowledgement retires completion guard`() {
        val id = "completion-ack"
        NaturalCompletionDetailsGuard.clear(id)
        try {
            NaturalCompletionDetailsGuard.markCompleted(item(id), nowMs = 2_000L)

            val acknowledged = NaturalCompletionDetailsGuard.mergeServerDetails(
                item(id).copy(isPlayed = true, playbackPositionTicks = 0L),
                nowMs = 2_001L
            )
            val laterServerChange = NaturalCompletionDetailsGuard.mergeServerDetails(
                item(id).copy(isPlayed = false, playbackPositionTicks = 42L),
                nowMs = 2_002L
            )

            assertTrue(acknowledged.isPlayed)
            assertEquals(0L, acknowledged.playbackPositionTicks)
            assertFalse(laterServerChange.isPlayed)
            assertEquals(42L, laterServerChange.playbackPositionTicks)
        } finally {
            NaturalCompletionDetailsGuard.clear(id)
        }
    }

    @Test
    fun `completion guard expires and can be explicitly cleared`() {
        val expiredId = "completion-expired"
        val clearedId = "completion-cleared"
        NaturalCompletionDetailsGuard.clear(expiredId)
        NaturalCompletionDetailsGuard.clear(clearedId)
        try {
            NaturalCompletionDetailsGuard.markCompleted(item(expiredId), nowMs = 3_000L)
            val afterExpiry = NaturalCompletionDetailsGuard.mergeServerDetails(
                item(expiredId).copy(isPlayed = false, playbackPositionTicks = 91L),
                nowMs = 3_000L + NaturalCompletionDetailsGuard.STALE_SERVER_WINDOW_MS
            )

            NaturalCompletionDetailsGuard.markCompleted(item(clearedId), nowMs = 4_000L)
            NaturalCompletionDetailsGuard.clear(clearedId)
            val afterClear = NaturalCompletionDetailsGuard.mergeServerDetails(
                item(clearedId).copy(isPlayed = false, playbackPositionTicks = 92L),
                nowMs = 4_001L
            )

            assertFalse(afterExpiry.isPlayed)
            assertEquals(91L, afterExpiry.playbackPositionTicks)
            assertFalse(afterClear.isPlayed)
            assertEquals(92L, afterClear.playbackPositionTicks)
        } finally {
            NaturalCompletionDetailsGuard.clear(expiredId)
            NaturalCompletionDetailsGuard.clear(clearedId)
        }
    }

    private fun item(id: String) = MediaItem(
        id = id,
        title = "Title",
        type = "Episode",
        year = "2026",
        imageTag = null,
        seriesName = "Series",
        episodeLabel = "S01 E01",
        playbackPositionTicks = 0L,
        runtimeTicks = 600_000_000L
    )
}

package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackReportingPolicyTest {
    @Test
    fun `transition stops previous item before starting next`() {
        val tracker = MusicPlaybackReportTracker()
        assertEquals(
            listOf(MusicPlaybackReportEvent.Playing("a", "session", 0L)),
            tracker.transitionTo("a", "session")
        )
        tracker.progress(positionTicks = 42_000L, isPaused = false)

        assertEquals(
            listOf(
                MusicPlaybackReportEvent.Stopped("a", "session", 42_000L),
                MusicPlaybackReportEvent.Playing("b", "session", 0L)
            ),
            tracker.transitionTo("b", "session")
        )
    }

    @Test
    fun `pause is immediate and final stop is emitted only once`() {
        val tracker = MusicPlaybackReportTracker()
        tracker.transitionTo("a", "session")

        assertEquals(
            listOf(MusicPlaybackReportEvent.Progress("a", "session", 15_000L, true)),
            tracker.progress(positionTicks = 15_000L, isPaused = true)
        )
        assertEquals(
            listOf(MusicPlaybackReportEvent.Stopped("a", "session", 20_000L)),
            tracker.finish(positionTicks = 20_000L)
        )
        assertTrue(tracker.finish(positionTicks = 21_000L).isEmpty())
        assertTrue(tracker.transitionTo(null, "").isEmpty())
    }

    @Test
    fun `duplicate transition callback does not duplicate playing report`() {
        val tracker = MusicPlaybackReportTracker()
        tracker.transitionTo("a", "session")

        assertTrue(tracker.transitionTo("a", "session").isEmpty())
    }

    @Test
    fun `explicit queue clear stops active item once`() {
        val tracker = MusicPlaybackReportTracker()
        tracker.transitionTo("a", "session")
        tracker.progress(positionTicks = 99_000L, isPaused = false)

        assertEquals(
            listOf(MusicPlaybackReportEvent.Stopped("a", "session", 99_000L)),
            tracker.transitionTo(null, "")
        )
        assertTrue(tracker.finish(positionTicks = 100_000L).isEmpty())
    }

    @Test
    fun `restarting an ended item creates a new reporting lifetime`() {
        val tracker = MusicPlaybackReportTracker()
        tracker.transitionTo("a", "session")
        tracker.finish(positionTicks = 120_000L)

        assertEquals(
            listOf(MusicPlaybackReportEvent.Playing("a", "session", 0L)),
            tracker.transitionTo("a", "session")
        )
    }

    @Test
    fun `duplicate track occurrences each create a reporting lifetime`() {
        val tracker = MusicPlaybackReportTracker()
        tracker.transitionTo("a", "session", entryId = "entry-0")
        tracker.progress(positionTicks = 10_000L, isPaused = false)

        assertEquals(
            listOf(
                MusicPlaybackReportEvent.Stopped("a", "session", 10_000L),
                MusicPlaybackReportEvent.Playing("a", "session", 0L)
            ),
            tracker.transitionTo("a", "session", entryId = "entry-1")
        )
        assertTrue(tracker.transitionTo("a", "session", entryId = "entry-1").isEmpty())
    }
}

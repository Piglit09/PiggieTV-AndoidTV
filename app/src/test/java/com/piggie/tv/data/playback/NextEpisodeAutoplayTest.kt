package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeAutoplayTest {
    private fun guards(
        enabled: Boolean = true,
        canceled: Boolean = false,
        hasNext: Boolean = true,
        foreground: Boolean = true,
        error: Boolean = false,
        earlyStop: Boolean = false,
        started: Boolean = false
    ) = AutoplayGuardState(enabled, canceled, hasNext, foreground, error, earlyStop, started)

    @Test fun countdownShowsInsideThreshold() {
        assertTrue(NextEpisodeAutoplayPolicy.shouldShowOverlay(20_000, guards(), false))
        assertFalse(NextEpisodeAutoplayPolicy.shouldShowOverlay(30_000, guards(), false))
        assertFalse(
            NextEpisodeAutoplayPolicy.shouldShowOverlay(
                20_000,
                guards(foreground = false),
                false
            )
        )
    }

    @Test fun cancelPreventsCountdownAndEndedFallback() {
        assertFalse(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.COUNTDOWN, guards(canceled = true)))
        assertFalse(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.ENDED, guards(canceled = true)))
    }

    @Test fun playNowBypassesDisabledSettingButNotSafetyGuards() {
        assertTrue(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.PLAY_NOW, guards(enabled = false)))
        assertFalse(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.PLAY_NOW, guards(error = true)))
        assertFalse(
            NextEpisodeAutoplayPolicy.shouldStart(
                AutoplayTrigger.PLAY_NOW,
                guards(foreground = false)
            )
        )
    }

    @Test fun endedFallbackStartsAndDuplicateCallbackDoesNot() {
        assertTrue(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.ENDED, guards()))
        assertFalse(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.ENDED, guards(started = true)))
    }

    @Test fun shownCountdownOwnsEndCallbackWhileDismissedOverlayUsesFallback() {
        assertEquals(AutoplayTrigger.COUNTDOWN, NextEpisodeAutoplayPolicy.endTrigger(true, false))
        assertEquals(AutoplayTrigger.ENDED, NextEpisodeAutoplayPolicy.endTrigger(false, false))
        assertEquals(AutoplayTrigger.ENDED, NextEpisodeAutoplayPolicy.endTrigger(true, true))
    }

    @Test fun upNextActionsHaveDistinctUnambiguousEffects() {
        val playNow = NextEpisodeAutoplayPolicy.effect(UpNextOverlayAction.PLAY_NOW)
        assertEquals(AutoplayTrigger.PLAY_NOW, playNow.trigger)
        assertFalse(playNow.cancelAutoplay)
        assertFalse(playNow.dismissOnly)

        val cancel = NextEpisodeAutoplayPolicy.effect(UpNextOverlayAction.CANCEL_AUTOPLAY)
        assertNull(cancel.trigger)
        assertTrue(cancel.cancelAutoplay)
        assertFalse(cancel.dismissOnly)

        val close = NextEpisodeAutoplayPolicy.effect(UpNextOverlayAction.CLOSE)
        assertNull(close.trigger)
        assertFalse(close.cancelAutoplay)
        assertTrue(close.dismissOnly)
    }

    @Test fun finalEpisodeAndMissingNextDoNotStart() {
        assertFalse(NextEpisodeAutoplayPolicy.shouldStart(AutoplayTrigger.ENDED, guards(hasNext = false)))
    }

    @Test fun nextEpisodeSelectorHandlesSeasonBoundaryAndSpecials() {
        val s1e2 = episode("s1e2", 1, 2)
        val s2e1 = episode("s2e1", 2, 1)
        assertEquals(s2e1, NextEpisodeSelector.select(s1e2.id, listOf(s1e2, s2e1)))

        val special1 = episode("sp1", 0, 1)
        val special2 = episode("sp2", 0, 2)
        assertEquals(special2, NextEpisodeSelector.select(special1.id, listOf(special1, special2)))
        assertNull(NextEpisodeSelector.select(special2.id, listOf(special1, special2)))
    }

    @Test fun previousEpisodeSelectorStaysInOrderedSeriesResponse() {
        val first = episode("first", 1, 1)
        val second = episode("second", 1, 2)
        val third = episode("third", 2, 1)

        assertEquals(second, PreviousEpisodeSelector.select(third.id, listOf(first, second, third)))
        assertNull(PreviousEpisodeSelector.select(first.id, listOf(first, second, third)))
        assertNull(PreviousEpisodeSelector.select("outside", listOf(first, second, third)))
    }

    private fun episode(id: String, season: Int, number: Int) = MediaItem(
        id = id,
        title = id,
        type = "Episode",
        year = null,
        imageTag = null,
        seriesName = "Series",
        episodeLabel = "S$season E$number",
        playbackPositionTicks = 0,
        runtimeTicks = 1,
        indexNumber = number,
        parentIndexNumber = season
    )
}

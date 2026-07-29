package com.piggie.tv.data.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingPlaybackPreferencesTest {
    @After
    fun tearDown() {
        PendingPlaybackPreferences.clearAllForTests()
    }

    @Test
    fun `audio and subtitle selections remain scoped to one item`() {
        PendingPlaybackPreferences.setAudio("movie-a", 3)
        PendingPlaybackPreferences.setSubtitle(
            "movie-a",
            SubtitleSelectionMode.TRACK,
            7
        )

        assertEquals(3, PendingPlaybackPreferences.get("movie-a").audioIndex)
        assertEquals(7, PendingPlaybackPreferences.get("movie-a").subtitleIndex)
        assertEquals(
            SubtitleSelectionMode.TRACK,
            PendingPlaybackPreferences.get("movie-a").subtitleMode
        )
        assertNull(PendingPlaybackPreferences.get("movie-b").audioIndex)
        assertEquals(
            SubtitleSelectionMode.DEFAULT,
            PendingPlaybackPreferences.get("movie-b").subtitleMode
        )
    }

    @Test
    fun `subtitle off does not leak a stale track index`() {
        PendingPlaybackPreferences.setSubtitle(
            "episode",
            SubtitleSelectionMode.TRACK,
            5
        )
        PendingPlaybackPreferences.setSubtitle("episode", SubtitleSelectionMode.OFF)

        assertEquals(SubtitleSelectionMode.OFF, PendingPlaybackPreferences.get("episode").subtitleMode)
        assertNull(PendingPlaybackPreferences.get("episode").subtitleIndex)
    }
}

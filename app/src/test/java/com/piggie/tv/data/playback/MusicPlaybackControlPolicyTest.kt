package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicPlaybackControlPolicyTest {
    @Test
    fun `pauses from play intent even while player is buffering`() {
        assertEquals(
            MusicPlaybackToggleAction.PAUSE,
            MusicPlaybackControlPolicy.toggleAction(
                playWhenReady = true,
                playbackEnded = false,
                playbackFailed = false
            )
        )
    }

    @Test
    fun `restarts ended and failed playback`() {
        assertEquals(
            MusicPlaybackToggleAction.RESTART,
            MusicPlaybackControlPolicy.toggleAction(
                playWhenReady = true,
                playbackEnded = true,
                playbackFailed = false
            )
        )
        assertEquals(
            MusicPlaybackToggleAction.RESTART,
            MusicPlaybackControlPolicy.toggleAction(
                playWhenReady = false,
                playbackEnded = false,
                playbackFailed = true
            )
        )
    }

    @Test
    fun `plays a normally paused item`() {
        assertEquals(
            MusicPlaybackToggleAction.PLAY,
            MusicPlaybackControlPolicy.toggleAction(
                playWhenReady = false,
                playbackEnded = false,
                playbackFailed = false
            )
        )
    }
}

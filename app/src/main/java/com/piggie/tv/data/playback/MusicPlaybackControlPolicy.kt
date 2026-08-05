package com.piggie.tv.data.playback

enum class MusicPlaybackToggleAction {
    PAUSE,
    PLAY,
    RESTART
}

/** Keeps Media3's play intent separate from whether audio is currently rendering. */
object MusicPlaybackControlPolicy {
    fun toggleAction(
        playWhenReady: Boolean,
        playbackEnded: Boolean,
        playbackFailed: Boolean
    ): MusicPlaybackToggleAction = when {
        playbackEnded || playbackFailed -> MusicPlaybackToggleAction.RESTART
        playWhenReady -> MusicPlaybackToggleAction.PAUSE
        else -> MusicPlaybackToggleAction.PLAY
    }
}

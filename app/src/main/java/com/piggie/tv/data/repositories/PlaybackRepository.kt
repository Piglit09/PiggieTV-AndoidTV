package com.piggie.tv.data.repositories

import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.playback.PlaybackNegotiator
import com.piggie.tv.data.playback.PlaybackStream

class PlaybackRepository(
    private val api: JellyfinNativeApi,
    private val negotiator: PlaybackNegotiator
) {
    fun getStream(session: NativeSession, itemId: String, positionTicks: Long): PlaybackStream =
        negotiator.getPlaybackStream(session, itemId, positionTicks)

    fun reportPlaying(session: NativeSession, itemId: String, playSessionId: String, posTicks: Long) {
        api.reportPlaying(session, itemId, playSessionId, posTicks)
    }

    fun reportProgress(session: NativeSession, itemId: String, playSessionId: String, posTicks: Long, paused: Boolean) {
        api.reportProgress(session, itemId, playSessionId, posTicks, paused)
    }

    fun reportStopped(session: NativeSession, itemId: String, playSessionId: String, posTicks: Long) {
        api.reportStopped(session, itemId, playSessionId, posTicks)
    }
}

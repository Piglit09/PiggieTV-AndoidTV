package com.piggie.tv.data.models

enum class PlaybackSkipSegmentType {
    INTRO,
    OUTRO
}

/** A server-supplied, exact playback range that can be skipped by the viewer. */
data class PlaybackSkipSegment(
    val type: PlaybackSkipSegmentType,
    val startTicks: Long,
    val endTicks: Long
) {
    val isValid: Boolean
        get() = startTicks >= 0L && endTicks > startTicks
}

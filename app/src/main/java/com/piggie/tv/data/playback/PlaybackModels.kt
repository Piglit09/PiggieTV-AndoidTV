package com.piggie.tv.data.playback

import com.piggie.tv.data.models.MediaCardPresentation

object PlaybackProgress {
    fun fraction(positionTicks: Long, runtimeTicks: Long): Float =
        if (positionTicks <= 0L || runtimeTicks <= 0L) 0f else (positionTicks.toDouble() / runtimeTicks.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

object ImageSizing {
    fun maxWidth(presentation: MediaCardPresentation): Int = when (presentation) {
        // Sized for the 1920x1080 AFTKM app surface with modest decode headroom.
        MediaCardPresentation.POSTER -> 240
        MediaCardPresentation.LANDSCAPE -> 400
        MediaCardPresentation.SQUARE -> 240
    }
}

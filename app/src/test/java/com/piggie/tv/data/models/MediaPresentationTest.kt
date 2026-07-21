package com.piggie.tv.data.models

import com.piggie.tv.data.playback.ImageSizing
import com.piggie.tv.data.playback.PlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPresentationTest {

    private fun mockItem(type: String) = MediaItem(
        id = "id", title = "T", type = type, year = null, imageTag = null, 
        seriesName = null, episodeLabel = null, playbackPositionTicks = 0L, runtimeTicks = 0L
    )

    @Test
    fun presentationSelectorReturnsCorrectTypes() {
        assertEquals(MediaCardPresentation.LANDSCAPE, MediaCardPresentationSelector.forItem(mockItem("Movie"), "Continue watching"))
        assertEquals(MediaCardPresentation.LANDSCAPE, MediaCardPresentationSelector.forItem(mockItem("Episode")))
        assertEquals(MediaCardPresentation.SQUARE, MediaCardPresentationSelector.forItem(mockItem("Audio")))
        assertEquals(MediaCardPresentation.POSTER, MediaCardPresentationSelector.forItem(mockItem("Movie")))
    }

    @Test
    fun playbackProgressFractionCalculatedCorrectly() {
        assertEquals(0.5f, PlaybackProgress.fraction(500L, 1000L))
        assertEquals(0f, PlaybackProgress.fraction(0L, 1000L))
        assertEquals(1f, PlaybackProgress.fraction(2000L, 1000L))
    }

    @Test
    fun imageSizingMaxWidthsAreTVAppropriate() {
        assertEquals(480, ImageSizing.maxWidth(MediaCardPresentation.POSTER))
        assertEquals(640, ImageSizing.maxWidth(MediaCardPresentation.LANDSCAPE))
        assertEquals(420, ImageSizing.maxWidth(MediaCardPresentation.SQUARE))
    }
}

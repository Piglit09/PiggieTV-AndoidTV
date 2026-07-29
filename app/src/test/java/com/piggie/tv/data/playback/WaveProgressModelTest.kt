package com.piggie.tv.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveProgressModelTest {
    @Test
    fun fractionAndSnapshotClampInvalidPlaybackValues() {
        assertEquals(0f, WaveProgressModel.fraction(500L, 0L), 0f)
        assertEquals(0f, WaveProgressModel.fraction(-1L, 1_000L), 0f)
        assertEquals(1f, WaveProgressModel.fraction(2_000L, 1_000L), 0f)

        val snapshot = WaveProgressModel.snapshot(
            positionMs = 1_500L,
            durationMs = 1_000L,
            bufferedPositionMs = -1L
        )
        assertEquals(1_000L, snapshot.positionMs)
        assertEquals(0L, snapshot.bufferedPositionMs)
        assertEquals(0L, snapshot.remainingMs)
    }

    @Test
    fun progressUsesBounded750MillisecondCadence() {
        assertEquals(750L, WaveProgressModel.UPDATE_INTERVAL_MS)
        assertTrue(WaveProgressModel.UPDATE_INTERVAL_MS in 500L..1_000L)
    }

    @Test
    fun playedBarsTrackProgress() {
        assertEquals(0, WaveProgressModel.playedBarCount(0f))
        assertEquals(12, WaveProgressModel.playedBarCount(0.5f))
        assertEquals(24, WaveProgressModel.playedBarCount(1f))
    }

    @Test
    fun waveformPatternIsNotFlat() {
        val heights = (0 until WaveProgressModel.DEFAULT_BAR_COUNT)
            .map(WaveProgressModel::barHeightFraction)

        assertTrue(heights.distinct().size > 4)
        assertTrue(heights.all { it in 0.25f..1f })
    }

    @Test
    fun timeFormattingSupportsTracksAndLongFormAudio() {
        assertEquals("0:00", WaveProgressModel.formatTime(0L))
        assertEquals("3:07", WaveProgressModel.formatTime(187_000L))
        assertEquals("1:02:03", WaveProgressModel.formatTime(3_723_000L))
    }
}
